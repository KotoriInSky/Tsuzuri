package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** 穷尽目标应用与域名专属应用后才交给默认浏览器；保留完整 Intent 参数。后台调用。 */
internal object CopyDirectIntentResolver {
    fun resolve(context: Context, targets: List<String>, preferredPackage: String?): Intent? {
        // intent: 目标只放行负一屏快递详情（PasscodeRules.isAllowedIntentTarget），不解析任意 Intent
        val candidates = targets.filter {
            !it.startsWith("intent:") || io.github.kotoriinsky.tsuzuri.core.PasscodeRules.isAllowedIntentTarget(it)
        }.mapNotNull { target -> runCatching {
            (if (target.startsWith("intent:")) Intent.parseUri(target, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .also { if (isWeb(it)) it.addCategory(Intent.CATEGORY_BROWSABLE) }
        }.getOrNull() }
        val pm = context.packageManager
        // 第一遍仅选应用，不能让第一条网页的浏览器截断后面的内容深链。
        for (candidate in candidates) {
            if (candidate.component != null || candidate.`package` != null) {
                if (candidate.resolveActivity(pm) != null) return candidate
                continue
            }
            if (preferredPackage != null) {
                val pinned = Intent(candidate).setPackage(preferredPackage)
                if (pinned.resolveActivity(pm) != null) return pinned
            }
            if (!isWeb(candidate)) {
                if (candidate.resolveActivity(pm) != null) return candidate
            } else domainApp(context, candidate)?.let { return Intent(candidate).setPackage(it) }
        }
        return candidates.firstOrNull { it.resolveActivity(pm) != null }
    }

    private fun isWeb(intent: Intent) = intent.data?.scheme?.lowercase() in setOf("http", "https")

    private fun domainApp(context: Context, target: Intent): String? = runCatching {
        val pm = context.packageManager
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browserMatches = pm.queryIntentActivities(generic, PackageManager.MATCH_DEFAULT_ONLY)
            .groupBy { it.activityInfo.packageName }.mapValues { (_, matches) -> matches.maxOf { it.match } }
        pm.queryIntentActivities(target, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName !in setOf("android", "com.android.intentresolver") &&
                it.match > (browserMatches[it.activityInfo.packageName] ?: 0) }
            .maxWithOrNull(compareBy<android.content.pm.ResolveInfo> { it.match }.thenBy { it.priority })
            ?.activityInfo?.packageName
    }.getOrNull()
}
