package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.RuleSet.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规则格式 v2：解析、校验、匹配、样例执行。
 * 测试用的规则取自内置规则（bili_bv、nico、express_prefixed …），顺带对照 `PasscodeRules` 的结果，
 * 保证第 3 步迁移时同一份规则写成 JSON 后语义不变。
 */
class RuleSetTest {

    private val sample = """
        {
          "format": 2, "version": 3,
          "platforms": [
            { "id": "bili", "name": "B站", "pkg": "tv.danmaku.bili", "accent": "#FB7299", "category": "long_video", "share": true },
            { "id": "nico", "name": "niconico", "pkg": "jp.nicovideo.android", "accent": "#252525", "category": "long_video" },
            { "id": "express", "name": "快递", "accent": "#FF6A00", "category": "express" },
            { "id": "channels", "name": "微信", "pkg": "com.tencent.mm", "accent": "#07C160", "category": "image_text" }
          ],
          "hosts": [
            { "id": "host.bili", "label": "B 站链接", "platform": "bili", "hosts": ["b23.tv", "bilibili.com"], "meta": "auto",
              "samples": { "hit": ["https://www.bilibili.com/video/BV1GJ411x7h7"], "miss": ["https://notbilibili.com/x"] } },
            { "id": "host.channels", "label": "视频号链接", "platform": "channels", "hosts": ["weixin.qq.com"], "pathPrefix": "/sph",
              "samples": { "hit": ["https://weixin.qq.com/sph/AbCd"], "miss": ["https://weixin.qq.com/other"] } }
          ],
          "passcodes": [
            { "id": "bili_bv", "label": "B 站视频", "regex": "\\b(?:BV|bv)([0-9a-zA-Z]{10})\\b", "platform": "bili",
              "core": "BV{1}", "target": "https://www.bilibili.com/video/BV{1}",
              "samples": { "hit": [{ "text": "看看 bv1GJ411x7h7 这个", "target": "https://www.bilibili.com/video/BV1GJ411x7h7", "core": "BV1GJ411x7h7" }],
                           "miss": ["BV12345"] } },
            { "id": "nico", "label": "niconico", "regex": "\\b(sm|nm|SM|NM|so)(\\d+)\\b", "platform": "nico",
              "core": "{1|lower}{2}", "target": "https://www.nicovideo.jp/watch/{1|lower}{2}",
              "samples": { "hit": [{ "text": "SM39366587", "target": "https://www.nicovideo.jp/watch/sm39366587" }], "miss": ["SO2"] } },
            { "id": "express_prefixed", "label": "快递", "regex": "\\b(SF\\d{12,15}|[A-Z]{2}\\d{9}[A-Z]{2})\\b", "platform": "express",
              "core": "{1}", "target": "@express", "alts": ["@express_cainiao", "@express_alipay", "@express_web"], "accept": "s10",
              "samples": { "hit": [{ "text": "单号 SF1382024591123", "core": "SF1382024591123" }], "miss": ["RA123456788CN"] } }
          ]
        }
    """.trimIndent()

    private fun load(json: String = sample, mode: RuleSetCheck.Mode = RuleSetCheck.Mode.CI): RuleSetCheck.Checked {
        val parsed = RuleSet.parse(json)
        val doc = assertNotNull(parsed.doc, parsed.issues.toString())
        assertTrue(parsed.issues.isEmpty(), parsed.issues.toString())
        return RuleSetCheck.check(doc, mode)
    }

    // ── 解析 ──

    @Test
    fun `示例文件解析与校验全过`() {
        val c = load()
        assertTrue(c.ok, c.issues.toString())
        assertEquals(3L, c.version)
        assertEquals(2, c.hosts.size)
        assertEquals(3, c.passcodes.size)
        assertEquals(PasscodeIsland.ContentCategory.EXPRESS, c.platforms["express"]?.category)
    }

    @Test
    fun `顶层坏了整份拒绝`() {
        assertNull(RuleSet.parse("[]").doc)
        assertNull(RuleSet.parse("""{"format": 1, "version": 1}""").doc)
        assertNull(RuleSet.parse("""{"format": 2}""").doc)
        assertNull(RuleSet.parse("""{"format": 2, "version": 1.5}""").doc)
        assertNull(RuleSet.parse("not json").doc)
    }

