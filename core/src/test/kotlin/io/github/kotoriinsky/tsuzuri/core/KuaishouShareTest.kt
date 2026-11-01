package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 快手分享页字段提取。
 *
 * ⚠️ 样本是**按真机页面结构合成的**（字段名、键序、混淆过的顶层键都照实），
 * 但没有搬运任何真实用户内容。
 */
class KuaishouShareTest {

    /** 顶层键位移混淆（`tusjoh` = `string`）这条特征必须保留，否则测不出"按字段名取"的需求。 */
    private val page = """
        <html><head><title>快手</title></head><body>
        <script>window.INIT_STATE = {
          "tusjoh.0sftu0xe0tztufn0tubsuvq@lqg":"\u003EBOESPJE`QIPOF`I6",
          "b":{"user":{"bannerType":2,"usedCount":"0","type":2},
               "headUrl":"https://p66.a.kwimgs.com/uhead/AB/2026/aa==_s.jpg",
               "userSex":"F","kwaiId":"kaoyu_xiao","userName":"测试作者","userId":4643086776,
               "caption":"#喜爱度激励计划 #会火 这是文案",
               "coverUrls":[{"cdn":"p4.a.yximgs.com","url":"https://p4.a.yximgs.com/upic/aa.jpg?clientCacheKey=3xn3yeegd6bq2ga.jpg&di=x"},{"cdn":"p2.a.yximgs.com","url":"https://p2.a.yximgs.com/upic/bb.jpg"}],
               "photoId":"5257389821735245544"}}
        </script></body></html>
    """.trimIndent()

    @Test
    fun `从混淆键的 INIT_STATE 里取出四个字段`() {
        val f = KuaishouShare.parse(page)
        assertEquals("#喜爱度激励计划 #会火 这是文案", f?.title)
        assertEquals("测试作者", f?.author)
        assertEquals("https://p66.a.kwimgs.com/uhead/AB/2026/aa==_s.jpg", f?.avatar)
        assertEquals("5257389821735245544", f?.photoId)
    }

    /** 封面取**第一张**（图集里的首图），并保留原样的查询参数。 */
    @Test
    fun `封面取 coverUrls 的第一项`() {
        val cover = KuaishouShare.parse(page)?.cover
        assertTrue(cover?.startsWith("https://p4.a.yximgs.com/upic/aa.jpg") == true, "$cover")
        assertTrue(cover?.contains("clientCacheKey=3xn3yeegd6bq2ga.jpg") == true)
    }

    /** 转义：JSON 里的 `\uXXXX` 与 `\"` 要还原成真正的文字。 */
    @Test
    fun `还原 JSON 转义`() {
        val html = """{"caption":"他说\"你好\" \u4e16\u754c","coverUrls":[{"url":"https://x/a.jpg"}]}"""
        assertEquals("他说\"你好\" 世界", KuaishouShare.parse(html)?.title)
    }

    @Test
    fun `标题与封面都没有时返回 null`() {
        assertNull(KuaishouShare.parse("""{"foo":1}"""))
        assertNull(KuaishouShare.parse(""))
    }

    /** 只有封面、没有文案（纯图集）也要能用。 */
    @Test
    fun `缺文案时仍返回封面`() {
        val f = KuaishouShare.parse("""{"coverUrls":[{"url":"https://x/a.jpg"}],"photoId":"123"}""")
        assertNull(f?.title)
        assertEquals("https://x/a.jpg", f?.cover)
    }

    /** 非 https 的封面不接受（与其它平台同一条纪律）。 */
    @Test
    fun `非 https 封面被丢弃`() {
        assertNull(KuaishouShare.parse("""{"caption":"有文案","coverUrls":[{"url":"http://x/a.jpg"}]}""")?.cover)
    }

    /**
     * 图集按**图文**走（短视频模板不显示封面，而图集的价值在首图）。
     * 真机样本：`v.kuaishou.com/nA6zqmo2` 落到 `…&subBiz=BROWSE_SLIDE_PHOTO&shareResourceType=PHOTO_OTHER`。
     */
    @Test
    fun `快手图集归类为图文、视频维持短视频`() {
        val photo = PasscodeRules.match(
            "https://v.m.chenzhongtech.com/fw/photo/3xn3yeegd6bq2ga?subBiz=BROWSE_SLIDE_PHOTO" +
                "&shareResourceType=PHOTO_OTHER&photoId=3xn3yeegd6bq2ga",
        )
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, photo?.category)

        val video = PasscodeRules.match("https://v.kuaishou.com/nA6zqmo2")
        assertEquals(PasscodeIsland.ContentCategory.SHORT_VIDEO, video?.category)
    }
}
