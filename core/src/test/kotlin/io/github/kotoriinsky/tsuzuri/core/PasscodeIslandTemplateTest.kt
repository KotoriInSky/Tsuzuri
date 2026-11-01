package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland.ContentCategory
import io.github.kotoriinsky.tsuzuri.core.PasscodeIslandTemplate.Picture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasscodeIslandTemplateTest {
    @Test
    fun `有封面用封面并给右上 App 图标`() {
        val e = PasscodeIslandTemplate.expanded(ContentCategory.LONG_VIDEO, "某视频", "UP 主", hasCover = true)
        assertEquals(Picture.COVER, e.picture)
        assertTrue(e.showAppMark)
        assertEquals("UP 主", e.content)
    }

    @Test
    fun `无封面用 App 图标且不重复放 E 区`() {
        for (c in ContentCategory.values()) {
            val e = PasscodeIslandTemplate.expanded(c, "标题", null, hasCover = false)
            assertEquals(Picture.TARGET_ICON, e.picture, "$c")
            assertFalse(e.showAppMark, "$c")
        }
    }

    @Test
    fun `次要文本必传 七类都不为空`() {
        for (c in ContentCategory.values()) {
            val e = PasscodeIslandTemplate.expanded(c, "标题", null, hasCover = false)
            assertTrue(e.content.isNotBlank(), "$c")
        }
    }

    @Test
    fun `次要文本按次行 平台名 域名 分类名的顺序兜底`() {
        fun content(secondary: String?, platform: String?, domain: String?) =
            PasscodeIslandTemplate.expanded(ContentCategory.WEB, "标题", secondary, false, platform, domain).content
        assertEquals("example.com", content(null, null, "example.com"))
        assertEquals("哔哩哔哩", content(null, "哔哩哔哩", "b23.tv"))
        assertEquals("作者", content("作者", "哔哩哔哩", "b23.tv"))
        assertEquals("网页", content(null, null, null))
        assertEquals("网页", content("  ", "", null))
    }

    @Test
    fun `次要文本不抄主文本`() {
        val e = PasscodeIslandTemplate.expanded(
            ContentCategory.SHORT_VIDEO, "抖音", "抖音", hasCover = false, platformName = "抖音",
        )
        assertEquals("短视频", e.content)
    }

    @Test
    fun `兜底标题是打开某应用 次要文本不重复标题里的字`() {
        assertEquals("打开 YouTube", PasscodeIslandTemplate.fallbackTitle("YouTube"))
        assertEquals("打开小红书", PasscodeIslandTemplate.fallbackTitle("小红书"))
        val xhs = PasscodeIslandTemplate.expanded(
            ContentCategory.IMAGE_TEXT, "打开小红书", null, false, platformName = "小红书", domain = "xhslink.com",
        )
        assertEquals("xhslink.com", xhs.content)
        val yt = PasscodeIslandTemplate.expanded(
            ContentCategory.LONG_VIDEO, "打开 YouTube", "dQw4w9WgXcQ", false, platformName = "YouTube",
        )
        assertEquals("dQw4w9WgXcQ", yt.content)
        val spotify = PasscodeIslandTemplate.expanded(
            ContentCategory.MUSIC, "打开 Spotify", null, false, platformName = "Spotify", domain = "spotify.com",
        )
        assertEquals("spotify.com", spotify.content)
    }

    @Test
    fun `快递 主文本承运商 次要文本单号`() {
        val e = PasscodeIslandTemplate.expanded(ContentCategory.EXPRESS, "顺丰速运", "SF1234567890123", false)
        assertEquals("SF1234567890123", e.content)
        assertEquals(Picture.TARGET_ICON, e.picture)
    }

    @Test
    fun `网页无标题时次行是路径`() {
        assertEquals("/api/v1", PasscodeIslandTemplate.webPathLine("https://localhost:8080/api/v1/?x=1"))
        assertEquals("/文档", PasscodeIslandTemplate.webPathLine("https://example.org/%E6%96%87%E6%A1%A3"))
        assertEquals(null, PasscodeIslandTemplate.webPathLine("https://example.org/"))
        assertEquals(null, PasscodeIslandTemplate.webPathLine("https://example.org"))
        val long = PasscodeIslandTemplate.webPathLine("https://example.org/" + "a".repeat(80))!!
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `次行从链接读出所在社区`() {
        assertEquals("r/japanweather", PasscodeIslandTemplate.communityLine("https://www.reddit.com/r/japanweather/s/ML54TjloLD"))
        assertEquals("r/Android", PasscodeIslandTemplate.communityLine("https://old.reddit.com/r/Android/comments/abc/x/"))
        assertEquals("u/spez", PasscodeIslandTemplate.communityLine("https://reddit.com/user/spez"))
        assertEquals("南小鸟吧", PasscodeIslandTemplate.communityLine("https://tieba.baidu.com/f?kw=%E5%8D%97%E5%B0%8F%E9%B8%9F"))
        assertEquals(null, PasscodeIslandTemplate.communityLine("https://tieba.baidu.com/p/6157983019"))
        assertEquals(null, PasscodeIslandTemplate.communityLine("https://www.reddit.com/"))
        assertEquals(null, PasscodeIslandTemplate.communityLine("https://example.org/r/x"))
    }
}
