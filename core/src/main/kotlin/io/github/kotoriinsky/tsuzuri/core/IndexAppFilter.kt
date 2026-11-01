package io.github.kotoriinsky.tsuzuri.core

/**
 * 「索引里出现哪些应用」的判据（本批 2️⃣）。
 *
 * ## 为什么是「一个模式 + 一份清单」，而不是白名单 / 黑名单二选一
 *
 * 「只显示这几个」和「除了这几个都显示」都是真实需求，而**一份包名清单自己表达不了方向**
 * （只看值分不出 `{com.a}` 是想留下还是想排掉）。若各配一份清单，就会出现
 * 「两边都配了、谁赢」这种只能靠文档回答、且用户永远猜不到的问题（
 * 同一件事只在一个地方做）。所以：模式说方向，清单只有一份，不存在优先级。
 *
 * 选 [PrefSpec.AppList]（键 `index_filter_packages`）而不是 `OrderedList`：
 * **顺序在这里没有意义** —— 过滤是「在不在集合里」的判定，不产生任何排列。
 * `AppList` 的 KDoc 写死了「顺序无意义就用 Set」，而 `OrderedList` 是给扇形槽位那种
 * 「顺序决定落在哪一格」的清单用的。选错了不会报错，只会让后来的人以为可以拖排序。
 *
 * ## 默认 = 一个都不动
 *
 * [MODE_ALL] + 空清单 → [keeps] 恒为 true → 索引与改动前**逐位一致**（硬要求 1）。
 *
 * ## [MODE_INCLUDE] + 空清单 = 不过滤（不是「一个都不显示」）
 *
 * 两条已有契约指向同一个结论：[PrefSpec.AppList] 的「空集 = 用户没配过」，
 * 遵循「防错优先于报错」（清空清单不该变成空面板 ——
 * 用户会认为「我把功能点坏了」）。这条在界面上也要说出来，见 `AppListPickerScreen` 的横幅。
 *
 * ## 过滤发生在**构建索引时**，不在触摸路径上
 *
 * [keeps] 是纯内存判定，调用方（`AppIndex.Index` 的过滤）只在
 * 「索引重建 / 过滤配置变化」时跑一遍，绝不在 `onTouch` / `trackPointer` 上做
 * 这条纪律靠调用点的位置保证，不靠这个类自觉 —— 所以这里只有纯函数。
 */
object IndexAppFilter {

    /** 不过滤（默认）。 */
    const val MODE_ALL = "all"

    /** 排除清单里的应用。 */
    const val MODE_EXCLUDE = "exclude"

    /** 只保留清单里的应用（清单为空时退化成不过滤）。 */
    const val MODE_INCLUDE = "include"

    /** 配置里能写的三个取值，顺序即界面上的顺序。 */
    val MODES: List<String> = listOf(MODE_ALL, MODE_EXCLUDE, MODE_INCLUDE)

    /**
     * 归一化模式取值：trim + 小写，认不出的按 [MODE_ALL]。
     *
     * 认不出时**不过滤**（而不是「过滤掉一切」）—— 与「读配置失败一律回退默认值」同一条
     * 铁律：读不懂的配置绝不该改变系统行为。
     */
    fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        MODE_EXCLUDE -> MODE_EXCLUDE
        MODE_INCLUDE -> MODE_INCLUDE
        else -> MODE_ALL
    }

    /** 归一化清单：前后空白 trim、空项丢掉（同 [LauncherScope.normalizeLaunchers]）。 */
    fun normalizePackages(packages: Iterable<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (p in packages) {
            val t = p?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /**
     * 这份配置**到底管不管事**。
     *
     * 用来在界面上说「已选 N 个 · 当前未启用」与
     * 在安装报告里区分 `off` / `exclude(N)`。**空清单一律不管事** —— 两种模式都算没配过。
     */
    fun isActive(mode: String?, packages: Collection<String>): Boolean =
        packages.isNotEmpty() && normalizeMode(mode) != MODE_ALL

    /**
     * 真正生效的模式。`include` + 空清单退化成 [MODE_ALL]（见类注释）。
     *
     * 单独抽出来是因为它有两个消费者（[keeps] 与界面上的状态文案），
     * 各算一遍就会出现「界面说只保留、实际却不过滤」这种只有用户能发现的偏差。
     */
    fun effectiveMode(mode: String?, packages: Collection<String>): String {
        val m = normalizeMode(mode)
        if (packages.isEmpty()) return MODE_ALL
        return m
    }

    /**
     * 这个包该不该出现在索引里。
     *
     * @param packageName `null` = 这一项没有包名（理论上不该发生）。此时
     *   排除模式**保留**它（不改行为的那一侧），只保留模式**丢掉**它 ——
     *   它连自己是谁都不知道，留下来就等于「用户明确排除了它，它却还在」。
     */
    fun keeps(mode: String?, packages: Collection<String>, packageName: String?): Boolean =
        when (effectiveMode(mode, packages)) {
            MODE_EXCLUDE -> packageName == null || packageName !in packages
            MODE_INCLUDE -> packageName != null && packageName in packages
            // MODE_ALL：不过滤
            else -> true
        }
}
