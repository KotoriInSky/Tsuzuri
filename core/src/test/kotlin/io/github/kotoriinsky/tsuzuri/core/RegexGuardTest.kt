package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 正则防护的钉子：超时失效 = 一条坏规则卡死 system_server 的剪贴板观察线程；
 * 风险检查误报 = 用户正常写的规则保存不了。
 */
class RegexGuardTest {

    /**
     * 回溯失控样例。教科书上的 `(a+)+b` 在 JDK 9+ 上被记忆化优化掉了（测试机跑 2ms），
     * 但设备上 ART 的 java.util.regex 底层是 ICU，没有这层优化 —— 所以测超时要用 JDK 上
     * 也失控的写法：六个相邻 `.*` 是多项式级回溯，200 字实测 > 3s。
     */
    private val evilPattern = ".*.*.*.*.*.*="
    private val evilText = "a".repeat(200)

    @Test
    fun `回溯失控的正则在时限内被打断`() {
        val re = assertNotNull(RegexGuard.compile(evilPattern))
        val start = System.nanoTime()
        assertFailsWith<RegexGuard.Timeout> { RegexGuard.match(re, evilText, anchored = false, timeoutMs = 50) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1000, "打断用了 ${ms}ms")
    }

    @Test
    fun `正常规则不受超时影响 分组取值正确`() {
        val re = assertNotNull(RegexGuard.compile("""BV([1-9A-HJ-NP-Za-km-z]{10})"""))
        val m = assertNotNull(RegexGuard.match(re, "看看 BV1GJ411x7h7 吧", anchored = false, timeoutMs = 50))
        assertEquals("1GJ411x7h7", m.groupValues[1])
        assertNull(RegexGuard.match(re, "看看 BV1GJ411x7h7 吧", anchored = true, timeoutMs = 50))
    }

    @Test
    fun `编译结果被缓存 编不过返回 null`() {
        assertSame(RegexGuard.compile("abc+"), RegexGuard.compile("abc+"))
        assertNull(RegexGuard.compile("(unclosed"))
    }

    @Test
    fun `超时的规则跳过 后面的规则照常命中 并回调`() {
        val evil = PasscodeCustomRules.Rule("evil", "坏规则", evilPattern, false, "https://a.example/")
        val good = PasscodeCustomRules.Rule("good", "好规则", "a{3}", false, "https://b.example/")
        val timedOut = ArrayList<String>()
        val m = PasscodeCustomRules.matchFirst(evilText, listOf(evil, good), onTimeout = { timedOut.add(it.id) })
        assertEquals("https://b.example/", m?.target)
        assertEquals(listOf("evil"), timedOut)
    }

    @Test
    fun `风险写法 分组内无界量词外面又套无界量词`() {
        for (p in listOf("(a+)+", "(a*)*", """(\w+\s?)+$""", "(?:x*){2,}", "((a+)b)+", "(a{1,})+", ".*.*.*=", "a.+b.*c.+d")) {
            assertTrue(RegexGuard.isRisky(p), p)
        }
    }

    @Test
    fun `正常写法不误报`() {
        for (p in listOf(
            """BV[1-9A-HJ-NP-Za-km-z]{10}""",
            """xhslink\.com/([A-Za-z0-9]+)""",
            "(?:ab)+",
            "(a{2,5})+",
            "(a+)b",
            """\(a+\)+""",
            "[(a+)]+",
            """https?://(?:www\.)?example\.com/(\d+)""",
            """【(.+)】.*https?://\S+""",
            """\.\*\.\*\.\*""",
        )) {
            assertFalse(RegexGuard.isRisky(p), p)
        }
    }
}
