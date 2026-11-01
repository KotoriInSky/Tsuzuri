package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland.ContentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 2026-10-22 按 wujunwei928/parse-video 与 iawia002/lux（均 MIT）的分享域补的平台。
 * 链接是按各自分享域拼的形态样例（只借域名这一事实）。
 */
class PasscodeParseVideoShapesTest {

    private fun platform(url: String) = assertNotNull(PasscodeRules.match(url), url).platform

    @Test
    fun `新平台的分享域都能认出`() {
        val cases = mapOf(
            "https://share.huoshan.com/hotsoon/s/AbCdEf/" to PasscodeRules.P_HUOSHAN,
            "https://haokan.baidu.com/v?vid=1234567890" to PasscodeRules.P_HAOKAN,
            "https://haokan.hao123.com/v?vid=1234567890" to PasscodeRules.P_HAOKAN,
            "https://www.pearvideo.com/video_1234567" to PasscodeRules.P_PEAR,
            "https://h5.pipigx.com/pp/post/123456" to PasscodeRules.P_PIPIGX,
            "https://www.meipai.com/media/123456" to PasscodeRules.P_MEIPAI,
            "https://doupai.cc/s/AbCdEf" to PasscodeRules.P_DOUPAI,
            "https://kg.qq.com/node/play?s=AbCdEf" to PasscodeRules.P_KGE,
            "https://v.6.cn/123456" to PasscodeRules.P_SIXROOM,
            "https://www.xinpianchang.com/a12345678" to PasscodeRules.P_XINPIANCHANG,
            "https://tv.sohu.com/v/AbCdEf.html" to PasscodeRules.P_SOHU_TV,
            "https://my.tv.sohu.com/us/123/456.shtml" to PasscodeRules.P_SOHU_TV,
            "https://tv.cctv.com/2026/10/22/VIDEAbCdEf.shtml" to PasscodeRules.P_CCTV,
            "https://time.geekbang.org/column/article/123456" to PasscodeRules.P_GEEKBANG,
            "https://www.tangdou.com/v/AbCdEf" to PasscodeRules.P_TANGDOU,
            "https://c.m.163.com/news/a/AbCdEf.html" to PasscodeRules.P_NETEASE_NEWS,
        )
        for ((url, expected) in cases) assertEquals(expected, platform(url), url)
    }

    @Test
    fun `不抢已有平台`() {
        // 好看视频之外的百度子站仍归百度；网易云 / BUFF 仍是各自平台
        assertEquals(PasscodeRules.P_BAIDU, platform("https://baike.baidu.com/item/abc"))
        assertEquals(PasscodeRules.P_NETEASE, platform("https://music.163.com/#/song?id=22735043"))
        assertEquals(PasscodeRules.P_BUFF, platform("https://buff.163.com/goods/123"))
        // 只认 6.cn 本身与子域，不认 x6.cn 这类后缀相同的域
        assertEquals(PasscodeRules.P_GENERIC, platform("https://x6.cn/abc"))
    }

    @Test
    fun `分类与分享文案标题`() {
        assertEquals(ContentCategory.MUSIC, assertNotNull(PasscodeRules.match("https://kg.qq.com/node/play?s=AbCdEf")).category)
        assertEquals(ContentCategory.SHORT_VIDEO, assertNotNull(PasscodeRules.match("https://share.huoshan.com/hotsoon/s/AbCdEf/")).category)
        val m = assertNotNull(PasscodeRules.match("【某个舞蹈教学】https://www.tangdou.com/v/AbCdEf"))
        assertEquals("某个舞蹈教学", m.core)
    }
}
