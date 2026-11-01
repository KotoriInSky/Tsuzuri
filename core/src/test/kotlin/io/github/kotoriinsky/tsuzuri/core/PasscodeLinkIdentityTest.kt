package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PasscodeLinkIdentityTest {
    @Test
    fun `长链和已展开短链使用同一内容号`() {
        val longUrl = "https://www.bilibili.com/video/BV1GJ411x7h7?spm_id_from=share"
        val resolvedShortUrl = "https://www.bilibili.com/video/BV1GJ411x7h7"
        assertEquals(PasscodeLinkIdentity.fromUrl(longUrl), PasscodeLinkIdentity.fromUrl(resolvedShortUrl))
    }

    @Test
    fun `全字母视频号仍是内容身份`() {
        val shortForm = "https://youtu.be/AAAAAAAAAAA"
        val longForm = "https://www.youtube.com/watch?v=AAAAAAAAAAA"
        assertEquals(PasscodeLinkIdentity.fromUrl(shortForm), PasscodeLinkIdentity.fromUrl(longForm))
    }

    @Test
    fun `小红书相同笔记合并但不同笔记不合并`() {
        val first = "https://www.xiaohongshu.com/explore/65f81234567890abcdef1234?xsec_token=first"
        val second = "https://www.xiaohongshu.com/discovery/item/65f81234567890abcdef1234?xsec_token=second"
        val other = "https://www.xiaohongshu.com/explore/65f81234567890abcdef1235"
        assertEquals(PasscodeLinkIdentity.fromUrl(first), PasscodeLinkIdentity.fromUrl(second))
        assertNotEquals(PasscodeLinkIdentity.fromUrl(first), PasscodeLinkIdentity.fromUrl(other))
    }

    @Test
    fun `未知网页保留可能指向不同内容的参数`() {
        assertNotEquals(
            PasscodeLinkIdentity.fromUrl("https://example.org/item?id=1"),
            PasscodeLinkIdentity.fromUrl("https://example.org/item?id=2"),
        )
        assertNull(PasscodeLinkIdentity.fromUrl("javascript:alert(1)"))
    }

    @Test
    fun `查询参数里的平台域名不能改变真实来源`() {
        val input = "https://example.org/page?next=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1GJ411x7h7"
        assertEquals(PasscodeRules.P_GENERIC, PasscodeRules.match(input)?.platform)
    }

    @Test
    fun `完整文案与纯未知短链命中同一入口`() {
        val url = "https://bit.ly/example"
        val plain = PasscodeRules.match(url)!!
        val shared = PasscodeRules.match("【视频演示】$url 复制打开")!!
        assertEquals(plain.target, shared.target)
        assertEquals(plain.platform, shared.platform)
        assertEquals("视频演示", shared.core)
    }

    // ── fromMatch：把身份从「只有 URL 规则」扩到所有规则（2026-10-06）────────────

    /** 用户实际遇到的是「裸号 / 分享文案里的号 / 带号的长链」三种写法混用。 */
    @Test
    fun `同一个 BV 号的三种写法落到同一内容身份`() {
        val forms = listOf(
            "BV1GJ411x7h7",
            "【标题】BV1GJ411x7h7 复制打开B站",
            "https://www.bilibili.com/video/BV1GJ411x7h7?spm_id_from=share",
        ).map { text ->
            val m = PasscodeRules.match(text)
            m?.let { PasscodeLinkIdentity.fromMatch(it.platform?.pkg, it.core, it.target) }
        }
        assertEquals(3, forms.count { it != null }, "三种写法都应能匹配：$forms")
        assertEquals(1, forms.distinct().size, "同一内容只能有一个身份：$forms")
    }

    /** 号规则以前根本不给身份（旧实现只在 rule == "url" 时算），所以重复复制号没有去重。 */
    @Test
    fun `裸号形态的身份由平台包名和号组成`() {
        val m = PasscodeRules.match("BV1GJ411x7h7")!!
        val id = PasscodeLinkIdentity.fromMatch(m.platform?.pkg, m.core, m.target)!!
        assertEquals("content:${m.platform!!.pkg}:BV1GJ411x7h7", id)
    }

    /** core 是标题（不是号）时不能拿标题当身份，否则两条不同内容会撞成一条。 */
    @Test
    fun `core 是标题时回落到 URL 身份`() {
        val url = "https://example.org/item?id=1"
        val m = PasscodeRules.match("【视频演示】$url")!!
        assertEquals(false, PasscodeIsland.isIdLike(m.core ?: ""))
        assertEquals(PasscodeLinkIdentity.fromUrl(url), PasscodeLinkIdentity.fromMatch(m.platform?.pkg, m.core, m.target))
    }

    @Test
    fun `没有平台或没有链接时不产生身份`() {
        assertNull(PasscodeLinkIdentity.fromMatch(null, "BV1GJ411x7h7", null))
        assertNull(PasscodeLinkIdentity.fromMatch("com.example", "标题里有中文", null))
    }
}