    @Test
    fun `单条结构错只丢这一条`() {
        val p = RuleSet.parse(
            """{"format":2,"version":1,"passcodes":[
                {"id":"a","label":"A","regex":"x(\\d+)","target":"https://a.example/{1}"},
                {"id":"b","label":"B","regex":"y","target":42},
                "oops"
            ]}""",
        )
        val doc = assertNotNull(p.doc)
        assertEquals(listOf("a"), doc.passcodes.map { it.id })
        assertEquals(2, p.issues.size, p.issues.toString())
        assertTrue(p.issues.any { it.ruleId == "b" })
    }

    // ── 校验 ──

    private fun issuesOf(passcode: String, mode: RuleSetCheck.Mode = RuleSetCheck.Mode.LOAD): List<RuleSet.Issue> {
        val doc = assertNotNull(
            RuleSet.parse(
                """{"format":2,"version":1,
                   "platforms":[{"id":"p","name":"P","accent":"#112233","category":"web"}],
                   "passcodes":[$passcode]}""",
            ).doc,
        )
        return RuleSetCheck.check(doc, mode).issues
    }

    private fun assertError(passcode: String, fragment: String) {
        val issues = issuesOf(passcode)
        assertTrue(issues.any { it.severity == Severity.ERROR && fragment in it.message }, "期望含「$fragment」：$issues")
    }

    @Test
    fun `正则与模板的错误`() {
        assertError("""{"id":"r","label":"R","regex":"(unclosed","target":"https://x.example/"}""", "编译失败")
        assertError("""{"id":"r","label":"R","regex":"(a+)+b","target":"https://x.example/"}""", "回溯失控")
        assertError("""{"id":"r","label":"R","regex":"a(\\d)","target":"https://x.example/{2}"}""", "第 2 组")
        assertError("""{"id":"r","label":"R","regex":"a(\\d)","target":"https://x.example/{1|trim}"}""", "模板写错")
        assertError("""{"id":"r","label":"R","regex":"a","target":"@nope"}""", "@nope")
        assertError("""{"id":"r","label":"R","regex":"a","platform":"ghost","target":"https://x.example/"}""", "不存在的平台")
    }

