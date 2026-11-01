package io.github.kotoriinsky.tsuzuri.core

/**
 * 长列表的字母索引（分组标题 + 侧滑索引条）的**纯逻辑**。
 *
 * ## 为什么在契约层，以及为什么首字母是**参数**
 *
 * 汉字的首字母要靠 Android 的 ICU（`android.icu.text.Transliterator`）拿，
 * 而这一层不允许出现 `android.*`（它要能在纯 JVM 上跑单测）。所以首字母的取法由调用方
 * 以函数参数传进来（[group] 的 `firstLetter`），这里只负责**分组、排序、字母槽位 ↔ 行下标
 * 的换算** —— 恰好就是容易写错、又最值得单测的那部分。
 *
 * 取法本身有两份实现是刻意的：hook 侧的在屏索引条（`AppIndex.firstLetter`）跑在 SystemUI
 * 进程里，这里的跑在设置界面进程里，两边不能共用代码。但**分组的语义必须一致**，
 * 所以这一份是共用的。
 */
object LetterIndex {

    /** 非字母开头的项归这一组。与 `AppIndex.ALPHABET` 的末位同义。 */
    const val OTHER = "#"

    /** 索引条上的槽位，顺序即显示顺序。 */
    val ALPHABET: List<String> = buildList {
        for (c in 'A'..'Z') add(c.toString())
        add(OTHER)
    }

    private val SLOTS: Set<String> = ALPHABET.toHashSet()

    /** 一个字母分组。`items` 保持传进来的相对顺序（排序是调用方的事）。 */
    class Group<T>(val letter: String, val items: List<T>)

    /**
     * 把提取出来的首字母规范化成槽位之一。
     *
     * 提取器可能返回多字符（ICU 对某些字给出 `ZH`、`CH` 这样的双字母）、空串或小写，
     * 这些都归 [OTHER] —— 界面上出现一个不在索引条里的分组标题，就是一个跳不过去的死区。
     */
    fun slotOf(raw: String): String {
        if (raw.length != 1) return OTHER
        val c = raw[0].uppercaseChar()
        return if (c in 'A'..'Z') c.toString() else OTHER
    }

    /**
     * 按首字母分组，**只返回非空的分组**，顺序固定为 [ALPHABET]。
     *
     * 只返回非空的：索引条是按这个结果画的，出现一个点不到东西的字母，用户会以为
     * 「这个字母下没应用」——那是真的没应用，但条上不该有那一格。
     *
     * @param labelOf 取用于判首字母的显示名（应用名）
     * @param firstLetter 显示名 → 首字母。**性能取决于它**：每个项调一次，
     *                    调用方必须自己缓存（ICU 转一次是毫秒级，不能每帧重来）
     */
    fun <T> group(
        items: List<T>,
        labelOf: (T) -> String,
        firstLetter: (String) -> String,
    ): List<Group<T>> {
        if (items.isEmpty()) return emptyList()
        val buckets = HashMap<String, MutableList<T>>(32)
        for (item in items) {
            val slot = slotOf(firstLetter(labelOf(item)))
            buckets.getOrPut(slot) { ArrayList(8) }.add(item)
        }
        return ALPHABET.mapNotNull { letter -> buckets[letter]?.let { Group(letter, it) } }
    }

    /**
     * 扁平行列表的布局：每组一行标题 + 它的项。
     *
     * 索引条跳转靠的是「这一组的标题在第几行」，而那个下标**包含前面所有组的标题行**。
     * 少了这个换算，跳转会越往后偏得越多（前面每多一个分组就多偏一行），
     * 而这种错误在十几项时几乎看不出来。
     */
    class Rows<T>(val groups: List<Group<T>>) {
        /** 第 i 组的**标题行**下标；组内第一项是它 +1。 */
        val headerAt: IntArray = IntArray(groups.size).also { out ->
            var at = 0
            for (i in groups.indices) {
                out[i] = at
                at += 1 + groups[i].items.size
            }
        }

        /** 总行数（含标题行）。LazyColumn 的 item 数就是它。 */
        val count: Int = headerAt.lastOrNull()?.let { it + 1 + groups.last().items.size } ?: 0

        /** 字母 → 行下标。字母不在列表里返回 null（调用方自己决定怎么办）。 */
        fun rowOf(letter: String): Int? {
            val i = groups.indexOfFirst { it.letter == letter }
            return if (i < 0) null else headerAt[i]
        }
    }

    fun <T> rows(groups: List<Group<T>>): Rows<T> = Rows(groups)

    /**
     * 手指在索引条上的纵向位置 → 第几个槽位。
     *
     * 「线性分格 + 两端夹紧」：每格等高，索引 = 偏移量整除格高，O(1)、不依赖任何测量结果。
     * 与 hook 侧 `AppIndex.letterAt` 是同一个算法（那边照搬的是 Appindex 的
     * `calculateSelectedLetter`），这里再写一遍是因为两边跑在不同进程、不共用代码 ——
     * 但**手感必须一致**。
     */
    fun slotAt(y: Float, top: Float, itemHeight: Float, count: Int): Int {
        if (count <= 0 || itemHeight <= 0f) return 0
        return ((y - top) / itemHeight).toInt().coerceIn(0, count - 1)
    }
}
