package io.github.kotoriinsky.tsuzuri.ui.nav

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import top.yukonga.miuix.kmp.nav.core.NavKey
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.prefCategories
import io.github.kotoriinsky.tsuzuri.core.PrefSpec

/** 只保存稳定键；重建时从当前 Catalog 解析，不把配置声明快照放进 Bundle。 */
internal fun Dest.stateKey(): String {
    val fields = when (this) {
        Dest.Home -> listOf("home")
        Dest.Settings -> listOf("settings")
        Dest.About -> listOf("about")
        Dest.Credits -> listOf("credits")
        is Dest.SettingsPage -> listOf("settings-page", section)
        Dest.Search -> listOf("search")
        Dest.Setup -> listOf("setup")
        Dest.WidgetSettings -> listOf("widget-settings")
        Dest.Proactive -> listOf("proactive")
        Dest.LogViewer -> listOf("logs")
        is Dest.FeatureDetail -> listOf("feature", featureKey, focusPrefKey.orEmpty())
        is Dest.FeatureSection -> listOf("section", featureKey, group, focusPrefKey.orEmpty())
        is Dest.FeatureCategory -> listOf("category", featureKey, category, focusPrefKey.orEmpty())
        is Dest.FanItems -> listOf("items", spec.key)
        is Dest.AppPicker -> listOf("apps", spec.key)
        is Dest.AddItem -> listOf("add", spec.key)
        // 合并说明：传送门线把 IconPackPicker 的 spec 改成可空（复制直达许可页没有用户清单），
        // 返回栈键不能再从 spec.key 派生 —— 改用页面自带的 featureKey + 预览包名，重建时回查 Catalog。
        is Dest.IconPackPicker -> listOf("packs", featureKey, previewPackage.orEmpty())
        is Dest.ShortcutImport -> listOf("shortcuts", spec.key)
        is Dest.LinkImport -> listOf("links", spec.key)
        is Dest.ActivityImport -> listOf("activities", spec.key)
        is Dest.FanIconOverrides -> listOf("overrides", spec.key, focusKey.orEmpty())
        is Dest.AppListPicker -> listOf("app-list", spec.key)
        is Dest.SideIndexOverrides -> listOf("side-overrides", spec.key)
        is Dest.IconPackDrawable -> listOf("drawable", spec.key, targetKey, targetLabel)
        is Dest.PasscodeRules -> listOf("rules", spec.key)
    }
    return fields.joinToString("/") { Uri.encode(it) }
}

private fun restoreDest(route: String): Dest? {
    val fields = route.split('/').map(Uri::decode)
    val arg = fields.getOrNull(1).orEmpty()
    val pref = Catalog.features.asSequence().flatMap { it.prefs.asSequence() }.find { it.key == arg }
    val list = pref as? PrefSpec.OrderedList
    return when (fields.firstOrNull()) {
        "home" -> Dest.Home
        "modules" -> Dest.Home
        "settings" -> Dest.Settings
        "about" -> Dest.About
        "credits" -> Dest.Credits
        // 分区被改名或下架就丢弃该页，不恢复一个指向空页面的栈
        "settings-page" -> io.github.kotoriinsky.tsuzuri.ui.screen.SettingsSection.of(arg)?.let { Dest.SettingsPage(it.id) }
        "search" -> Dest.Search
        "setup" -> Dest.Setup
        "widget-settings" -> Dest.WidgetSettings
        "proactive" -> Dest.Proactive
        "logs" -> Dest.LogViewer
        "feature" -> Catalog.find(arg)?.let { Dest.FeatureDetail(arg, fields.getOrNull(2)?.takeIf(String::isNotEmpty)) }
        "section" -> fields.getOrNull(2)?.let { group ->
            Catalog.find(arg)?.takeIf { feature -> feature.prefs.any { it.group == group } }
                ?.let { Dest.FeatureSection(arg, group, fields.getOrNull(3)?.takeIf(String::isNotEmpty)) }
        }
        // 类别页：重建时回查 Catalog —— 类别被下架（或旧返回栈里的标识不存在）就丢弃该页，
        // 不把一个指向空页面的栈恢复给用户。
        "category" -> fields.getOrNull(2)?.let { categoryId ->
            Catalog.find(arg)?.takeIf { feature ->
                feature.prefCategories().any { it.category.id == categoryId }
            }?.let { Dest.FeatureCategory(arg, categoryId, fields.getOrNull(3)?.takeIf(String::isNotEmpty)) }
        }
        "items" -> list?.let(Dest::FanItems)
        "apps" -> list?.let(Dest::AppPicker)
        "add" -> list?.let(Dest::AddItem)
        // 合并说明：spec 可空，重建时从当前 Catalog 回查该特性的有序清单（只用于预览目标），
        // 标题文案退回特性自己的标题键 —— 复制直达许可页就是没有清单的那一种。
        "packs" -> Catalog.find(arg)?.let { feature ->
            val list = feature.prefs.filterIsInstance<PrefSpec.OrderedList>().firstOrNull()
            Dest.IconPackPicker(
                featureKey = arg,
                labelKey = list?.labelKey ?: feature.titleI18n,
                spec = list,
                previewPackage = fields.getOrNull(2)?.takeIf(String::isNotEmpty),
            )
        }
        "shortcuts" -> list?.let(Dest::ShortcutImport)
        "links" -> list?.let(Dest::LinkImport)
        "activities" -> list?.let(Dest::ActivityImport)
        "overrides" -> list?.let { Dest.FanIconOverrides(it, fields.getOrNull(2)?.takeIf(String::isNotEmpty)) }
        "side-overrides" -> list?.let { Dest.SideIndexOverrides(it) }
        "drawable" -> list?.let {
            if (fields.size == 4) Dest.IconPackDrawable(it, fields[2], fields[3]) else null
        }
        "app-list" -> (pref as? PrefSpec.AppList)?.let(Dest::AppListPicker)
        "rules" -> (pref as? PrefSpec.Screen)?.let(Dest::PasscodeRules)
        else -> null
    }
}

/**
 * 返回栈的保存与恢复。栈根固定为 [Dest.Home]（tab 页）：旧版本保存的栈里可能以
 * Settings 开头、或者根本没有 Home，恢复时一律规整成「Home + 下钻页」。
 */
internal val DestStackSaver = listSaver<SnapshotStateList<NavKey>, String>(
    save = { stack -> stack.filterIsInstance<Dest>().map { it.stateKey() } },
    restore = { routes ->
        val drill = routes.mapNotNull(::restoreDest).filter { !it.isTopLevel }
        mutableStateListOf<NavKey>(Dest.Home).apply { addAll(drill) }
    },
)
