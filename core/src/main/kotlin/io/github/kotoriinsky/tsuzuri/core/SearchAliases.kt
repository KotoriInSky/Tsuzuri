package io.github.kotoriinsky.tsuzuri.core

/**
 * 搜索别名：把「用户嘴里的说法」翻成「界面上写的词」。
 *
 * ## 为什么必须有
 *
 * 界面写「振动」，用户打「震动」；界面写「不透明度」，用户打「透明」。
 * 没有这张表就是**搜不到**，而用户只会认为搜索坏了、或者这个功能不存在 ——
 * 他不会想到是自己用词和界面不一致。这是 2026-09-17 真踩到的：
 * 搜「震动」零结果，因为数据里写的是「振动」。
 *
 * ## 为什么放在 core
 *
 * 这张表必须能被单测钉住。它的失效方式是**静默**的：改了某处文案却忘了同步
 * 这张表，表现只是「某个词突然搜不到了」，没有任何报错，也没人会回来报 bug。
 * 放在 ui 层（尤其是 Composable 里）就没法直接测。
 *
 * ## 匹配规则
 *
 * 先看原词，再看别名展开 —— 也就是说**原词永远有效**，别名只是补充。
 * 这样即便别名表漏了，用户照着界面上的词原样输入也能搜到。
 */
object SearchAliases {

    /**
     * 别名表。值可以多个：一个口语词可能对应界面上好几种写法。
     *
     * 维护约定：改任何参数/分组的文案时，如果那个词有常见口语说法，来这里补一行。
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "震动" to listOf("振动"),
        "振感" to listOf("振动"),
        "力度" to listOf("振动"),
        "快慢" to listOf("展开速度", "速度"),
        "速度" to listOf("展开速度", "速度"),
        "快一点" to listOf("展开速度"),
        "透明" to listOf("不透明度"),
        "淡" to listOf("不透明度"),
        "浓" to listOf("不透明度"),
        "白边" to listOf("描边"),
        "描边" to listOf("描边"),
        "小窗" to listOf("窗口"),
        "居中" to listOf("水平位置"),
        "高低" to listOf("垂直位置"),
        "上下" to listOf("垂直位置"),
        "图标包" to listOf("图标"),
        "字体" to listOf("字号"),
        "大小" to listOf("大小"),
    )

    /** 查询词展开成「原词 + 所有别名」。空白查询返回空表。 */
    fun expand(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return (listOf(q) + (ALIASES[q].orEmpty())).distinct()
    }

    /**
     * [text] 是否命中 [query]。
     *
     * 空白查询视为命中全部（调用方一般先自己判空，这里保持一致是为了
     * 不出现「空查询时行为不一致」这种说不清的边界）。
     *
     * 命中规则（按顺序短路）：
     *  1. **原词包含**：`text` 里直接出现该词 —— 最准、零误伤；
     *  2. **别名包含**：用户打口语词（「震动」）命中界面词（「振动」）；
     *  3. **子序列模糊**：查询字符在 `text` 中**按顺序**出现但可不连续
     *     （「标大」命中「图标大小」）。覆盖「记不全、打漏字」的场景，
     *     又不像编辑距离那样把无关词误伤成命中。
     */
    fun matches(text: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        for (term in expand(q)) {
            if (text.contains(term, ignoreCase = true)) return true
            if (isSubsequence(text, term)) return true
        }
        return false
    }

    /**
     * 顺序子序列匹配：`term` 的每个字符在 `text` 中**依次**出现（可非连续）。
     *
     * 与 `contains` 的区别：搜「标大」能命中「图标大小」，但搜「小大」命不中
     * （顺序错了）。用于模糊搜索，是「用户打漏字」时的兜底。
     *
     * 不做编辑距离容错：那会把大量无关词误伤成命中，对设置项的搜索反而更吵。
     */
    private fun isSubsequence(text: String, term: String): Boolean {
        if (term.isEmpty()) return false
        val s = text.lowercase()
        val t = term.lowercase()
        var i = 0
        for (c in s) {
            if (c == t[i]) {
                i++
                if (i == t.length) return true
            }
        }
        return false
    }
}
