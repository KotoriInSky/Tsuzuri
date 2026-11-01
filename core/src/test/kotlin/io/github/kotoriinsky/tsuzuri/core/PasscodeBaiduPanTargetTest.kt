package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 百度网盘的 App 直达（2026-10-06 真机取证）。
 *
 * 取证结论：`com.baidu.netdisk` **没有声明** `pan.baidu.com`（App Links 只验证了
 * `snsyun.baidu.com`，`dumpsys` 里匹配数 0），所以 https 目标一定落到浏览器；而
 * `baiduyun://` 解析到单一目标 `com.baidu.netdisk/.ui.WapLauncherActivity`。
 * 因此这条是**唯一**把 scheme 放 `target`、网页放 `alts` 的联网盘平台
 * （虎扑/懂球帝相反：App 能接自家 https，scheme 才是备选）。
 */
class PasscodeBaiduPanTargetTest {

    private val shareUrl = "https://pan.baidu.com/s/1abcdEFG"

    @Test
    fun `分享链用 App scheme 直达、网页退到候选链兜底`() {
        val m = PasscodeRules.match(shareUrl)
        assertNotNull(m)
        assertEquals(PasscodeRules.P_BAIDUPAN, m.platform)
        assertEquals("baiduyun://", m.target)
        assertEquals(listOf(shareUrl), m.alts)
        assertEquals("1abcdEFG", m.core)
    }

    @Test
    fun `旧形态 surl 同样取得到分享码`() {
        val m = PasscodeRules.match("https://pan.baidu.com/share/init?surl=1aBcD2e")
        assertNotNull(m)
        assertEquals("baiduyun://", m.target)
        assertEquals("1aBcD2e", m.core)
    }

    /**
     * ⚠️ 这条是本次的防回归关键。
     *
     * target 换成 scheme 之后，[PasscodeLinkIdentity.fromUrl] 因为 scheme 不是 http(s)
     * 会返回 null，通知 id 就会退化成 `"baiduyun://".hashCode()` —— **不同分享会撞成
     * 同一条通知**（第二条在岛的存活窗口内被去重静默丢掉）。所以分享码既是展示核心，
     * 也是身份来源。
     */
    @Test
    fun `不同的分享码不会撞成同一条通知`() {
        val a = PasscodeRules.match(shareUrl)
        val b = PasscodeRules.match("https://pan.baidu.com/s/1zzzzZZZ")
        assertNotNull(a)
        assertNotNull(b)
        val idA = PasscodeLinkIdentity.fromMatch(a.platform?.pkg, a.core, a.target)
        val idB = PasscodeLinkIdentity.fromMatch(b.platform?.pkg, b.core, b.target)
        assertEquals("content:com.baidu.netdisk:1abcdEFG", idA)
        assertNotEquals(idA, idB)
    }

    /** 拿不到「像号」的分享码时不换 scheme —— 否则身份退化成常量（同上的根因）。 */
    @Test
    fun `没有分享码时维持网页目标与 URL 身份`() {
        val m = PasscodeRules.match("https://pan.baidu.com/")
        assertNotNull(m)
        assertEquals("https://pan.baidu.com/", m.target)
        assertTrue(m.alts.isEmpty())
        assertNotNull(PasscodeLinkIdentity.fromMatch(m.platform?.pkg, m.core, m.target))
    }
}
