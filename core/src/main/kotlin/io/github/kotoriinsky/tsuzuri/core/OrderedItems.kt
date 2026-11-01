package io.github.kotoriinsky.tsuzuri.core

/**
 * 扇形清单（应用 + 快捷方式混排）的**编码与迁移** —— 纯逻辑，可脱离设备单测。
 *
 * ## 为什么是「一条一行」的字符串
 *
 * 顺序是这份数据的一半信息：扇形里每一项的位置由序号算出来（`FanMetrics.place(index)`），
 * 所以第 1 项在第 1 个槽位。而配置存储只有 `StringSet` 和 `String` 两种集合形态可用，
 * `StringSet` 的迭代顺序是实现细节（同一条结论在 `AppSettings` 的搜索历史上已经吃过一次），
 * 所以只能用单个字符串 + 分隔符表达。
 *
 * 分隔符取 `\n`：包名（Java 包名规范）与快捷方式 id 里都不会出现换行，
 * 所以它天然无歧义，且肉眼可读（`cat` 配置文件时分得清）。
 *
 * ## 一条隐形契约：空清单 = 删键
 *
 * 与 [PrefSpec.AppList] 一致 —— 空集/空串的语义是「用户没配过」，
 * 消费侧据此走自动挑选。所以**写入侧清空时必须 `remove(key)` 而不是写空串**。
 * 读到这里是空串时按「空清单」处理（两种情况结果一样，不必区分）。
 */
object OrderedItems {

    /** 分隔符。**只有这一处定义**，写入侧与读取侧都必须用它。 */
    const val SEP = "\n"

    /**
     * 编码。会做两件清洗，都是为了让「存进去的能被原样读出来」：
     *
     *  - 丢掉空白项（空串、纯空格）：它们读回来时会被当成不存在的项，
     *    留着只会让「配置里有 N 条、界面显示 M 条」这种最难查的不一致出现；
     *  - 把项内部的换行换成空格：包名/id 里不该有换行，真出现了（配置被手改过）
     *    会**把一个条目拆成两个**，那是最糟的失败方式 —— 扇子里多出一个点不动的项。
     */
    fun encode(items: List<String>): String = items
        .asSequence()
        .map { it.replace(SEP, " ").trim() }
        .filter { it.isNotEmpty() }
        // 去重保序：同一项出现两次会在扇子里画两个一模一样的图标
        .distinct()
        .joinToString(SEP)

    /**
     * 解码。`null` / 空串 / 只有分隔符 → 空清单（= 没配过）。
     *
     * 不做「格式不对就返回默认值」：这里的输入是我们自己写出去的，
     * 读回来认不出的只有「手改配置」这一种情况，那就按用户实际写的内容尽量还原。
     */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 老的**无序**清单 → 新的有序清单（一次性迁移）。
     *
     * ## 为什么需要一个"迁移"而不是直接读
     *
     * 老配置里这个键是 `StringSet`。同样的键换成读字符串会 `ClassCastException`，
     * 而 [Prefs] 的铁律是「读失败一律回退默认值」—— 于是用户的清单会**看起来凭空消失**，
     * 且因为回退是静默的，他会以为是自己改坏了。所以新类型必须用**新键**，
     * 老键的内容在这里翻译过来，老键本身**不删**（保留以便回滚）。
     *
     * ## 代价：老配置里本来就没有顺序
     *
     * 用户以前的顺序是「扇形按显示名排」，而那一份顺序从来没被存下来过。
     * 所以迁移只能给一个**确定的**初始顺序：应用在前、快捷方式在后，各自按字符串排序
     * （与 hook 侧旧行为「先应用后快捷方式」一致，组内顺序可能与旧观感略有出入）。
     * 这是能诚实给出的最好结果 —— 与其假装恢复了顺序，不如给个稳定起点让用户自己拖。
     *
     * @return 迁移后的顺序；`null` = 没什么可迁的（老清单为空）
     */
    fun migrateFromUnordered(old: Set<String>): List<String>? {
        // 先过一遍与 encode 同一套清洗：老键里可能有纯空白项（用户手改过配置，
        // 或者旧版本写入时没校验），它们 encode 出来是空串 ——
        // 直接返回 ["   "] 会让调用方以为「迁移出了 1 项」，而写进新键后读回来是 0 项。
        val apps = old.filter { !it.contains(ShortcutEntries.SEP) }.map { it.trim() }.filter { it.isNotEmpty() }
        val shortcuts = old.filter { it.contains(ShortcutEntries.SEP) }.map { it.trim() }.filter { it.isNotEmpty() }
        if (apps.isEmpty() && shortcuts.isEmpty()) return null
        return apps.sorted() + shortcuts.sorted()
    }
}
