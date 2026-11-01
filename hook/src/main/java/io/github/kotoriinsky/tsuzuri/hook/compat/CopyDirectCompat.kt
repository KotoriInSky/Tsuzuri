package io.github.kotoriinsky.tsuzuri.hook.compat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.graphics.drawable.Drawable
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import org.json.JSONObject

/** 仅适配实测 4.12.26；所有厂商符号在此检查并访问。 */
class CopyDirectCompat private constructor(private val loader: ClassLoader, val context: Context) {
    private fun clazz(name: String): Class<*> = listOf(name, "defpackage.$name").firstNotNullOfOrNull {
        runCatching { Class.forName(it, false, loader) }.getOrNull()
    } ?: error("缺少符号 $name")
    val eventMethod = clazz("rl9").declaredMethods.single {
        it.name == "n" && it.parameterTypes.toList() == listOf(String::class.java, String::class.java, String::class.java, java.util.HashMap::class.java)
    }.apply { isAccessible = true }
    val appsMethod = clazz("x5").declaredMethods.single {
        it.name == "b" && it.parameterTypes.toList() == listOf(Context::class.java, String::class.java, Map::class.java)
    }.apply { isAccessible = true }
    val clickMethod = clazz("um0").getDeclaredMethod("b", String::class.java).apply { isAccessible = true }
    val longClickMethod = clazz("jm0").getDeclaredMethod("onLongClick", View::class.java).apply { isAccessible = true }
    val menuMethod = clazz("jt2").getDeclaredMethod("onCreate", Bundle::class.java).apply { isAccessible = true }
    val iconMethod = clazz("lka").getDeclaredMethod("c", String::class.java, String::class.java, ImageView::class.java).apply { isAccessible = true }
    private val bubbleClass = Class.forName("com.xiaomi.ai.bubble.core.bubbleview.view.BubbleView", false, loader)
    private val appsFragment = Class.forName("com.xiaomi.aicr.decisionframework.settings.presentation.detail.AbilityMultiAppDetailActivity\$a", false, loader)
    private val appsActivity = Class.forName("com.xiaomi.aicr.decisionframework.settings.presentation.detail.AbilityMultiAppDetailActivity", false, loader)
    val appsRenderMethod = appsFragment.getDeclaredMethod("P", appsFragment, List::class.java).apply { isAccessible = true }
    private val preferenceClass = Class.forName("androidx.preference.Preference", false, loader)
    private val iconField = preferenceClass.getDeclaredField("mIcon").apply { isAccessible = true }
    private val iconSetter = preferenceClass.getMethod("k0", Drawable::class.java)
    private val titleSetter = preferenceClass.getMethod("w0", String::class.java)
    private val summarySetter = preferenceClass.getMethod("t0", CharSequence::class.java)
    private val abilityGetter = appsFragment.getDeclaredMethod("R")
    private val rowsField = appsFragment.getDeclaredField("appPrefByKey").apply { isAccessible = true }
    private val classifierClass = clazz("os3")
    private val dataClass = clazz("ss3")
    private val classify = classifierClass.getDeclaredMethod("b", Context::class.java, Bundle::class.java)
    private val data = dataClass.getDeclaredMethod("b", Context::class.java, Bundle::class.java)
    private val managerClass = clazz("um0")
    private val cueClass = Class.forName("com.xiaomi.ai.bubble.core.model.CueData", false, loader)
    private val fromJson = cueClass.getMethod("fromJson", String::class.java)
    val bubbleBindMethod = bubbleClass.getDeclaredMethod("a", cueClass).apply { isAccessible = true }
    private val show = managerClass.getDeclaredMethod("l", List::class.java)
    private val queueClass = clazz("ql0")
    private val switchClass = clazz("hu8")
    private val readSwitch = switchClass.getDeclaredMethod("c", String::class.java)
    private val switches by lazy { switchClass.getConstructor(Context::class.java).newInstance(context) }
    private fun manager(): Any? = queueClass.getField("f").get(null)?.let { queueClass.getField("e").get(it) }

