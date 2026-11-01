package io.github.kotoriinsky.tsuzuri.hook.compat

import android.os.Bundle
import android.net.Uri
import io.github.kotoriinsky.tsuzuri.core.PortalMediaRules
import io.github.kotoriinsky.tsuzuri.core.PortalDouyinRules
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import io.github.kotoriinsky.tsuzuri.core.PortalWeiboRules
import io.github.kotoriinsky.tsuzuri.core.PortalMediaSelection
import io.github.kotoriinsky.tsuzuri.core.PortalRuleMedia
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object PortalRuleRuntimeCompat {
    /** 只接受显式 JSON 容器和基础值；不加载来源 APK，不反射来源对象。 */
    fun tree(raw: String): Any? = runCatching {
        require(raw.length <= 256 * 1024 && raw.trimStart().firstOrNull() in listOf('{', '['))
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000')
        convert(value, 0)
    }.getOrNull()
    private fun convert(value: Any?, depth: Int): Any? {
        require(depth <= 24)
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                require(value.length() <= 1024)
                value.keys().asSequence().associateWith { convert(value.get(it), depth + 1) }
            }
            is JSONArray -> { require(value.length() <= 1024); (0 until value.length()).map { convert(value.get(it), depth + 1) } }
            is String, is Number, is Boolean -> value
            else -> error("快照含未知类型")
        }
    }
    fun pages(raw: String): List<*>? {
        val value = tree(raw)
        if (value is List<*>) return value
        val envelope = value as? Map<*, *> ?: return null
        if ((envelope["code"] as? Number)?.toInt() != 200) return null
        return (envelope["value"] as? String)?.let(::tree) as? List<*>
    }
    fun pick(bundle: Bundle, page: PortalMediaHostCompat.Page?): PortalRuleMedia.Selected? {
        val pkg = page?.pkg ?: bundle.getString("apkName")
        val activity = page?.activity ?: when (pkg) {
            "com.xingin.xhs" -> PortalMediaRules.NOTE_PAGE
            PortalDouyinRules.PACKAGE -> "com.ss.android.ugc.aweme.splash.SplashActivity"
            PortalWeiboRules.PACKAGE -> PortalWeiboRules.PREVIEW_PAGE
            else -> ""
        }
        val plans = PortalMediaRules.compiled(activity).filter { it.rule.pkg == pkg }
        val standard = when (pkg) {
            "com.xingin.xhs" -> PortalMediaRules.imagePaths
            PortalDouyinRules.PACKAGE -> PortalDouyinRules.standardPaths()
            else -> emptyList()
        }
        val known = plans.flatMap { it.fields }.toSet() + standard + "imgUrl"
        val values = known.filter(bundle::containsKey).associateWith { key ->
            when (val value = bundle.get(key)) {
                is CharSequence -> value.toString()
                is Uri -> value.toString()
                is Number -> value
                is List<*> -> value.takeIf { it.size <= 1024 && it.all { item -> item is String } }
                else -> null
            }
        }
        PortalRuleMedia.pick(plans, values, ::tree)?.let { return it }
        val strings = values.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val source = if (pkg == "com.xingin.xhs") PortalMediaSelection.pick(strings)
        else standard.firstNotNullOfOrNull { key -> strings[key]?.takeIf(PortalMediaContract::allowedUrl)?.let {
            PortalMediaSelection.Source(it, null)
        } }
            ?: PortalMediaSelection.pick(strings.filterKeys { it == "imgUrl" })
        source ?: return null
        return PortalRuleMedia.Selected(source, PortalMediaSelection.isLive(source, strings), "standard")
    }
}
