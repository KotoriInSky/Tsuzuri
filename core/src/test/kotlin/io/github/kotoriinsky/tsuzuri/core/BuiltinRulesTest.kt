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
                } else if (old != null && now != null && m?.platform == PasscodeRules.P_BAIDUPAN && old[2] == "baiduyun://") {
                    // 百度网盘 2026-11-26 起升级为 bdnetdisk://n/action.SHARE_LINK?surl=... 深链直达（baiduyun:// 退居备选首项）
                    old.filterIndexed { i, _ -> i != 2 && i != 5 } == now.filterIndexed { i, _ -> i != 2 && i != 5 } &&
                        m.target.startsWith("bdnetdisk://n/action.SHARE_LINK?surl=") &&
                        m.alts.firstOrNull() == "baiduyun://"
                } else if (old != null && now != null && m?.platform == PasscodeRules.P_ALIYUNPAN && old[4] == null && m.target.contains("/s/")) {
                    // 阿里云盘 2026-11-26 起支持从 /s/<分享码> 提取核心内容
                    old.filterIndexed { i, _ -> i != 4 } == now.filterIndexed { i, _ -> i != 4 } && !m.core.isNullOrEmpty()
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
        // 2026-11-26 补齐全量网盘（123/移动/UC/蓝奏/天翼/115/PikPak/微云/城通/MEGA/Google Drive/OneDrive）与下载协议
        val addedCloudPlatforms = listOf(
            "115网盘|com.ylmf.androidclient|#2378F4|image_text",
            "123云盘|com.mfcloudcalculate.networkdisk|#3B7BFF|image_text",
            "Google Drive|com.google.android.apps.docs|#0F9D58|image_text",
            "MEGA|mega.privacy.android.app|#D9272E|image_text",
            "OneDrive|com.microsoft.skydrive|#0078D4|image_text",
            "PikPak|com.pikcloud.pikpak|#306EFF|image_text",
            "UC网盘|com.UCMobile|#FF7F00|image_text",
            "城通网盘|null|#1677FF|image_text",
            "天翼云盘|com.cn21.ecloud|#1E88E5|image_text",
            "微云|com.qq.qcloud|#00A4FF|image_text",
            "移动云盘|com.chinamobile.mcloud|#0085D0|image_text",
            "蓝奏云|null|#2F80ED|image_text",
        )
        val expectedSupported = ((g["supported"] as List<*>) + addedCloudPlatforms).sortedBy { it.toString() }
        assertEquals(expectedSupported, plats(PasscodeRules.supportedPlatforms()))
        // 2026-11-26 新增抖音、快手离线裸口令规则与迅雷磁力/电驴/迅雷下载链规则
        val expectedCommand = ((g["command"] as List<*>) + listOf(
            "抖音|com.ss.android.ugc.aweme|#FE2C55|short_video",
            "快手|com.smile.gifmaker|#FF5001|short_video",
            "迅雷|com.xunlei.downloadprovider|#4285F4|image_text",
        )).sortedBy { it.toString() }
        assertEquals(expectedCommand, plats(PasscodeRules.commandPlatforms()))
        val expectedUrl = ((g["url"] as List<*>) + addedCloudPlatforms).sortedBy { it.toString() }
        assertEquals(expectedUrl, plats(PasscodeRules.urlPlatforms()))
        assertEquals(g["share"], plats(PasscodeRules.SHARE_PLATFORMS))
    }
}
