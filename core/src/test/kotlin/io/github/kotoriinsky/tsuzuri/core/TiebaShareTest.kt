package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiebaShareTest {

    private val img = "<div class=\"pb_img_item\" data-url='http://c.hiphotos.baidu.com/forum/w%3D250/sign=x/aaa.jpg?" +
        "&amp;src=http%3A%2F%2Fimgsrc.baidu.com%2Fforum%2Fpic%2Fitem%2Faaa.jpg'></div>"

    @Test
    fun `取一楼第一张图的原图并改成 https`() {
        val html = "<div class=\"content\" lz=\"1\">正文<br>$img</div>"
        assertEquals("https://imgsrc.baidu.com/forum/pic/item/aaa.jpg", TiebaShare.firstFloorImage(html))
    }

    @Test
    fun `一楼没图时不拿回帖的图`() {
        val html = "<div class=\"content\" lz=\"1\">只有字</div><div class=\"content\" lz=\"0\">$img</div>"
        assertNull(TiebaShare.firstFloorImage(html))
    }

    @Test
    fun `没有 src 参数时用缩略图`() {
        val html = "<div class=\"content\" lz=\"1\"><div class=\"pb_img_item\" data-url='http://c.hiphotos.baidu.com/a.jpg'></div>"
        assertEquals("https://c.hiphotos.baidu.com/a.jpg", TiebaShare.firstFloorImage(html))
    }

    @Test
    fun `结构对不上返回 null`() {
        assertNull(TiebaShare.firstFloorImage(""))
        assertNull(TiebaShare.firstFloorImage("<div class=\"content\">$img</div>"))
    }
}