    /** 沿厂商依赖容器获取缓存的窗口管理器；首次复制也不另起服务或自建队列。 */
    private fun readyManager(): Any {
        manager()?.let { return it }
        val initializer = Class.forName("com.xiaomi.ai.bubble.core.bridge.a", false, loader)
        val proxy = initializer.getDeclaredMethod("a", Context::class.java).invoke(null, context)
        val processor = proxy.javaClass.getDeclaredField("perceptionProcessor").apply { isAccessible = true }.get(proxy)
        val processorClass = clazz("cm0")
        return processorClass.getDeclaredMethod("a", processorClass).invoke(null, processor)
            ?: error("原生窗口管理器尚未就绪")
    }

    data class NativeResult(val status: Int, val tag: Int = 0, val intent: Intent? = null)

    /** 后台调用；原生内部云解析保持其自己的认证、缓存和超时。 */
    fun nativeResult(text: String, source: String): NativeResult {
        val input = Bundle().apply { putString("in", JSONObject().put("copyText", text).put("clipSource", source).toString()) }
        val output = classify.invoke(classifierClass.getConstructor().newInstance(), context, input) as Bundle
        val body = JSONObject(output.getString("target_out") ?: return NativeResult(-103))
        val status = body.optInt("status", -103)
        if (status != 0) return NativeResult(status)
        val tag = body.optInt("tag")
        // 导航、电话等继续由原生能力处理，本模块只接复制直达。
        if (tag !in COPY_TAGS) return NativeResult(0, tag)
        val actionInput = Bundle().apply { putString("in", body.toString()) }
        val actionOutput = data.invoke(dataClass.getConstructor().newInstance(), context, actionInput) as Bundle
        val result = JSONObject(actionOutput.getString("target_out") ?: return NativeResult(0, tag))
        val actionData = result.optString("copyDirectData")
        val uri = if (actionData.isEmpty()) "" else JSONObject(actionData).optString("intentUri")
        return NativeResult(0, tag, uri.takeIf { it.isNotEmpty() }?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) })
    }

    fun allowed(packageName: String?, category: String): Boolean =
        readSwitch.invoke(switches, "settings.df_main_switch") == true &&
            readSwitch.invoke(switches, "settings.copy_text_jump_app.$category") == true &&
            (packageName == null || readSwitch.invoke(switches, "settings.copy_text_jump_app.$category#$packageName") == true)

    fun category(tag: Int, packageName: String?): String = when {
        tag == 11 -> "jump_url"
        tag in setOf(5, 501, 502) -> "tracking_number"
        tag in setOf(8, 10) || packageName in setOf("com.baidu.netdisk", "com.xunlei.downloadprovider", "com.quark.browser", "com.alicloud.databox") -> "jump_link"
        else -> "command"
    }

    fun copyTag(tag: Int) = tag in COPY_TAGS

    /** 必须主线程调用，使用厂商原有容器、动画、来源前台约束和长按菜单。 */
    fun showCue(id: String, eventId: String, title: String, source: String, targetPackage: String?, seconds: Int, category: String) {
        val card = JSONObject().put("title", JSONObject().put("text", title))
        if (targetPackage != null) card.put("startIcon", JSONObject().put("type", "application").put("value", targetPackage))
        val display = JSONObject().put("briefCard", card).put("expandable", false)
            .put("position", JSONObject().put("category", "global"))
            .put("settings", JSONObject().put("reason", JSONObject().put("text", "复制文本中包含可打开的内容"))
                .put("source_package", source).put("switch_key", "settings.copy_text_jump_app.$category")
                .put("switch_reason", "不再建议口令跳转"))
        val json = JSONObject().put("cueId", id).put("eventId", eventId).put("type", "action")
            .put("category", id).put("aliveTime", seconds).put("display", display)
            .put("support_aggregate", false)
        val cue = fromJson.invoke(null, json.toString()) ?: error("胶囊数据为空")
        show.invoke(readyManager(), listOf(cue))
    }

    /** 原生事件队列会异步执行，等待实际入仓后再报告接收。 */
    fun contains(id: String): Boolean {
        val current = manager() ?: return false
        val repository = managerClass.getField("c").get(current)
        return repository.javaClass.getDeclaredMethod("b", String::class.java).invoke(repository, id) != null
    }
    fun dismiss(id: String) {
        val current = manager() ?: return
        val state = managerClass.getField("e").get(current)
        state.javaClass.getDeclaredMethod("p", String::class.java).invoke(state, id)
    }
    fun longClickId(listener: Any?): String? = runCatching {
        val bubble = listener?.javaClass?.getField("b")?.get(listener) ?: return null
        val cue = bubble.javaClass.getMethod("getCueData").invoke(bubble) ?: return null
        cueClass.getMethod("getCueId").invoke(cue) as? String
    }.getOrNull()

    /** 仅关联复制直达胶囊与原始事件，不用后来变化的剪贴板作为分享内容。 */
    fun copyCueIdentity(cue: Any): Pair<String, String>? {
        val display = cueClass.getMethod("getDisplay").invoke(cue) ?: return null
        val settings = display.javaClass.getField("settings").get(display) ?: return null
        val switch = settings.javaClass.getField("switch_key").get(settings) as? String ?: return null
        if (!switch.startsWith("settings.copy_text_jump_app.")) return null
        return (cueClass.getMethod("getCueId").invoke(cue) as String) to
            (cueClass.getMethod("getEventId").invoke(cue) as? String ?: return null)
    }
    fun supportedApp(packageName: String, name: String): Any =
        clazz("w5").getConstructor(String::class.java, String::class.java).newInstance(packageName, applicationName(packageName, name))
    fun supportedPackage(app: Any): String = app.javaClass.getMethod("b").invoke(app) as String

    /** 用普通网址询问系统，排除没有默认目标时返回的选择器。 */
    fun defaultBrowser(): String? = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val info = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        info?.packageName?.takeUnless { it in setOf("android", "com.android.intentresolver") || info.name.contains("ResolverActivity") }
    }.getOrNull()

    fun browserApp(): Any = supportedApp(CopyDirect.DEFAULT_BROWSER, "默认浏览器")
    fun isAppsActivity(activity: android.app.Activity): Boolean = appsActivity.isInstance(activity)
    fun isWebPage(fragment: Any): Boolean = appsFragment.isInstance(fragment) &&
        abilityGetter.invoke(fragment) == "settings.copy_text_jump_app.jump_url"
    fun renderBrowser(fragment: Any) {
        appsRenderMethod.invoke(null, fragment, listOf(browserApp()))
    }

    fun applicationName(packageName: String, fallback: String): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse { CopyDirect.fullName(packageName, fallback) }

    data class AppRow(val key: String, val preference: Any, val packageName: String, val name: String, val nativeIcon: Drawable?)
    data class AppAppearance(val row: AppRow, val name: String, val icon: Drawable?, val themed: Boolean, val summary: String? = null)

    /** 只捕获原生行；原生能力分类、布局和许可监听器均保留。主线程调用。 */
    fun appRows(fragment: Any, apps: List<*>): List<AppRow> {
        val ability = abilityGetter.invoke(fragment) as String
        if (!ability.startsWith("settings.copy_text_jump_app.")) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val rows = rowsField.get(fragment) as Map<String, Any>
        return apps.filterNotNull().mapNotNull { item ->
            val pkg = supportedPackage(item)
            val key = "$ability#$pkg"
            val row = rows[key] ?: return@mapNotNull null
            val name = item.javaClass.getMethod("a").invoke(item) as String
            val iconPackage = if (pkg == CopyDirect.DEFAULT_BROWSER) defaultBrowser() ?: CopyDirect.DEFAULT_BROWSER else pkg
            AppRow(key, row, iconPackage, name, iconField.get(row) as? Drawable)
        }
    }

    /** 只更新仍属于当前页面的行，防止后台完成时写到已销毁或重建的偏好行。 */
    fun decorateApps(fragment: Any, appearances: List<AppAppearance>): Int {
        @Suppress("UNCHECKED_CAST")
        val rows = rowsField.get(fragment) as Map<String, Any>
        var decorated = 0
        appearances.forEach {
            if (rows[it.row.key] === it.row.preference) {
                titleSetter.invoke(it.row.preference, it.name)
                summarySetter.invoke(it.row.preference, it.summary)
                iconSetter.invoke(it.row.preference, it.icon)
                decorated++
            }
        }
        return decorated
    }

    companion object {
        private val COPY_TAGS = setOf(2, 3, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 501, 502, 601, 602)
        fun create(loader: ClassLoader): CopyDirectCompat {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(CopyDirect.ENGINE_PACKAGE, 0)
            check(version.longVersionCode == 2030041226L && version.versionName == "4.12.26")
            val os = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.mi.os.version.code", "0") as String
            check(os.toFloatOrNull() == 4f)
            return CopyDirectCompat(loader, app)
        }
    }
}
