package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自定义规则的钉子：编解码往返丢一个字段 = 用户导出再导入后行为变了；
 * 匹配顺序错 = 内置规则抢在用户规则前面（用户显式配置永远优先）；
 * 正则异常逃逸 = 炸掉剪贴板观察线程。
 */
class PasscodeCustomRulesTest {

    private val sample = PasscodeCustomRules.Rule(
        id = "xhs_user", label = "小红书笔记", anchored = false,
        regex = """xhslink\.com/([A-Za-z0-9]+)""", target = "https://xhslink.com/$1",
        platformName = "小红书", platformPkg = "com.xingin.xhs", accent = "#FF2442",
        coreGroup = 1,
        category = PasscodeIsland.ContentCategory.IMAGE_TEXT,
    )

    // ── 编解码 ───────────────────────────────────────────────────────

    @Test
    fun `编解码往返不丢字段`() {
        val raw = PasscodeCustomRules.encode(listOf(sample))!!
        val out = PasscodeCustomRules.decode(raw)
        assertEquals(listOf(sample), out)
    }

    @Test
    fun `分类和启用状态编解码并参与匹配`() {
        val disabled = sample.copy(
            id = "disabled",
            category = PasscodeIsland.ContentCategory.MUSIC,
            enabled = false,
        )
        val raw = PasscodeCustomRules.encode(listOf(disabled))!!
        assertEquals(listOf(disabled), PasscodeCustomRules.decode(raw))
        assertNull(PasscodeCustomRules.matchFirst("xhslink.com/AbC123", listOf(disabled)))
    }

    @Test
    fun `旧九列和十列数据默认启用`() {
        val legacyNine = "xhs_user\u001F小红书笔记\u001F0\u001Fxhslink\\.com/([A-Za-z0-9]+)\u001Fhttps://xhslink.com/\$1\u001F小红书\u001Fcom.xingin.xhs\u001F#FF2442\u001F1"
        val legacyTen = legacyNine + "\u001Fimage_text"
        assertTrue(PasscodeCustomRules.decode(legacyNine).single().enabled)
        assertTrue(PasscodeCustomRules.decode(legacyTen).single().enabled)
    }

    @Test
    fun `空列表编解码`() {
        assertEquals("", PasscodeCustomRules.encode(emptyList()))
        assertTrue(PasscodeCustomRules.decode("").isEmpty())
        assertTrue(PasscodeCustomRules.decode(null).isEmpty())
    }

    @Test
    fun `坏行跳过不连坐好行`() {
        val raw = PasscodeCustomRules.encode(listOf(sample))!!
        val bad = "只有三列\u001F真的\u001F不够\n" + raw.lines()[1] + "\n" +
            "1\u001F坏正则\u001F0\u001F([未闭合\u001Fx\u001F\u001F\u001F\u001F\u001F"
        val out = PasscodeCustomRules.decode(bad)
        assertEquals(1, out.size)
        assertEquals("xhs_user", out[0].id)
    }

    @Test
    fun `超长或超量编码返回null而不是截断`() {
        // 丢规则不能没有声音：调用方拿到 null 必须报错
        val big = PasscodeCustomRules.Rule(
            id = "big", label = "x", anchored = false,
            regex = "a".repeat(70_000), target = "taobao://",
        )
        assertNull(PasscodeCustomRules.encode(listOf(big)))
        val many = (1..PasscodeCustomRules.MAX_RULES + 1).map {
            PasscodeCustomRules.Rule(id = "r$it", label = "x", anchored = false, regex = "a", target = "t")
        }
        assertNull(PasscodeCustomRules.encode(many))
    }

    @Test
    fun `字段含分隔符编码整体拒绝`() {
        // 分隔符注入会切错列 —— encode 必须整体拒绝，不能让乱码正则进匹配热路径
        val hostile = PasscodeCustomRules.Rule(
            id = "h", label = "x", anchored = false,
            regex = "a\u001Fb", target = "t",
        )
        assertNull(PasscodeCustomRules.encode(listOf(hostile)))
        val hostileLabel = sample.copy(label = "a\nb")
        assertNull(PasscodeCustomRules.encode(listOf(hostileLabel)))
        // decode 端对切列产物也跳过。合法列数是 9（旧）/ 10（+category）/ 11（+enabled），
        // 所以「多出来的列」要写到 12 列才算注入 —— 这里原本写的是 10 列，
        // 在 category 列（10 列）落地之后那已经是一条**合法**的长版本记录了。
        assertTrue(
            PasscodeCustomRules.decode(
                "h\u001Fx\u001F0\u001Fa\u001Fb\u001Ft\u001F\u001F\u001F\u001Fextra\u001Fextra2\u001Fextra3",
            ).isEmpty(),
        )
    }

