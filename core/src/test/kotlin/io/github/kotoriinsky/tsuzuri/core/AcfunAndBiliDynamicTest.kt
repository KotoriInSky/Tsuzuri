package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B 项（AcFun / B站动态）的字段提取。
 *
 * ⚠️ 样本按**真机页面的真实结构**合成（字段名与转义照实），没有搬运真实用户内容。
 * 取证结论见两个 object 的类注释。
 */
class AcfunAndBiliDynamicTest {

    /** AcFun：`window.videoInfo` 里标题与封面都是明文。 */
    private val acfunPage = """
        <script>window.videoInfo ={"id":"46593564","title":"长征十二号火箭成功首飞！我国首个商业航天发射场首发任务告捷",
        "cover":"https://tx-free-imgs.acfun.cn/newUpload/65005693_798f8cc616d443c4b09a8e3d07148fa5.jpeg?imageslim",
        "videoCover":"https://tx-free-imgs.acfun.cn/newUpload/other.jpeg"}</script>
    """.trimIndent()

    @Test
    fun `AcFun 取标题与封面`() {
        val f = AcfunShare.parse(acfunPage)
        assertEquals("长征十二号火箭成功首飞！我国首个商业航天发射场首发任务告捷", f?.title)
        assertEquals(
            "https://tx-free-imgs.acfun.cn/newUpload/65005693_798f8cc616d443c4b09a8e3d07148fa5.jpeg?imageslim",
            f?.cover,
        )
    }

    @Test
    fun `AcFun 没有 videoInfo 时返回 null`() {
        assertNull(AcfunShare.parse(""))
        assertNull(AcfunShare.parse("<html><title>AcFun</title></html>"))
    }

    /** B站动态：`pic.pics[0].url`，转义要还原、http 要改 https。 */
    @Test
    fun `B站动态取首图并修正转义与协议`() {
        val html = """
            {"blocks":[{"para_type":2,"pic":{"pics":[{"url":"http:\u002F\u002Fi0.hdslb.com\u002Fbfs\u002Fnew_dyn\u002Faabb.jpg","width":1680}]}}]}
        """.trimIndent()
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/aabb.jpg", BiliDynamicShare.firstImage(html))
    }

    @Test
    fun `B站动态没有图时返回 null（纯文字动态）`() {
        assertNull(BiliDynamicShare.firstImage(""))
        assertNull(BiliDynamicShare.firstImage("""{"pic":null,"line":null}"""))
    }
}
