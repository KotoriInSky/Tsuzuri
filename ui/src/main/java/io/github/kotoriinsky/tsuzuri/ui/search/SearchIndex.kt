package io.github.kotoriinsky.tsuzuri.ui.search

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.kotoriinsky.tsuzuri.core.PREF_GROUP_OTHER
import io.github.kotoriinsky.tsuzuri.core.prefGroups
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest

/**
 * 一条可被搜索到的条目。
 *
 * `haystack` 里同时放**本地化文案**和**原始配置键**：
 * 用户会搜「小窗」（界面词），维护者会搜 `corner_small_window`（配置键）。
 * 只索引界面文案的话，后者永远搜不到，而排障时恰恰最需要它。
 */
data class SearchEntry(
    val title: String,
    val summary: String?,
    val haystack: String,
    val group: SearchGroup,
    /** 点进去要跳哪。 */
    val dest: Dest,
    /** 右侧强调片段，通常是配置键。 */
    val badge: String? = null,
)

enum class SearchGroup(val labelI18n: String) {
    FEATURE("search.section.features"),
    SETTING("search.section.settings"),
}

/**
 * 搜索索引。
 *
 * 索引来源：Catalog 的每个特性（标题 + 说明 + key）、每个特性的每个参数、
 * 以及设置页里那几个固定入口。**加一个特性不用改这里** ——
 * 这正是「界面由 Catalog 生成」的好处。
 *
 * 为什么用 `(String) -> String` 而不是直接调 `fpString`：
 * `fpString` 是 @Composable，不能出现在 `remember { }` 的 lambda 里
 * （这是本项目里反复踩的一类编译错误）。换成一个普通函数之后，
 * 整个索引构建变成纯逻辑，可以放进 `remember` 只跑一次。
 */
@Composable
fun rememberSearchIndex(): List<SearchEntry> {
    val context = LocalContext.current
    val resolve: (String) -> String = remember(context) { resolver(context) }
    return remember(resolve) { buildIndex(resolve) }
}

private fun resolver(context: Context): (String) -> String = { key ->
    val res = fpStringRes(context, key)
    // 查不到时 fpStringRes 返回 0，这里退化成键名 —— 与运行时 fpString 行为一致
    if (res == 0) key else context.getString(res)
}

private fun buildIndex(tr: (String) -> String): List<SearchEntry> = buildList {
    for (spec in Catalog.settingsFeatures) {
        val title = tr(spec.titleI18n)
        val summary = tr(spec.summaryI18n)
        add(
            SearchEntry(
                title = title,
                summary = summary,
                haystack = (title + " " + summary + " " + spec.key).lowercase(),
                group = SearchGroup.FEATURE,
                dest = Dest.FeatureDetail(spec.key),
                badge = spec.key,
            )
        )
        // ⚠️ 参数必须能被搜到。参数一旦收进二级页，
        // 不索引它就等于**把参数藏起来** —— 用户记得「有个触发区宽度」，却哪都找不到。
        val groups = spec.prefGroups()
        // 只有一组时参数是**内联展开在详情页**的（`FeatureDetailScreen` 的判断），
        // 所以搜索也要跳到详情页而不是一个用户从没见过的二级页
        val inlined = groups.size <= 1 && spec.prefs.size <= 8
        val groupLabels = groups.associate { it.id to tr(it.labelKey) }
        for (pref in spec.prefs) {
            // 隐藏分组（side_handle / corner_internal / side_internal）不进索引：
            // 搜到了点进去是一个界面上不存在的分组页
            if (!io.github.kotoriinsky.tsuzuri.core.PrefCategories.declarationOf(pref.group ?: PREF_GROUP_OTHER).exposed) continue
            val label = tr(pref.labelKey)
            val hint = pref.hintKey?.let(tr).orEmpty()
            val groupId = pref.group ?: PREF_GROUP_OTHER
            add(
                SearchEntry(
                    title = label,
                    summary = hint.ifEmpty { title },
                    // 分组名也进索引：搜「触发区」要能一次列出那一组里的全部参数
                    haystack = (label + " " + hint + " " + pref.key + " " +
                        (groupLabels[groupId] ?: "")).lowercase(),
                    group = SearchGroup.FEATURE,
                    dest = if (inlined) {
                        Dest.FeatureDetail(spec.key, pref.key)
                    } else {
                        Dest.FeatureSection(spec.key, groupId, pref.key)
                    },
                    badge = pref.key.removePrefix(spec.key + "."),
                )
            )
        }
    }
    addAll(settingEntries(tr))
}

/** 设置页里的固定入口，同样可以被搜到。 */
private fun settingEntries(tr: (String) -> String): List<SearchEntry> {
    fun entry(titleKey: String, hintKey: String?, dest: Dest, extraKeys: String): SearchEntry {
        val title = tr(titleKey)
        val hint = hintKey?.let(tr)
        return SearchEntry(
            title = title,
            summary = hint,
            haystack = (title + " " + (hint ?: "") + " " + extraKeys).lowercase(),
            group = SearchGroup.SETTING,
            dest = dest,
        )
    }
    return listOf(
        entry("settings.setup", "settings.setup.hint", Dest.Setup, "onboarding lsposed scope"),
        entry("settings.copy.config", null, Dest.SettingsPage("data"), "export dump"),
        entry("settings.reset.all", "settings.reset.all.hint", Dest.SettingsPage("data"), "reset default clear"),
        entry("settings.reset.appearance", "settings.reset.appearance.hint", Dest.SettingsPage("data"), "reset appearance default"),
        entry("settings.haptic", "settings.haptic.hint", Dest.SettingsPage("appearance"), "haptic vibrate feedback"),
        entry("settings.auto.reload", "settings.auto.reload.hint", Dest.SettingsPage("appearance"), "auto reload hot reload"),
        entry("appearance.style.title", "appearance.style.hint", Dest.SettingsPage("appearance"), "miuix material theme"),
        entry("appearance.dark.title", null, Dest.SettingsPage("appearance"), "dark light mode"),
        entry("appearance.accent.title", null, Dest.SettingsPage("appearance"), "accent monet color"),
        entry("about.changelog.title", null, Dest.About, "changelog release notes version"),
        entry("about.version", null, Dest.About, "version build"),
    )
}

/** 简单子串匹配。刻意不做模糊匹配 —— 索引只有几十条，子串够用且结果可预期。 */
fun List<SearchEntry>.search(query: String): List<SearchEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val terms = q.split(' ').filter { it.isNotEmpty() }
    return filter { entry -> terms.all { entry.haystack.contains(it) } }
}
