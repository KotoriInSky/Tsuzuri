package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内置规则 JSON的钉子。
 *
 * `passcode_golden.json` 是迁移**之前**的旧代码（Kotlin 表）对 2699 条输入跑出的标准答案：
 * 输入 = core 单测里的全部字符串字面量 + 每个域名规则合成的链接（含 www / m / 子域与路径前缀）。
 * 迁移后的结果必须逐条一致；有意的差异列在 [INTENDED] 里，并写明原因。
 */
class BuiltinRulesTest {

    private fun resource(name: String) =
        assertNotNull(javaClass.getResource("/io/github/kotoriinsky/tsuzuri/core/$name"), name).readText()

    @Test
    fun `内置规则随包加载 整份通过 CI 级校验 样例全过`() {
        assertTrue(BuiltinRules.loaded, "passcode_rules.json 没打进 core 的资源或解析失败")
        val doc = assertNotNull(RuleSet.parse(resource("passcode_rules.json")).doc)
        val checked = RuleSetCheck.check(doc, RuleSetCheck.Mode.CI)
        assertTrue(checked.ok, checked.issues.joinToString("\n"))
        assertEquals(emptyList(), RuleSetSamples.run(checked).map { it.toString() })
    }

    @Test
    fun `口令规则的正则与 ExpressIntl 同源`() {
        val p = assertNotNull(BuiltinRules.checked).passcodes.associateBy { it.rule.id }
        assertEquals(ExpressIntl.PREFIXED.pattern, p.getValue("express_intl").regex.pattern)
        assertEquals(ExpressIntl.NUMERIC.pattern, p.getValue("express_intl_numeric").regex.pattern)
    }

    private fun ser(m: PasscodeRules.Match?): Any? = m?.let {
        listOf(
            it.ruleId, it.label, it.target,
            it.platform?.let { p -> "${p.name}|${p.pkg}|${p.accent}|${p.category.wireName}" },
            it.core, it.alts, it.category.wireName,
        )
    }

    private fun golden(o: Any?): Any? = (o as? Map<*, *>)?.let {
        listOf(it["rule"], it["label"], it["target"], it["platform"], it["core"], it["alts"], it["category"])
    }

    /**
     * 有意的差异：旧表是「对整条链接跑正则、取最左命中」，新表是「主机名后缀 + 路径前缀」。
     * 只在下面这些构造出来的边角链接上不同，新行为都更合理。
     */
    private val INTENDED = mapOf(
        // 旧正则 amazon\.(?:com|…|com\.mx|com\.br) 永远先命中 com，墨西哥站、巴西站反而认不出
        //（合成链接里 amazon.com.mx / amazon.com.br 的各种子域另见 [isAmazonLatam]）
        "https://www.amazon.com.mx/dp/B000000000" to "旧表的交替顺序 bug",
        // 旧表只要求链接里某处出现「weixin.qq.com/sph」，嵌在查询参数里也算
        "https://weixin.qq.com/x?u=weixin.qq.com/sph" to "路径前缀只看路径",
        // 旧正则 (?:open\.)?spotify… 最左命中 open.spotify.com，xopen 子域对不上后缀
        "https://xopen.spotify.com/track/1" to "spotify.com 后缀本来就该认",
    )

    private fun isAmazonLatam(url: String) =
        runCatching { java.net.URI(url).host }.getOrNull()?.let { it.endsWith("amazon.com.mx") || it.endsWith("amazon.com.br") } == true

    @Test
    fun `迁移前后结果逐条一致`() {
        val g = MiniJson.parse(resource("passcode_golden.json")) as Map<*, *>
        val cases = g["cases"] as List<*>
        assertTrue(cases.size > 2500)
        val diffs = ArrayList<String>()
        val strict = PasscodeRules.Options(genericUrl = false, numericTracking = true)
        for (c in cases) {
            c as Map<*, *>
            val input = c["in"] as String
            if (input in INTENDED || isAmazonLatam(input)) continue
            for ((key, opts) in listOf("default" to PasscodeRules.Options(), "strict" to strict)) {
                val m = PasscodeRules.match(input, opts)
                val old = golden(c[key]) as List<*>?
                val now = ser(m) as List<*>?
                // 快递的跳转链 2026-10-22 有意改了（负一屏 → 菜鸟 App → 支付宝小程序 → 快递100）：
                // 其余字段仍逐条比对，跳转链按新规则核对
                val ok = if (old != null && now != null && (old[0] as String).startsWith("express")) {
                    old.filterIndexed { i, _ -> i != 2 && i != 5 } == now.filterIndexed { i, _ -> i != 2 && i != 5 } &&
                        listOf(m!!.target) + m.alts == PasscodeRules.expressTargetChain(m.core!!)
                } else if (old != null && now != null &&
                    (m?.platform == PasscodeRules.P_TIEBA || m?.platform == PasscodeRules.P_WEIBO)) {
                    // 贴吧帖子 2026-10-22 起多一个官方贴吧直达备选（官方贴吧不认网页地址）：备选按新规则核对
                    old.filterIndexed { i, _ -> i != 5 } == now.filterIndexed { i, _ -> i != 5 } &&
                        m.alts == PasscodeRules.articleAppTargets(m.target, m.platform.pkg)
                } else {
                    old == now
                }
                if (!ok) diffs += "$key「${input.take(80)}」\n  旧 $old\n  新 $now"
            }
        }
        assertTrue(diffs.isEmpty(), "${diffs.size} 处不一致：\n" + diffs.take(30).joinToString("\n"))
    }

    @Test
    fun `有意的差异按预期变化`() {
        assertEquals(PasscodeRules.P_AMAZON, PasscodeRules.match("https://www.amazon.com.mx/dp/B000000000")?.platform)
        assertEquals(PasscodeRules.P_AMAZON, PasscodeRules.match("https://amazon.com.br/x")?.platform)
        assertEquals(PasscodeRules.P_GENERIC, PasscodeRules.match("https://weixin.qq.com/x?u=weixin.qq.com/sph")?.platform)
        assertEquals(PasscodeRules.P_SPOTIFY, PasscodeRules.match("https://xopen.spotify.com/track/1")?.platform)
    }

    @Test
    fun `平台列表与分享平台集合不变`() {
        val g = MiniJson.parse(resource("passcode_golden.json")) as Map<*, *>
        fun plats(l: Collection<PasscodeRules.Platform>) =
            l.map { "${it.name}|${it.pkg}|${it.accent}|${it.category.wireName}" }.sorted()
        assertEquals(g["supported"], plats(PasscodeRules.supportedPlatforms()))
        assertEquals(g["command"], plats(PasscodeRules.commandPlatforms()))
        assertEquals(g["url"], plats(PasscodeRules.urlPlatforms()))
        assertEquals(g["share"], plats(PasscodeRules.SHARE_PLATFORMS))
    }
}
