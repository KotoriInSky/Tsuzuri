package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland.ContentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * 2026-10-22 对照 yt-dlp（Unlicense）各中文平台 IE 的 `_VALID_URL` 补的链接形态。
 * 样例链接取自对应 IE 的 `_TESTS`（同为 Unlicense），只借形态事实，实现是自写的。
 */
class PasscodeYtDlpShapesTest {

    private fun m(url: String) = assertNotNull(PasscodeRules.match(url), url)

    @Test
    fun `B 站番剧 课程 音频抽出带前缀的号`() {
        assertEquals("ep267851", m("https://www.bilibili.com/bangumi/play/ep267851").core)
        assertEquals("ss26801", m("https://www.bilibili.com/bangumi/play/ss26801").core)
        assertEquals("md28234679", m("https://www.bilibili.com/bangumi/media/md28234679").core)
        assertEquals("ep5556", m("https://www.bilibili.com/cheese/play/ep5556").core)
        assertEquals("au1003142", m("https://www.bilibili.com/audio/au1003142").core)
        assertEquals("am10624", m("https://www.bilibili.com/audio/am10624").core)
    }

    @Test
    fun `B 站音频走音乐模板 番剧仍是长视频`() {
        assertEquals(ContentCategory.MUSIC, m("https://www.bilibili.com/audio/au1003142").category)
        assertEquals(ContentCategory.LONG_VIDEO, m("https://www.bilibili.com/bangumi/play/ep267851").category)
        // 原有形态不受影响
        assertEquals("BV1GJ411x7h7", m("https://www.bilibili.com/video/BV1GJ411x7h7").core)
    }

    @Test
    fun `niconico 与 AcFun 是视频模板 直播与番剧有号`() {
        assertEquals(ContentCategory.LONG_VIDEO, m("https://www.nicovideo.jp/watch/sm22312215").category)
        assertEquals("lv329299587", m("https://live.nicovideo.jp/watch/lv329299587").core)
        assertEquals(ContentCategory.LONG_VIDEO, m("https://www.acfun.cn/v/ac35457073").category)
        assertEquals("aa5022158", m("https://www.acfun.cn/bangumi/aa5022158").core)
        assertEquals(ContentCategory.LONG_VIDEO, assertNotNull(PasscodeRules.match("sm9")).category)
    }

    @Test
    fun `网易云不同类型的同号不再合并`() {
        val song = m("https://music.163.com/#/song?id=22735043")
        val playlist = m("https://music.163.com/#/playlist?id=22735043")
        assertEquals("22735043", song.core)
        assertEquals("playlist22735043", playlist.core)
        assertNotEquals(PasscodeLinkIdentity.fromUrl(song.target), PasscodeLinkIdentity.fromUrl(playlist.target))
        assertEquals("album220780", m("http://music.163.com/#/album?id=220780").core)
        assertEquals("djradio42", m("http://music.163.com/#/djradio?id=42").core)
        // 旧形态与旧身份不变
        assertEquals("17241424", m("http://music.163.com/song?id=17241424").core)
        assertEquals("347230", m("https://music.163.com/song/347230/?userid=111").core)
    }

    @Test
    fun `腾讯视频 优酷 土豆 爱奇艺抽出内容号`() {
        assertEquals("a00269ix3l8", m("https://v.qq.com/x/cover/7ce5noezvafma27/a00269ix3l8.html").core)
        assertEquals("o3013za7cse", m("https://v.qq.com/x/page/o3013za7cse.html").core)
        assertEquals("7ce5noezvafma27", m("https://v.qq.com/x/cover/7ce5noezvafma27.html").core)
        assertEquals("XNjA1NzA2Njgw", m("http://v.youku.com/v_show/id_XNjA1NzA2Njgw.html").core)
        val tudou = m("https://play.tudou.com/v_show/id_XNjAxNjI2OTU3Ng==.html?")
        assertEquals(PasscodeRules.P_YOUKU, tudou.platform)
        assertEquals("XNjAxNjI2OTU3Ng", tudou.core)
        assertEquals("v_19rrhnnclk", m("http://www.iqiyi.com/v_19rrhnnclk.html").core)
    }

    @Test
    fun `TikTok 分享域与视频号`() {
        val a = m("https://www.tiktok.com/@leenabhushan/video/6748451240264420610")
        assertEquals("6748451240264420610", a.core)
        assertEquals(PasscodeRules.P_TIKTOK, m("https://www.tiktokv.com/share/video/6782889302012578053").platform)
    }

    @Test
    fun `知乎视频走视频模板 斗鱼备用域`() {
        assertEquals(ContentCategory.LONG_VIDEO, m("https://www.zhihu.com/zvideo/1342930761977176064").category)
        assertEquals(ContentCategory.IMAGE_TEXT, m("https://www.zhihu.com/question/123456789").category)
        assertEquals(PasscodeRules.P_DOUYU, m("https://www.douyutv.com/iseven").platform)
    }
}
