package io.github.kotoriinsky.tsuzuri.ui.state

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.DisplayNameOverrides
import io.github.kotoriinsky.tsuzuri.core.IndexAppFilter
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.LauncherScope
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs

/**
 * 「桌面应用自定义」这一批（本批 1️⃣/2️⃣/3️⃣）在**设置界面**这一侧的键与读写。
 *
 * ## 为什么要有这个文件
 *
 * 这三件的键**没有**（也不该）在 `core/Catalog` 里各占一行参数行 —— 它们的入口是
 * 单独的页面（`AppListPickerScreen` / `SideIndexOverridesScreen`），不是一个能在行里
 * 拖的滑杆。这与 `icon_pack` 当初的处理方式是同一条纪律：`Catalog` 里声明成 `AppList`
 * 会得到一个 `FeatureRows` 的**禁用只读行**（那是「看得见、设不了」的死控件），
 * 所以只在需要时按**键**读写。
 *
 * 键名在这里只有一处定义：界面侧写的就是 hook 侧读的（`SideIndex.LookReader` 里那几个
 * 容错读法用同一个后缀），两边各写一遍字面量的表现是「界面里配好了、面板上永远不生效」，
 * 而编译、日志、装机全正常。
 *
 * ## 空集 = 删键（不是写空集）
 *
 * 与 `PrefSpec.AppList` 的契约一致：空集的语义是「用户没配过」，所以**写入侧清空时必须
 * `remove(key)`**，否则消费侧读到的是一个「配过但配成空」的状态 —— 那会让
 * 「用户把勾全取消」变成「一个都不生效」，而他的本意是「恢复默认」。
 */
object SideIndexCustom {

    /** 1️⃣ 哪些包算桌面（hook 侧 `LauncherScope` 消费）。 */
    const val LAUNCHER_PACKAGES = "launcher_packages"

    /** 2️⃣ 索引过滤的模式（`IndexAppFilter.MODES` 三档之一）。 */
    const val INDEX_FILTER_MODE = "index_filter_mode"

    /** 2️⃣ 索引过滤的清单（方向由 [INDEX_FILTER_MODE] 决定）。 */
    const val INDEX_FILTER_PACKAGES = "index_filter_packages"

    /** 3️⃣ 单应用显示名覆盖（hook 侧 `DisplayNameOverrides` 消费）。 */
    const val NAME_OVERRIDES = "name_overrides"

    /**
     * 3️⃣ 单应用图标覆盖。**与扇形共用同一个键后缀与同一份格式**，
     * 读写都走 [IconOverridesStore]（它按 [featureKey] 参数化，这里直接用）。
     */
    const val ICON_OVERRIDES = "icon_overrides"

    /** 清单声明的键里取特性键（`side_index.launcher_packages` → `side_index`）。 */
    fun featureKeyOf(spec: PrefSpec.AppList): String = spec.key.substringBeforeLast('.')

    private fun key(featureKey: String, pref: String) = Keys.pref(featureKey, pref)

    // ── 1️⃣ 桌面包名集合 ──────────────────────────────────────────────

    /** 读用户配的桌面包名集合。归一化交给 `core/LauncherScope`（判定与界面用同一份）。 */
    fun readLaunchers(prefs: Prefs, featureKey: String): Set<String> =
        LauncherScope.normalizeLaunchers(prefs.rawStringSet(key(featureKey, LAUNCHER_PACKAGES)))

    // ── 2️⃣ 索引过滤 ────────────────────────────────────────────────

    fun readFilterMode(prefs: Prefs, featureKey: String): String =
        IndexAppFilter.normalizeMode(prefs.rawString(key(featureKey, INDEX_FILTER_MODE), null))

    fun writeFilterMode(featureKey: String, mode: String) {
        // 模式是 Choice：写默认档时也**删键**（与「没配过」同一条语义，读回来结果一样）。
        if (IndexAppFilter.normalizeMode(mode) == IndexAppFilter.MODE_ALL) {
            ConfigStore.remove(key(featureKey, INDEX_FILTER_MODE))
        } else {
            ConfigStore.putString(key(featureKey, INDEX_FILTER_MODE), IndexAppFilter.normalizeMode(mode))
        }
    }

