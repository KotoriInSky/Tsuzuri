package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 抖音分享页解析与地址归一。
 *
 * ⚠️ 样本按**真机页面的真实结构**合成（字段名、顺序、`\u002F` 转义、`item_list` 形态都照实），
 * 没有搬运真实用户内容。真机取证见 [DouyinShare] 的类注释。
 */
class DouyinShareTest {

    /** 图文作品（真机形态：`desc` 为空、`images[0].url_list` 是首图、URL 用 `\u002F` 转义）。 */
    private val notePage = """
        <script>window._ROUTER_DATA = {"loaderData":{"video_layout":null,
        "note_(id)/page":{"ua":"Mozilla","lastPath":"7692436928063127729","itemId":"7692436928063127729",
        "videoInfoRes":{"item_list":[{"aweme_id":"7692436928063127729","desc":"","create_time":1791035041,
        "author":{"short_id":"104848579","nickname":"测试作者","signature":"签名",
        "avatar_thumb":{"uri":"100x100\u002Faweme-avatar\u002Ftos-cn-avt-0015","url_list":["https:\u002F\u002Fp11.douyinpic.com\u002Faweme\u002F100x100\u002Faa.jpg"]}},
        "images":[{"uri":"tos-cn-i-0813c000-ce\u002FoA13","url_list":["https:\u002F\u002Fp3-sign.douyinpic.com\u002Ftos-cn-i-0813c000-ce\u002Faa~tplv.jpg?x-expires=1"]},
        {"uri":"bb","url_list":["https:\u002F\u002Fp3-sign.douyinpic.com\u002Ftos-cn-i-0813c000-ce\u002Fbb~tplv.jpg"]}],
        "video":{"play_addr":{"uri":"v0200","url_list":["https:\u002F\u002Fv3.douyinpic.com\u002Fplay"]}},
        "statistics":{"digg_count":1}}]}},"errors":{}}</script>
    """.trimIndent()

    /** 视频作品：`desc` 有值，封面走 `video.cover`。 */
    private val videoPage = """
        <script>window._ROUTER_DATA = {"loaderData":{
        "video_(id)/page":{"itemId":"6961737553342991651",
        "videoInfoRes":{"item_list":[{"aweme_id":"6961737553342991651","desc":"这是一条视频文案 #话题",
        "author":{"short_id":"1","nickname":"视频作者",
        "avatar_thumb":{"url_list":["https:\u002F\u002Fp11.douyinpic.com\u002Fav.jpg"]}},
        "video":{"cover":{"uri":"c1","url_list":["https:\u002F\u002Fp3-sign.douyinpic.com\u002Fcover.jpg?x=1"]},
        "play_addr":{"url_list":["https:\u002F\u002Fv3.douyinpic.com\u002Fplay"]}}}]}},"errors":{}}</script>
    """.trimIndent()

    @Test
    fun `图文页取出作者与首图，URL 转义被还原`() {
        val f = DouyinShare.parse(notePage)
        assertEquals("测试作者", f?.author)
        assertNull(f?.title) // 真机这条 desc 就是空的
        assertEquals(
            "https://p3-sign.douyinpic.com/tos-cn-i-0813c000-ce/aa~tplv.jpg?x-expires=1",
            f?.cover,
        )
        assertEquals("7692436928063127729", f?.awemeId)
    }

    @Test
    fun `视频页取出文案与封面`() {
        val f = DouyinShare.parse(videoPage)
        assertEquals("这是一条视频文案 #话题", f?.title)
        assertEquals("视频作者", f?.author)
        assertEquals("https://p3-sign.douyinpic.com/cover.jpg?x=1", f?.cover)
        assertEquals("6961737553342991651", f?.awemeId)
    }

    @Test
    fun `页里没有 item_list 时返回 null`() {
        assertNull(DouyinShare.parse(""))
        assertNull(DouyinShare.parse("""<script>window._ROUTER_DATA = {"loaderData":{}}</script>"""))
    }

    /** 唯一带内容的形态是 `iesdouyin.com/share/...`；`www.douyin.com/video/<号>` 实测没有数据。 */
    @Test
    fun `分享页地址归一`() {
        assertEquals(
            "https://www.iesdouyin.com/share/video/6961737553342991651/",
            DouyinShare.shareUrl("https://www.douyin.com/video/6961737553342991651?foo=1"),
        )
        assertEquals(
            "https://www.iesdouyin.com/share/note/7692436928063127729/",
            DouyinShare.shareUrl("https://www.douyin.com/note/7692436928063127729"),
        )
        // 已经是分享页形态时保持 note/video 类型
        assertEquals(
            "https://www.iesdouyin.com/share/note/7692436928063127729/",
            DouyinShare.shareUrl("https://www.iesdouyin.com/share/note/7692436928063127729/?region=CN"),
        )
        // 短链没有号 → null（由展示侧先重定向）
        assertNull(DouyinShare.shareUrl("https://v.douyin.com/Xf8x_mD1CjY/"))
    }

    @Test
    fun `作品号与域名判定`() {
        assertEquals("6961737553342991651", DouyinShare.awemeId("https://www.douyin.com/video/6961737553342991651"))
        assertEquals("7692436928063127729", DouyinShare.awemeId("https://www.iesdouyin.com/share/note/7692436928063127729/"))
        assertNull(DouyinShare.awemeId("https://v.douyin.com/Xf8x_mD1CjY/"))

        assertTrue(DouyinShare.isNote("https://www.douyin.com/note/7692436928063127729"))
        assertTrue(DouyinShare.isDouyinHost("www.iesdouyin.com"))
        assertTrue(DouyinShare.isDouyinHost("v.douyin.com"))
        assertTrue(!DouyinShare.isDouyinHost("notdouyin.com"))
    }
}
