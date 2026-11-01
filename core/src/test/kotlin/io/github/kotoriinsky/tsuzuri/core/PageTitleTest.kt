package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 网页标题清洗（2026-10-06 从 hook 搬进 `:core` 并补齐两处）。
 *
 * 注意：沿用原有口径 —— **不含 CJK 且短于 8 字符的标题一律丢弃**（"Home" / "&#39;"
 * 这种不是内容），所以下面几条 ASCII 用例都刻意留够长度。
 */
class PageTitleTest {

    @Test
    fun `数字实体被解码：十进制与十六进制`() {
        assertEquals("It's fine", PageTitle.clean("It&#39;s fine"))
        assertEquals("It's fine", PageTitle.clean("It&#x27;s fine"))
        assertEquals("abcdefg…", PageTitle.clean("abcdefg&#8230;"))
    }

    /** `&amp;` 必须最后处理，否则 `&amp;#39;` 会被二次解码成 `'`。 */
    @Test
    fun `amp 不会被二次解码`() {
        assertEquals("&#39;abcd", PageTitle.clean("&amp;#39;abcd"))
    }

    @Test
    fun `更多命名实体被解码`() {
        assertEquals("甲—乙…丙", PageTitle.clean("甲&mdash;乙&hellip;丙"))
        assertEquals("“引号”", PageTitle.clean("&ldquo;引号&rdquo;"))
    }

    @Test
    fun `已知平台尾巴被剥掉`() {
        assertEquals("如何评价这次的更新？", PageTitle.clean("如何评价这次的更新？ - 知乎"))
    }

    /** 长尾站点的站名不在已知表里 —— 用**页面自己的域名**判。 */
    @Test
    fun `长尾站点的站名尾巴按域名剥掉`() {
        assertEquals(
            "Kotlin 协程入门",
            PageTitle.clean("Kotlin 协程入门 - CSDN博客", "https://blog.csdn.net/xxx"),
        )
        assertEquals(
            "随手记一条",
            PageTitle.clean("随手记一条 | GitHub", "https://github.com/a/b"),
        )
    }

    /** 反例：域名对不上就不动 —— 「- 附件」是标题的一部分，不能猜着剥。 */
    @Test
    fun `域名对不上时不动标题`() {
        assertEquals("季度报告 - 附件", PageTitle.clean("季度报告 - 附件", "https://example.org/report"))
    }

    @Test
    fun `标签被去掉`() {
        assertEquals("标题", PageTitle.clean("<b>标题</b>"))
    }

    @Test
    fun `太短的纯 ASCII 标题丢弃、超长截断`() {
        assertNull(PageTitle.clean("Home"))
        assertEquals("标".repeat(100) + "…", PageTitle.clean("标".repeat(200)))
    }

    @Test
    fun `安全验证页的标题当作没有`() {
        for (t in listOf("百度安全验证", "安全验证", "验证中心", "Just a moment...", "请完成验证后继续访问")) {
            assertNull(PageTitle.clean(t), t)
        }
        assertEquals("如何通过安全验证考试", PageTitle.clean("如何通过安全验证考试"))
    }
}
