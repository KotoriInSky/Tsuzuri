package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 淘宝系分享短链的跳转地址提取。
 *
 * ⚠️ 样本按**真机壳页结构**合成（URL 形态照实，商品号是编的），没有搬运真实用户内容。
 */
class TaobaoShellJumpTest {

    /** 真机上闲鱼那版壳页里的形态：一长串跟踪参数里带着 `itemId`。 */
    private val goofishShell = """
        <html><head><title></title>
        <script src="//g.alicdn.com/mtb/lib-callapp/1.4.8/??combo.js,callapp.js"></script>
        <script>
        var url = "https://h5.m.goofish.com/item?forceFlush=1&itemId=1048929011444&hitNativeDetail=true&xy_refer=%7B%22campaignId%22%3A1119%7D&needNotPreGet=true&id=1048929011444&from_kun_share=default&ut_sk=1.abc_123.copy.detail.1048929011444&sp_tk=d1ZxUVRLYWdaYXM%3D&short_name=h.8BhbGii&bxsign=xyz";
        callApp("taobao://", url);
        </script></head><body></body></html>
    """.trimIndent()

    @Test
    fun `从闲鱼壳页里抠出商品地址并归一化`() {
        assertEquals(
            "https://h5.m.goofish.com/item?itemId=1048929011444",
            TaobaoShellJump.itemUrl(goofishShell),
        )
    }

    @Test
    fun `没有商品号的壳页返回 null`() {
        assertNull(TaobaoShellJump.itemUrl(""))
        assertNull(TaobaoShellJump.itemUrl("<html><title></title></html>"))
        // 有域名但没有 itemId：不能瞎拼
        assertNull(TaobaoShellJump.itemUrl("https://h5.m.goofish.com/item?forceFlush=1"))
    }

    @Test
    fun `淘宝商品页形态也能抠（未被样本证实的分支）`() {
        assertEquals(
            "https://item.taobao.com/item.htm?id=123456789012",
            TaobaoShellJump.itemUrl("location.href='https://item.taobao.com/item.htm?id=123456789012&spm=a1z10'"),
        )
    }

    /** 只有淘宝系**分享短域**才值得去抠；商品页本身（item.taobao.com）不需要。 */
    @Test
    fun `短域判定`() {
        assertTrue(TaobaoShellJump.isShareShortHost("https://m.tb.cn/h.8BhbGii?tk=x"))
        assertTrue(TaobaoShellJump.isShareShortHost("https://e.tb.cn/h.8yTkoBORPQFwdrO"))
        assertTrue(TaobaoShellJump.isShareShortHost("https://tb.cn/abc"))
        assertFalse(TaobaoShellJump.isShareShortHost("https://item.taobao.com/item.htm?id=1"))
        assertFalse(TaobaoShellJump.isShareShortHost("https://www.goofish.com/item?id=1"))
        assertFalse(TaobaoShellJump.isShareShortHost("not a url"))
    }
}
