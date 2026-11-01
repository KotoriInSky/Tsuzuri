package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PasscodeRedirectIdentityTest {
    @Test
    fun `小红书相同笔记忽略分享话术和跟踪参数`() {
        val first = PasscodeRules.match(
            "看看这篇 https://www.xiaohongshu.com/explore/65f81234567890abcdef1234?xsec_token=first",
        )!!
        val second = PasscodeRules.match(
            "复制打开小红书 https://www.xiaohongshu.com/discovery/item/65f81234567890abcdef1234?xsec_token=second",
        )!!
        val other = PasscodeRules.match(
            "https://www.xiaohongshu.com/explore/65f81234567890abcdef1235",
        )!!
        assertEquals(PasscodeRules.duplicateIdentity(first), PasscodeRules.duplicateIdentity(second))
        assertNotEquals(PasscodeRules.duplicateIdentity(first), PasscodeRules.duplicateIdentity(other))
    }

    @Test
    fun `小红书短链以路径标识内容`() {
        val first = PasscodeRules.match("笔记 https://xhslink.cn/o/2HF9do5niR?share_from=one")!!
        val second = PasscodeRules.match("https://xhslink.cn/o/2HF9do5niR?share_from=two")!!
        val other = PasscodeRules.match("https://xhslink.cn/o/3HF9do5niR")!!
        assertEquals(PasscodeRules.duplicateIdentity(first), PasscodeRules.duplicateIdentity(second))
        assertNotEquals(PasscodeRules.duplicateIdentity(first), PasscodeRules.duplicateIdentity(other))
        assertNull(PasscodeRules.duplicateIdentity(PasscodeRules.match("https://example.org/page")!!))
    }

    @Test
    fun `淘宝共用短链只在真实跳转闲鱼后改类`() {
        val source = "https://m.tb.cn/h.abc123"
        val idlefish = PasscodeRules.redirectedMatch(
            source,
            "https://h5.m.goofish.com/item?id=123456",
        )
        assertEquals(PasscodeRules.P_XIANYU, idlefish?.platform)
        assertEquals(PasscodeIsland.ContentCategory.ECOMMERCE, idlefish?.category)
        assertNull(PasscodeRules.redirectedMatch(source, "https://item.taobao.com/item.htm?id=123456"))
        assertNull(PasscodeRules.redirectedMatch("https://example.org/page", "https://h5.m.goofish.com/item?id=123456"))
        assertNull(PasscodeRules.redirectedMatch(source, "https://goofish.com.evil.test/item/123"))
    }

    @Test
    fun `X 状态页只接受真实平台主机和路径`() {
        assertEquals("jack" to "20", PasscodeRules.xStatusIdentity("https://x.com/jack/status/20"))
        assertEquals("jack" to "20", PasscodeRules.xStatusIdentity("https://twitter.com/jack/status/20?s=20"))
        assertNull(PasscodeRules.xStatusIdentity("https://x.com.evil.test/jack/status/20"))
        assertNull(PasscodeRules.xStatusIdentity("https://x.com/jack/home"))
    }

    @Test
    fun `拼多多商品链接回首页不当成商品详情`() {
        val source = "https://mobile.yangkeduo.com/goods2.html?ps=6tn3qq7Gvl"
        assertEquals(true, PasscodeRules.isPddHomepageFallback(source, "https://mobile.yangkeduo.com/"))
        assertEquals(false, PasscodeRules.isPddHomepageFallback(source, source))
        assertEquals(false, PasscodeRules.isPddHomepageFallback(source, "https://mobile.yangkeduo.com/goods.html?goods_id=123"))
        assertEquals(false, PasscodeRules.isPddHomepageFallback("https://example.org/page", "https://mobile.yangkeduo.com/"))
    }
}
