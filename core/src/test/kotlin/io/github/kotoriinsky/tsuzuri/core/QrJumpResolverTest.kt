package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrJumpResolverTest {

    @Test fun blankInputReturnsNull() {
        assertNull(QrJumpResolver.resolve(null))
        assertNull(QrJumpResolver.resolve("   \n\t "))
    }

    @Test fun bilibiliUrlUsesPlatformRuleAndCleansTrackingParams() {
        val action = assertNotNull(
            QrJumpResolver.resolve("https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.1007"),
        )
        assertEquals(QrJumpResolver.Kind.PLATFORM, action.kind)
        assertEquals("打开 B站", action.title)
        assertEquals("BV1xx411c7mD", action.subtitle)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", action.primaryTarget)
        assertTrue(action.canLaunch)
        assertEquals("tv.danmaku.bili", action.platformPkg)
    }

    @Test fun baiduPanQrExtractsShareDeepLink() {
        val action = assertNotNull(
            QrJumpResolver.resolve("https://pan.baidu.com/s/1abcdEFG?pwd=9k2m"),
        )
        assertEquals(QrJumpResolver.Kind.PLATFORM, action.kind)
        assertEquals("com.baidu.netdisk", action.platformPkg)
        assertTrue(action.title.startsWith("打开 "))
        assertEquals("bdnetdisk://n/action.SHARE_LINK?surl=abcdEFG&pwd=9k2m", action.primaryTarget)
        assertTrue(action.alts.any { it.startsWith("https://pan.baidu.com/") })
    }

    @Test fun genericWebUrlResolvesAsPlatformWebLink() {
        val action = assertNotNull(
            QrJumpResolver.resolve("https://example.com/docs?utm_source=qr&id=42"),
        )
        assertEquals(QrJumpResolver.Kind.PLATFORM, action.kind)
        assertTrue(action.canLaunch)
        assertEquals("https://example.com/docs?id=42", action.primaryTarget)
        assertFalse(action.primaryTarget!!.contains("utm_source"))
    }

    @Test fun wechatAndAlipayQrSchemesResolveSafely() {
        val wx = assertNotNull(QrJumpResolver.resolve("weixin://dl/business/?t=abc123"))
        assertEquals(QrJumpResolver.Kind.URI, wx.kind)
        assertEquals("微信", wx.title)
        assertEquals("打开微信", wx.actionLabel)
        assertEquals("weixin://dl/business/?t=abc123", wx.primaryTarget)

        val ali = assertNotNull(QrJumpResolver.resolve("alipays://platformapi/startapp?appId=20000056"))
        assertEquals(QrJumpResolver.Kind.URI, ali.kind)
        assertEquals("支付宝", ali.title)
        assertTrue(ali.canLaunch)
    }

    @Test fun unsafeSchemesDegradeToPlainText() {
        val js = assertNotNull(QrJumpResolver.resolve("javascript:alert(1)"))
        assertEquals(QrJumpResolver.Kind.TEXT, js.kind)
        assertFalse(js.canLaunch)

        val file = assertNotNull(QrJumpResolver.resolve("file:///etc/hosts"))
        assertEquals(QrJumpResolver.Kind.TEXT, file.kind)
        assertFalse(file.canLaunch)
    }

    @Test fun wifiQrParsesEscapedSsidAndPassword() {
        val wifi = assertNotNull(
            QrJumpResolver.resolve("""WIFI:T:WPA;S:My\;Home\:5G;P:pass\\123\;4;;"""),
        )
        assertEquals(QrJumpResolver.Kind.WIFI, wifi.kind)
        assertEquals("Wi-Fi：My;Home:5G", wifi.title)
        assertEquals("密码：pass\\123;4", wifi.subtitle)
        assertEquals("pass\\123;4", wifi.copyText)
        assertEquals("复制密码", wifi.actionLabel)
        assertFalse(wifi.canLaunch)
    }

    @Test fun openWifiCopiesSsidWhenPasswordEmpty() {
        val wifi = assertNotNull(QrJumpResolver.resolve("WIFI:T:nopass;S:CafeGuest;;"))
        assertEquals(QrJumpResolver.Kind.WIFI, wifi.kind)
        assertEquals("Wi-Fi：CafeGuest", wifi.title)
        assertEquals("无密码（开放网络）", wifi.subtitle)
        assertEquals("CafeGuest", wifi.copyText)
        assertEquals("复制名称", wifi.actionLabel)
    }

    @Test fun plainTextFallsBackToCopyAction() {
        val text = assertNotNull(QrJumpResolver.resolve("取餐码 A042\n请到 2 号窗口领取"))
        assertEquals(QrJumpResolver.Kind.TEXT, text.kind)
        assertEquals("二维码内容", text.title)
        assertEquals("取餐码 A042 请到 2 号窗口领取", text.subtitle)
        assertFalse(text.canLaunch)
        assertEquals("复制", text.actionLabel)
    }
}
