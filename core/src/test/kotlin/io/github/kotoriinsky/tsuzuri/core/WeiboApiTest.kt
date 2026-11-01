package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 微博头条文章 / 视频（fid）的地址识别与字段提取。
 *
 * ⚠️ 样本按**接口返回体的真实结构**合成（字段名照实，含协议相对 URL 与转义），
 * 没有搬运真实用户内容。端点与字段来源见 [WeiboApi] 的类注释。
 */
class WeiboApiTest {

    @Test
    fun `文章地址识别`() {
        assertEquals(
            "2309404962180771742222",
            WeiboApi.articleId("https://weibo.com/ttarticle/p/show?id=2309404962180771742222"),
        )
        assertEquals(
            "2309404962180771742222",
            WeiboApi.articleId("https://card.weibo.com/article/m/show/id/2309404962180771742222"),
        )
        assertNull(WeiboApi.articleId("https://weibo.com/7207262816/P5kWdcfDe"))
    }

    @Test
    fun `视频 fid 识别`() {
        assertEquals("1034:5145615399845897", WeiboApi.videoFid("https://video.weibo.com/show?fid=1034:5145615399845897"))
        assertEquals("1034:5197094538117264", WeiboApi.videoFid("https://weibo.com/tv/show/1034:5197094538117264?mid=1"))
        assertNull(WeiboApi.videoFid("https://m.weibo.cn/status/Q0KtXh6z2"))
    }

    /** 文章的封面在 `data.content`（**转义在 JSON 字符串里的 HTML**）里取首个 `<img src>`。 */
    @Test
    fun `文章返回体取标题作者与首个配图`() {
        val json = """
            {"code":"100000","msg":"success","data":{"url":"https://weibo.com/ttarticle/p/show?id=1",
            "title":"这是头条文章标题","create_at_unix":1761372823,
            "userinfo":{"screen_name":"测试作者","profile_image_url":"https://tvax1.sinaimg.cn/a.jpg"},
            "content":"<p>第一段</p><img src=\"https://wx1.sinaimg.cn/large/abc.jpg\"><p>第二段</p>"}}
        """.trimIndent()
        val f = WeiboApi.parseArticle(json)
        assertEquals("这是头条文章标题", f?.title)
        assertEquals("测试作者", f?.author)
        assertEquals("https://wx1.sinaimg.cn/large/abc.jpg", f?.cover)
    }

    /** 视频的 `cover_image` 是**协议相对**地址（`//…`），要补成 https。 */
    @Test
    fun `视频返回体取标题封面并把协议相对地址补全`() {
        val json = """
            {"code":"100000","data":{"Component_Play_Playinfo":{
            "title":"这是视频标题","cover_image":"//wx2.sinaimg.cn/large/cover.jpg",
            "real_date":"2026-10-06","reward":{"user":{"name":"视频作者","profile_image_url":"//tva3.sinaimg.cn/a.jpg"}},
            "stream_url":"//f.video.weibocdn.com/x.mp4"}}}
        """.trimIndent()
        val f = WeiboApi.parseVideo(json)
        assertEquals("这是视频标题", f?.title)
        assertEquals("视频作者", f?.author)
        assertEquals("https://wx2.sinaimg.cn/large/cover.jpg", f?.cover)
    }

    @Test
    fun `解析不到内容时返回 null`() {
        assertNull(WeiboApi.parseArticle(""))
        assertNull(WeiboApi.parseVideo(""))
        assertNull(WeiboApi.parseVideo("""{"msg":"请求失败"}"""))
    }
}