    @Test
    fun `跳转 scheme 只认白名单`() {
        for (t in listOf("http://x.example/", "intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "{1}://x")) {
            assertError("""{"id":"r","label":"R","regex":"a(b)","target":"$t"}""", "scheme")
        }
        assertError("""{"id":"r","label":"R","regex":"a","target":"https://x.example/","alts":["http://y.example/"]}""", "alts[0]")
        assertTrue(issuesOf("""{"id":"r","label":"R","regex":"a","target":"taobao://"}""").isEmpty())
    }

    @Test
    fun `不认识的判据是错误 不认识的抓取器只是警告`() {
        assertError("""{"id":"r","label":"R","regex":"a","target":"https://x.example/","accept":"luhn"}""", "判据 luhn")
        val w = issuesOf("""{"id":"r","label":"R","regex":"a","target":"https://x.example/","meta":"bili2"}""")
        assertEquals(listOf(Severity.WARNING), w.map { it.severity }, w.toString())
    }

    @Test
    fun `CI 要求样例 加载时不要求`() {
        val rule = """{"id":"r","label":"R","regex":"a","target":"https://x.example/"}"""
        assertTrue(issuesOf(rule, RuleSetCheck.Mode.LOAD).isEmpty())
        assertTrue(issuesOf(rule, RuleSetCheck.Mode.CI).any { "样例" in it.message })
    }

    @Test
    fun `加载时有错的单条跳过 版本太新的单条跳过 其余照用`() {
        val doc = assertNotNull(
            RuleSet.parse(
                """{"format":2,"version":1,"passcodes":[
                    {"id":"good","label":"G","regex":"g(\\d+)","target":"https://g.example/{1}"},
                    {"id":"bad","label":"B","regex":"(a+)+","target":"https://b.example/"},
                    {"id":"future","label":"F","regex":"f","target":"https://f.example/","minAppVersion":999},
                    {"id":"good","label":"G2","regex":"h","target":"https://h.example/"}
                ]}""",
            ).doc,
        )
        val c = RuleSetCheck.check(doc, RuleSetCheck.Mode.LOAD, appVersion = 100)
        assertEquals(listOf("good"), c.passcodes.map { it.rule.id })
        assertTrue(c.issues.any { it.ruleId == "future" && it.severity == Severity.WARNING })
        assertTrue(c.issues.any { it.ruleId == "good" && "重复" in it.message })
    }

    @Test
    fun `订阅可以引用内置平台 也可以同 id 覆盖`() {
        val builtin = mapOf("bili" to RuleSet.Platform("bili", "B站", "tv.danmaku.bili", "#FB7299", PasscodeIsland.ContentCategory.LONG_VIDEO))
        val doc = assertNotNull(
            RuleSet.parse(
                """{"format":2,"version":1,
                   "platforms":[{"id":"bili","name":"哔哩哔哩","accent":"#00A1D6","category":"long_video"}],
                   "hosts":[{"id":"h","label":"L","platform":"bili","hosts":["bili.example"]}]}""",
            ).doc,
        )
        val c = RuleSetCheck.check(doc, RuleSetCheck.Mode.LOAD, knownPlatforms = builtin)
        assertTrue(c.ok, c.issues.toString())
        assertEquals("哔哩哔哩", c.platforms["bili"]?.name)
    }

    // ── 匹配 ──

    @Test
    fun `匹配结果与内置规则一致`() {
        val m = RuleSetMatcher(load())
        for (text in listOf("看看 bv1GJ411x7h7 这个", "SM39366587", "单号 SF1382024591123")) {
            val v2 = assertNotNull(m.match(text), text)
            val v1 = assertNotNull(PasscodeRules.match(text), text)
            assertEquals(v1.target, v2.target, text)
            assertEquals(v1.core, v2.core, text)
            assertEquals(v1.alts, v2.alts, text)
        }
    }

    @Test
    fun `链接独占 域名后缀与路径前缀`() {
        val m = RuleSetMatcher(load())
        val hit = assertNotNull(m.match("BV1GJ411x7h7 https://m.bilibili.com/video/BV1GJ411x7h7。"))
        assertEquals("host.bili", hit.ruleId)
        assertEquals("https://m.bilibili.com/video/BV1GJ411x7h7", hit.target)
        assertNull(m.match("https://weixin.qq.com/other"))
        assertNull(m.match("https://evilbilibili.com/x"))
    }

    @Test
    fun `判据不过就试同一段文本里的下一处`() {
        val m = RuleSetMatcher(load())
        // 第一个 S10 校验位错（应为 5），第二个对
        val hit = assertNotNull(m.match("RA123456788CN 或 RA123456785CN"))
        assertEquals("RA123456785CN", hit.core)
        assertNull(m.match("RA123456788CN"))
    }

    @Test
    fun `上下文门控的规则只在打开时参与 超时跳过并回调`() {
        val doc = assertNotNull(
            RuleSet.parse(
                """{"format":2,"version":1,"passcodes":[
                    {"id":"slow","label":"S","regex":".*.*=","target":"https://s.example/"},
                    {"id":"num","label":"N","regex":"(?<!\\d)(\\d{13})(?!\\d)","requiresContext":true,"target":"https://n.example/{1}"}
                ]}""",
            ).doc,
        )
        val m = RuleSetMatcher(RuleSetCheck.check(doc, RuleSetCheck.Mode.LOAD), timeoutMs = 20)
        val timedOut = ArrayList<String>()
        // 两个 .* 过得了风险检查，但对长文本逐位置试是立方级（JDK 对 (x|xx)+ 这类有优化，不能用）
        val text = "x".repeat(3000) + " 快递单号 1234567890123"
        assertNull(m.match(text, numericTracking = false, onTimeout = { timedOut += it }))
        assertEquals("https://n.example/1234567890123", m.match(text, numericTracking = true)?.target)
        assertEquals(listOf("slow"), timedOut.distinct())
    }

    // ── 样例 ──

    @Test
    fun `示例文件的样例全过`() {
        assertEquals(emptyList(), RuleSetSamples.run(load()))
    }

    @Test
    fun `样例能查出冲突 目标不符与误命中`() {
        val doc = assertNotNull(
            RuleSet.parse(
                """{"format":2,"version":1,"passcodes":[
                    {"id":"wide","label":"W","regex":"ab(\\d+)","target":"https://w.example/{1}",
                     "samples":{"hit":[{"text":"ab12","target":"https://w.example/99"}],"miss":["ab3"]}},
                    {"id":"narrow","label":"N","regex":"ab1(\\d)","target":"https://n.example/{1}",
                     "samples":{"hit":["ab17"]}}
                ]}""",
            ).doc,
        )
        val issues = RuleSetSamples.run(RuleSetCheck.check(doc, RuleSetCheck.Mode.CI))
        assertTrue(issues.any { it.ruleId == "wide" && "跳转目标不符" in it.message }, issues.toString())
        assertTrue(issues.any { it.ruleId == "wide" && "不该命中" in it.message }, issues.toString())
        assertTrue(issues.any { it.ruleId == "narrow" && "wide" in it.message && "冲突" in it.message }, issues.toString())
    }
}
