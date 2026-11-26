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
import io.github.kotoriinsky.tsuzuri.ui.screen.WIDGET_FEATURE_KEYS

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
    // 聚合二级页入口：用户在首页看到的名字，搜了要能直接进对应聚合页
    fun groupEntry(titleKey: String, summaryKey: String, dest: Dest, extra: String) {
        val title = tr(titleKey)
        val summary = tr(summaryKey)
        add(
            SearchEntry(
                title = title,
                summary = summary,
                haystack = (title + " " + summary + " " + extra).lowercase(),
                group = SearchGroup.FEATURE,
                dest = dest,
            )
        )
    }
    groupEntry("widget_settings_title", "widget_settings_summary", Dest.WidgetSettings, "widget 小部件 桌面 负一屏 堆叠")
    groupEntry("statusbar_settings_title", "statusbar_settings_summary", Dest.StatusBarSettings, "statusbar 状态栏 电池 通知图标 演示模式")
    groupEntry("proactive_scenes_title", "proactive_scenes_summary", Dest.Proactive, "proactive 主动智能 场景建议 新应用 音乐 常亮")

    for (spec in Catalog.settingsFeatures) {
        val title = tr(spec.titleI18n)
        val summary = tr(spec.summaryI18n)
        val isWidgetAggregated = spec.key in WIDGET_FEATURE_KEYS
        add(
            SearchEntry(
                title = title,
                summary = summary,
                haystack = (title + " " + summary + " " + spec.key).lowercase(),
                group = SearchGroup.FEATURE,
                dest = if (isWidgetAggregated) Dest.WidgetSettings else Dest.FeatureDetail(spec.key),
                badge = spec.key,
            )
        )
        // ⚠️ 参数必须能被搜到。参数一旦收进二级页，
        // 不索引它就等于**把参数藏起来** —— 用户记得「有个触发区宽度」，却哪都找不到。
        val groups = spec.prefGroups()
        val drill = spec.presentation.drillGroups
        val totalPrefs = groups.sumOf { if (it.id in drill) 1 else it.prefs.size }
        // 与 FeatureDetailScreen 同一套内联判据：单组 ≤ 8 或多分组总数 ≤ 12（且非 drillGroups、无兜底组）
        // 都在详情页内联展示，搜索直达详情页而不是用户没见过的孤立分组页
        val detailInlinedAll = groups.size <= 1 && totalPrefs <= 8
        val detailInlinedGrouped = !detailInlinedAll && totalPrefs <= 12 && groups.none { it.id == PREF_GROUP_OTHER }
        val groupLabels = groups.associate { it.id to tr(it.labelKey) }
        for (pref in spec.prefs) {
            // 隐藏分组（side_handle / corner_internal / side_internal）不进索引：
            // 搜到了点进去是一个界面上不存在的分组页
            if (!io.github.kotoriinsky.tsuzuri.core.PrefCategories.declarationOf(pref.group ?: PREF_GROUP_OTHER).exposed) continue
            val label = tr(pref.labelKey)
            val hint = pref.hintKey?.let(tr).orEmpty()
            val groupId = pref.group ?: PREF_GROUP_OTHER
            val inlinedHere = detailInlinedAll || (detailInlinedGrouped && groupId !in drill)
            add(
                SearchEntry(
                    title = label,
                    summary = hint.ifEmpty { title },
                    // 分组名也进索引：搜「触发区」要能一次列出那一组里的全部参数
                    haystack = (label + " " + hint + " " + pref.key + " " +
                        (groupLabels[groupId] ?: "")).lowercase(),
                    group = SearchGroup.FEATURE,
                    dest = when {
                        isWidgetAggregated -> Dest.WidgetSettings
                        inlinedHere -> Dest.FeatureDetail(spec.key, pref.key)
                        else -> Dest.FeatureSection(spec.key, groupId, pref.key)
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
        entry("settings.setup", "settings.setup.hint", Dest.Setup, "onboarding lsposed scope 模块状态 作用域"),
        entry("log.title", "log.export.hint", Dest.LogViewer, "log 日志 诊断"),
        entry("settings.export.config", "settings.backup.scope", Dest.SettingsPage("data"), "export backup zip 备份 导出"),
        entry("settings.share.backup", "settings.share.backup.hint", Dest.SettingsPage("data"), "share backup zip 分享 备份"),
        entry("settings.restore.config", "settings.restore.scope", Dest.SettingsPage("data"), "restore import zip json 恢复 导入"),
        entry("settings.copy.config", null, Dest.SettingsPage("data"), "export dump 复制配置"),
        entry("settings.reset.all", "settings.reset.all.hint", Dest.SettingsPage("data"), "reset default clear 重置"),
        entry("settings.reset.appearance", "settings.reset.appearance.hint", Dest.SettingsPage("data"), "reset appearance default"),
        entry("appearance.style.title", "appearance.style.hint", Dest.SettingsPage("appearance"), "miuix material theme 风格"),
        entry("appearance.dark.title", null, Dest.SettingsPage("appearance"), "dark light mode 深色 浅色"),
        entry("appearance.accent.title", null, Dest.SettingsPage("appearance"), "accent monet color 主题色"),
        entry("appearance.blur.title", "appearance.blur.hint", Dest.SettingsPage("appearance"), "blur 模糊 顶栏 底栏"),
        entry("appearance.floating.bar.title", "appearance.floating.bar.hint", Dest.SettingsPage("appearance"), "floating bar 悬浮底栏"),
        entry("appearance.glass.bar.title", "appearance.glass.bar.hint", Dest.SettingsPage("appearance"), "glass 液态玻璃"),
        entry("preview.title", null, Dest.SettingsPage("appearance"), "preview wallpaper 预览 底图 壁纸"),
        entry("settings.haptic", "settings.haptic.hint", Dest.SettingsPage("appearance"), "haptic vibrate feedback 振动"),
        entry("settings.auto.reload", "settings.auto.reload.hint", Dest.SettingsPage("appearance"), "auto reload hot reload 热重载"),
        entry("about.title", "settings.about.hint", Dest.About, "about 关于"),
        entry("about.changelog.title", null, Dest.About, "changelog release notes version 更新日志"),
        entry("about.version", null, Dest.About, "version build 版本"),
    )
}

/** 简单子串匹配。刻意不做模糊匹配 —— 索引只有几十条，子串够用且结果可预期。 */
fun List<SearchEntry>.search(query: String): List<SearchEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val terms = q.split(' ').filter { it.isNotEmpty() }
    return filter { entry -> terms.all { entry.haystack.contains(it) } }
}
