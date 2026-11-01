package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内容号抽取的五个缺口（2026-10-06 探针实测）。
 *
 * 全部是**纯规则**：不联网、不碰跨进程契约，所以一个提交就能收掉。
 */
class PasscodeContentCoreGapsTest {

    @Test
    fun `抖音长链抽到作品号`() {
        val m = PasscodeRules.match("https://www.douyin.com/video/6961737553342991651")
        assertEquals("6961737553342991651", m?.core)
        assertEquals(
            "content:com.ss.android.ugc.aweme:6961737553342991651",
            PasscodeLinkIdentity.fromMatch(m?.platform?.pkg, m?.core, m?.target),
        )
    }

    @Test
    fun `B 站动态与 opus 抽到号且归类为图文`() {
        val dynamic = PasscodeRules.match("https://t.bilibili.com/950000000000000000")
        assertEquals("950000000000000000", dynamic?.core)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, dynamic?.category)

        val opus = PasscodeRules.match("https://www.bilibili.com/opus/950000000000000001")
        assertEquals("950000000000000001", opus?.core)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, opus?.category)
    }

    @Test
    fun `B 站专栏归图文而不再算长视频`() {
        val m = PasscodeRules.match("https://www.bilibili.com/read/cv12345678")
        assertEquals("cv12345678", m?.core)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, m?.category)
    }

    /** 分享链常带 `?userid=`：两个用户分享同一首歌的 URL 不同，有了内容号才合并成一条。 */
    @Test
    fun `网易云歌曲号让不同分享者的同一首歌合并`() {
        val a = PasscodeRules.match("https://music.163.com/song/347230/?userid=111")
        val b = PasscodeRules.match("https://music.163.com/song/347230/?userid=222")
        assertEquals("347230", a?.core)
        assertEquals(
            PasscodeLinkIdentity.fromMatch(a?.platform?.pkg, a?.core, a?.target),
            PasscodeLinkIdentity.fromMatch(b?.platform?.pkg, b?.core, b?.target),
        )
    }

    /** 没有歌曲号时（`163cn.tv/<短码>`）仍要从《歌名》里提标题。 */
    @Test
    fun `网易云短链分享用书名号里的歌名`() {
        val m = PasscodeRules.match("分享单曲《海阔天空》https://163cn.tv/AbCdEf (@网易云音乐)")
        assertEquals("海阔天空", m?.core)
    }

    @Test
    fun `网盘分享码：夸克与迅雷与百度同形`() {
        assertEquals("abc123def", PasscodeRules.match("https://pan.quark.cn/s/abc123def")?.core)
        assertEquals("AbCdEf123", PasscodeRules.match("https://pan.xunlei.com/s/AbCdEf123")?.core)
        assertEquals("1abcdEFG", PasscodeRules.match("https://pan.baidu.com/s/1abcdEFG")?.core)
    }

    /** 防误伤：B 站视频仍是长视频、core 仍是 BV 号（专栏/动态的改动不能波及它）。 */
    @Test
    fun `B 站视频不受专栏动态改动影响`() {
        val m = PasscodeRules.match("https://www.bilibili.com/video/BV1GJ411x7h7")
        assertEquals("BV1GJ411x7h7", m?.core)
        assertEquals(PasscodeIsland.ContentCategory.LONG_VIDEO, m?.category)
    }
}
