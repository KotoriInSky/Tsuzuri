package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 结构按 2026-10-23 真机抓到的三类页面裁剪（正文内容替换成占位）。 */
class ZhihuShareTest {

    @Test
    fun `按地址分类`() {
        assertEquals(ZhihuShare.Kind.ANSWER, ZhihuShare.kind("https://www.zhihu.com/question/11416391656/answer/2054940208743854941?share_code=x"))
        assertEquals(ZhihuShare.Kind.QUESTION, ZhihuShare.kind("https://www.zhihu.com/question/458516980?share_code=x"))
        assertEquals(ZhihuShare.Kind.ARTICLE, ZhihuShare.kind("https://zhuanlan.zhihu.com/p/2096719742627325771?share_code=x"))
        assertEquals(ZhihuShare.Kind.ARTICLE, ZhihuShare.kind("https://www.zhihu.com/appview/p/2096719742627325771"))
        assertNull(ZhihuShare.kind("https://oia.zhihu.com/km_paid_content/share?km_pst=x"))
        assertNull(ZhihuShare.kind("https://evil.com/question/458516980"))
    }

    @Test
    fun `回答：问题当标题，答主进第二行，正文首图当封面`() {
        val html = """<head><title data-rh="true">问题甲？ - 知乎</title>
            <meta data-rh="true" property="og:title" content="问题甲？ - 莫邪 的回答"/></head>
            <img src="https://pic1.zhimg.com/avatar.jpg">
            <div class="RichContent RichContent--unescapable"><figure><img src="https://pic1.zhimg.com/50/v2-a_720w.jpg"
            data-original="https://pic1.zhimg.com/v2-a_r.jpg?source=2c26e567"></figure></div>"""
        assertEquals(
            ZhihuShare.Fields("问题甲？", "莫邪的回答", "https://pic1.zhimg.com/v2-a_r.jpg?source=2c26e567"),
            ZhihuShare.parse(ZhihuShare.Kind.ANSWER, html),
        )
    }

    @Test
    fun `问题：第二行是回答数`() {
        val html = """<title data-rh="true">有没有什么巨爽无比的爽文？</title><script>{"answerCount":17760}</script>"""
        assertEquals(ZhihuShare.Fields("有没有什么巨爽无比的爽文？", "17760 个回答", null), ZhihuShare.parse(ZhihuShare.Kind.QUESTION, html))
    }

    @Test
    fun `文章：h1 是标题，作者取 author name，头像不当封面`() {
        val html = """<title>知乎 - 知乎</title><img class="Avatar" src="https://picx.zhimg.com/avatar_200x0.jpg">
            <h1 class="css-1s9cxbb">文章标题乙</h1><p>正文</p><img src="https://pic1.zhimg.com/v2-b_1440w.jpg">
            <script>{"author":{"isFollowed":false,"uid":"1","name":"王新喜","headline":"x"}}</script>"""
        assertEquals(
            ZhihuShare.Fields("文章标题乙", "王新喜", "https://pic1.zhimg.com/v2-b_1440w.jpg"),
            ZhihuShare.parse(ZhihuShare.Kind.ARTICLE, html),
        )
    }
}
