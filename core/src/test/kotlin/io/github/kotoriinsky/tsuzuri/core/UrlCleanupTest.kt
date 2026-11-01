package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 身份用的查询参数归一化。
 *
 * 清单来源：**ParseHub**（`z-mio/ParseHub`，MIT）各 parser 的 `__reserved_parameters__` /
 * `__after_clean_parameters__`。见 [UrlCleanup] 的类注释。
 */
class UrlCleanupTest {

    /** 小红书 `xsec_token`：**每次分享都不一样**，不能进身份。 */
    @Test
    fun `小红书去掉 xsec_token`() {
        val a = "https://www.xiaohongshu.com/explore/68e8e3fa00000000030342ec?xsec_token=AAA&apptime=1"
        val b = "https://www.xiaohongshu.com/explore/68e8e3fa00000000030342ec?xsec_token=BBB&apptime=2"
        assertEquals("apptime=1", UrlCleanup.cleanUrlQuery(a))
        // 身份层面：两个不同的 token 应当合并成一个身份
        assertEquals(PasscodeLinkIdentity.fromUrl(a), PasscodeLinkIdentity.fromUrl(b))
    }

    /** 酷安 `shareKey` / `s` 是分享期参数（ParseHub 列为 after_clean）。 */
    @Test
    fun `酷安去掉 shareKey 与 s`() {
        assertEquals(
            "id=74187665",
            UrlCleanup.cleanUrlQuery("https://www.coolapk.com/feed/74187665?id=74187665&shareKey=abc&s=xyz"),
        )
    }

    /** `__reserved_parameters__`：**只保留**列出的参数。 */
    @Test
    fun `微博只保留 fid、B站只保留 p、YouTube 只保留 v-list-index`() {
        assertEquals("fid=1034:5145615399845897", UrlCleanup.cleanUrlQuery("https://video.weibo.com/show?fid=1034:5145615399845897&from=timeline"))
        assertEquals("p=40", UrlCleanup.cleanUrlQuery("https://www.bilibili.com/video/BV1584y167sD?p=40&spm_id_from=share"))
        assertEquals("v=dQw4w9WgXcQ&list=PL1", UrlCleanup.cleanUrlQuery("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1&t=30"))
    }

    /** 通用跟踪参数（`utm_*`）与本仓库自己的补充。 */
    @Test
    fun `通用 utm 参数被去掉`() {
        assertEquals("id=1", UrlCleanup.cleanUrlQuery("https://example.org/a?id=1&utm_source=wx&utm_medium=share"))
        // 没有可去掉的参数时原样返回（保持顺序与编码）
        assertEquals("a=1&b=2", UrlCleanup.cleanUrlQuery("https://example.org/x?a=1&b=2"))
    }

    /** tracking-query-params-registry（Unlicense）的通用参数与国内分享参数进身份清理。 */
    @Test
    fun `登记表与国内分享参数进身份清理`() {
        assertEquals("id=1", UrlCleanup.cleanUrlQuery("https://example.org/a?fbclid=x&id=1&gclid=y&_gl=z"))
        assertEquals("id=1", UrlCleanup.cleanUrlQuery("https://example.org/a?id=1&spm=a2.b&share_source=wx&vd_source=v"))
        assertEquals(
            PasscodeLinkIdentity.fromUrl("https://example.org/a?id=1&fbclid=AAA"),
            PasscodeLinkIdentity.fromUrl("https://example.org/a?id=1&fbclid=BBB&igshid=q"),
        )
        // 容易与内容号撞名的短名不删
        assertEquals("cid=7&sid=8", UrlCleanup.cleanUrlQuery("https://example.org/a?cid=7&sid=8"))
        // Spotify 的 si 是每次分享都变的分享者标记
        assertEquals("", UrlCleanup.cleanUrlQuery("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc"))
    }

    /** 分享出去的地址只去平台无关参数；平台专属（可能是凭据）的保留。 */
    @Test
    fun `stripTracking 只去通用参数`() {
        assertEquals("https://example.org/a?id=1", UrlCleanup.stripTracking("https://example.org/a?id=1&utm_source=wx&fbclid=x"))
        assertEquals("https://example.org/a#top", UrlCleanup.stripTracking("https://example.org/a?utm_medium=s#top"))
        val xhs = "https://www.xiaohongshu.com/explore/68e8e3fa00000000030342ec?xsec_token=AAA"
        assertEquals(xhs, UrlCleanup.stripTracking(xhs))
        assertEquals("https://example.org/a", UrlCleanup.stripTracking("https://example.org/a"))
        assertEquals("https://example.org/a?b=%E4%B8%AD", UrlCleanup.stripTracking("https://example.org/a?b=%E4%B8%AD&spm=1"))
    }

    /** 只影响身份，**不改打开地址**（`xsec_token` 可能是访问凭据）。 */
    @Test
    fun `参数里有内容号时身份仍然稳定`() {
        val url = "https://www.bilibili.com/video/BV1GJ411x7h7?spm_id_from=share&p=2"
        assertEquals("content:tv.danmaku.bili:BV1GJ411x7h7", PasscodeLinkIdentity.fromUrl(url))
    }

    /** 不同内容不能因为归一化被合并。 */
    @Test
    fun `不同 id 不会因归一化而合并`() {
        assertNotEquals(
            PasscodeLinkIdentity.fromUrl("https://example.org/a?id=1&utm_source=x"),
            PasscodeLinkIdentity.fromUrl("https://example.org/a?id=2&utm_source=x"),
        )
    }
}
