package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BiliVideoShareTest {

    @Test
    fun `多 P 地址带 p 参数也取稿件接口`() {
        assertEquals(
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1Wv411h7kN",
            BiliVideoShare.apiUrl("https://www.bilibili.com/video/BV1Wv411h7kN/?p=3&share_source=copy"),
        )
        assertEquals(
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1GJ411x7h7",
            BiliVideoShare.apiUrl("https://m.bilibili.com/video/BV1GJ411x7h7"),
        )
    }

    @Test
    fun `av 号`() {
        assertEquals(
            "https://api.bilibili.com/x/web-interface/view?aid=170001",
            BiliVideoShare.apiUrl("https://www.bilibili.com/video/av170001"),
        )
    }

    @Test
    fun `非视频页与外站不处理`() {
        assertNull(BiliVideoShare.apiUrl("https://www.bilibili.com/opus/998440765151510535"))
        assertNull(BiliVideoShare.apiUrl("https://b23.tv/abcdefg"))
        assertNull(BiliVideoShare.apiUrl("https://evil.com/video/BV1GJ411x7h7"))
    }

    @Test
    fun `封面升 https 且只认 hdslb`() {
        assertEquals("https://i2.hdslb.com/bfs/archive/a.jpg", BiliVideoShare.coverUrl("http://i2.hdslb.com/bfs/archive/a.jpg"))
        assertNull(BiliVideoShare.coverUrl("http://evil.com/a.jpg"))
        assertNull(BiliVideoShare.coverUrl(""))
    }
}