    // ── 匹配 ─────────────────────────────────────────────────────────

    @Test
    fun `全文规则命中并展开组引用`() {
        val m = PasscodeCustomRules.matchFirst(
            "快看 https://xhslink.com/AbC123 这个",
            listOf(sample),
        )
        assertNotNull(m)
        assertEquals("custom:xhs_user", m.ruleId)
        assertEquals("https://xhslink.com/AbC123", m.target)
        assertEquals("AbC123", m.core)
        assertEquals("小红书", m.platform?.name)
        assertEquals("com.xingin.xhs", m.platform?.pkg)
        assertEquals("#FF2442", m.platform?.accent)
    }

    @Test
    fun `锚定规则整段才命中`() {
        val r = sample.copy(anchored = true, regex = """BV[0-9a-zA-Z]{10}""", coreGroup = null)
        assertNotNull(PasscodeCustomRules.matchFirst("BV1GJ411x7h7", listOf(r)))
        assertNull(PasscodeCustomRules.matchFirst("看看 BV1GJ411x7h7", listOf(r)))
    }

    @Test
    fun `无组引用模板原样输出`() {
        val r = sample.copy(target = "taobao://")
        val m = PasscodeCustomRules.matchFirst("xhslink.com/AbC123", listOf(r))
        assertEquals("taobao://", m?.target)
    }

    @Test
    fun `平台元数据全空回落label与兜底蓝`() {
        val r = PasscodeCustomRules.Rule(
            id = "n", label = "我的站点", anchored = false,
            regex = "example\\d+", target = "https://example.com",
        )
        val m = PasscodeCustomRules.matchFirst("example42", listOf(r))!!
        assertEquals("我的站点", m.platform?.name)
        assertNull(m.platform?.pkg)
        assertEquals("#5B7CFF", m.platform?.accent)
        assertNull(m.core)
    }

    @Test
    fun `坏正则与运行期异常按没命中处理`() {
        val bad = listOf(
            sample.copy(id = "bad", regex = "([未闭合"),
            sample.copy(id = "ok"),
        )
        val m = PasscodeCustomRules.matchFirst("xhslink.com/AbC123", bad)
        assertNotNull(m)
        assertEquals("custom:ok", m.ruleId)
    }

    @Test
    fun `列表顺序即优先级`() {
        val first = sample.copy(id = "first", label = "一", regex = "xhslink\\.com/\\w+", target = "a://1")
        val second = sample.copy(id = "second", label = "二", regex = "xhslink", target = "a://2")
        assertEquals("a://1", PasscodeCustomRules.matchFirst("xhslink.com/AbC", listOf(first, second))?.target)
        assertEquals("a://2", PasscodeCustomRules.matchFirst("xhslink.com/AbC", listOf(second, first))?.target)
    }

    // ── 与内置引擎的关系 ─────────────────────────────────────────────

    @Test
    fun `自定义规则可以覆盖内置行为`() {
        // 用户想把 B 站口令跳到自己的解析站：自定义在前 = 用户意图优先
        val custom = PasscodeCustomRules.Rule(
            id = "bili_mine", label = "B站", anchored = true,
            regex = """BV[0-9a-zA-Z]{10}""", target = "https://my.example.com/$0",
            coreGroup = null,
        )
        val text = "BV1GJ411x7h7"
        val viaCustom = PasscodeCustomRules.matchFirst(text, listOf(custom))
        assertNotNull(viaCustom)
        assertEquals("https://my.example.com/BV1GJ411x7h7", viaCustom.target)
        // 内置引擎对同文本也命中 —— 合并顺序由调用方（Watcher）保证自定义在前
        val builtin = PasscodeRules.match(text)
        assertNotNull(builtin)
        assertEquals("bili_bv", builtin.ruleId)
        assertFalse(viaCustom.target == builtin.target)
    }
}