    fun readFilterPackages(prefs: Prefs, featureKey: String): Set<String> =
        IndexAppFilter.normalizePackages(prefs.rawStringSet(key(featureKey, INDEX_FILTER_PACKAGES)))

    /** 过滤现在到底管不管事（界面上那句「当前未启用」的判据，与 hook 侧同一个函数）。 */
    fun filterActive(prefs: Prefs, featureKey: String): Boolean =
        IndexAppFilter.isActive(readFilterMode(prefs, featureKey), readFilterPackages(prefs, featureKey))

    // ── 3️⃣ 单应用名字覆盖 ───────────────────────────────────────────

    fun readNames(prefs: Prefs, featureKey: String): Map<String, String> =
        DisplayNameOverrides.decode(
            runCatching { prefs.rawStringSet(key(featureKey, NAME_OVERRIDES)) }.getOrDefault(emptySet()),
        )

    /** 改一个应用的名字。空名字 = **删掉这一条**（不是存一个空串）。 */
    fun writeName(featureKey: String, packageName: String, name: String) {
        val current = readNames(ConfigStore.prefs(), featureKey).toMutableMap()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) current.remove(packageName) else current[packageName] = trimmed
        writeNames(featureKey, current)
    }

    fun removeName(featureKey: String, packageName: String) {
        val current = readNames(ConfigStore.prefs(), featureKey).toMutableMap()
        if (current.remove(packageName) == null) return
        writeNames(featureKey, current)
    }

    private fun writeNames(featureKey: String, names: Map<String, String>) {
        val encoded = DisplayNameOverrides.encode(names)
        if (encoded.isEmpty()) {
            ConfigStore.remove(key(featureKey, NAME_OVERRIDES))
        } else {
            ConfigStore.putStringSet(key(featureKey, NAME_OVERRIDES), encoded)
        }
    }

    // ── 「恢复默认」要清掉的键 ────────────────────────────────────────

    /**
     * 这个特性的**自定义键**（不在 `Catalog` 里的那几个）。
     *
     * ## 为什么必须有个集中处
     *
     * `FeatureRows.resetFeature` 是按 `spec.prefs` 逐条删的 —— 而这几个键**故意不在
     * Catalog 里**（见类注释：入口是单独页面，声明成参数行就是一个禁用的死控件）。
     * 于是「恢复默认」会漏掉它们，表现是**最让人火大的那一类**：用户点了恢复默认、
     * 界面上的开关全回到默认，但侧滑索引还是只在他勾的那几个桌面上生效 ——
     * 他没有任何理由想到「还有一份配置没被清掉」。
     *
     * ## 为什么 `icon_overrides` 要按特性键区分
     *
     * 扇形（`corner_small_window`）用的是**同一个键后缀**（同一份格式，见
     * `core/IconOverrideEntries`），而它的单项图标覆盖同样不在 Catalog 里 ——
     * 也就是说扇形的「恢复默认」今天也有同一个漏洞。本批**不顺手改它**：
     * 那是另一个特性的行为变更，该由它自己的改动带上（记在交付报告的遗留项里）。
     */
    fun customKeys(featureKey: String): List<String> = buildList {
        add(key(featureKey, LAUNCHER_PACKAGES))
        add(key(featureKey, INDEX_FILTER_MODE))
        add(key(featureKey, INDEX_FILTER_PACKAGES))
        add(key(featureKey, NAME_OVERRIDES))
        if (featureKey == Catalog.SIDE_INDEX) add(key(featureKey, ICON_OVERRIDES))
    }

    /** 清掉上面那几个键。**只删配置**：用户从相册选的那张图留在模块 App 的目录里
     *（删文件是 `IconOverridesStore.remove` 的附带动作，而这里是一次整体重置 ——
     * 留着文件不会让任何东西生效，也不会越用越多：同一个应用反复改图只覆盖同一个文件）。 */
    fun resetCustom(featureKey: String) {
        for (k in customKeys(featureKey)) ConfigStore.remove(k)
    }
}
