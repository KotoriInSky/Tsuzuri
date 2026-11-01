package io.github.kotoriinsky.tsuzuri.core

/**
 * 用户 / 外部来源正则的三道防护：**编译缓存**、**匹配超时**、**风险写法检查**。
 *
 * 为什么需要：自定义规则在 system_server 的剪贴板观察线程上匹配（`PasscodeIslandWatcher`），
 * 不会卡住 ClipboardService 本身，但一条回溯失控的正则（典型 `(a+)+b`）会把这个线程卡死，
 * 之后所有复制都排队、不再识别 —— 而且没有任何报错。将来订阅规则来自外部，这是必需品。
 *
 * 超时的做法：`java.util.regex` 没有超时参数，但它逐字符调 `CharSequence.charAt`，
 * 于是把输入包一层，在 `charAt` 里看截止时间，超了就抛 [Timeout] 打断匹配。
 */
object RegexGuard {

    /** 匹配超时抛出的信号。不带栈（热路径上可能频繁出现，填栈很贵）。 */
    class Timeout : RuntimeException(null, null, false, false)

    private const val CACHE_MAX = 128

    private val cache = object : LinkedHashMap<String, Regex>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean =
            size > CACHE_MAX
    }

    /** 取编译好的正则；编不过返回 null（不缓存失败，规则改好后立即可用）。 */
    fun compile(pattern: String): Regex? {
        synchronized(cache) { cache[pattern]?.let { return it } }
        val re = try {
            Regex(pattern)
        } catch (_: Throwable) {
            return null
        }
        synchronized(cache) { cache[pattern] = re }
        return re
    }

    /**
     * 带超时的匹配。命中返回结果；没命中返回 null；超时抛 [Timeout]（调用方决定要不要记日志）。
     * 返回的 [MatchResult] 的分组取自原文，可以放心在超时窗口之外使用。
     */
    fun match(re: Regex, text: String, anchored: Boolean, timeoutMs: Long): MatchResult? {
        val input = DeadlineChars(text, System.nanoTime() + timeoutMs * 1_000_000L)
        return if (anchored) re.matchEntire(input) else re.find(input)
    }

    /**
     * 带超时的逐个命中（惰性）：整个序列共用一个截止时间，迭代中超时抛 [Timeout]。
     * 给「形态命中后还要二次判据、不通过就试下一处」的规则用。
     */
    fun findAll(re: Regex, text: String, timeoutMs: Long): Sequence<MatchResult> =
        re.findAll(DeadlineChars(text, System.nanoTime() + timeoutMs * 1_000_000L))

    /**
     * 明显会回溯失控的两种写法：
     *  - **嵌套无界量词**：分组里有 `+` `*` `{n,}`，分组本身又跟着无界量词，如 `(a+)+`、`(\w+\s?)*`；
     *  - **三个及以上 `.*` / `.+`**：相邻的任意字符通配互相抢字符，回溯是多项式级（6 个就能卡几秒）。
     *
     * 只是启发式：`(a|aa)+` 这类靠交替重叠的写法查不出来，兜底靠 [match] 的超时。
     * 编不过的正则这里不判（返回 false），由 [compile] 负责。
     */
    fun isRisky(pattern: String): Boolean {
        // 栈里每个元素 = 该层分组内是否出现过无界量词
        val stack = ArrayDeque<Boolean>()
        var inner = false
        var i = 0
        var inClass = false
        var wildcards = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++ // 跳过被转义的字符
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '.' && unboundedQuantifierAt(pattern, i + 1) -> if (++wildcards >= 3) return true
                c == '(' -> {
                    stack.addLast(inner)
                    inner = false
                }
                c == ')' -> {
                    val groupHadUnbounded = inner
                    inner = stack.removeLastOrNull() ?: false
                    val q = unboundedQuantifierAt(pattern, i + 1)
                    if (q && groupHadUnbounded) return true
                    // 分组内的无界量词让外层也算「含无界量词」：((a+)b)+ 同样危险
                    if (groupHadUnbounded || q) inner = true
                }
                unboundedQuantifierAt(pattern, i) -> inner = true
            }
            i++
        }
        return false
    }

    /** [at] 位置是不是无界量词：`+`、`*`、`{n,}`。 */
    private fun unboundedQuantifierAt(p: String, at: Int): Boolean {
        if (at >= p.length) return false
        return when (p[at]) {
            '+', '*' -> true
            '{' -> {
                val close = p.indexOf('}', at)
                close > at && p.substring(at + 1, close).let { body ->
                    body.endsWith(",") && body.dropLast(1).all(Char::isDigit) && body.length > 1
                }
            }
            else -> false
        }
    }

    /** 每次 `charAt` 都看一眼截止时间；子序列返回普通字符串（分组取值不再受限）。 */
    private class DeadlineChars(private val s: String, private val deadline: Long) : CharSequence {
        private var calls = 0
        override val length: Int get() = s.length
        override fun get(index: Int): Char {
            // nanoTime 不算便宜，每 64 次看一次；回溯失控时调用次数是指数级，照样及时打断
            if (++calls and 63 == 0 && System.nanoTime() > deadline) throw Timeout()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.substring(startIndex, endIndex)
        override fun toString(): String = s
    }
}
