package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalMediaContractTest {
    @Test fun detectsOriginalBytesInsteadOfTrustingUrlSuffix() {
        assertEquals(PortalMediaContract.Format.GIF, PortalMediaContract.sniff("GIF89a".toByteArray()))
        assertEquals(PortalMediaContract.Format.JPEG, PortalMediaContract.sniff(byteArrayOf(-1, -40, -1, -32)))
        assertEquals(PortalMediaContract.Format.PNG, PortalMediaContract.sniff(byteArrayOf(-119,80,78,71,13,10,26,10)))
        assertEquals(PortalMediaContract.Format.WEBP, PortalMediaContract.sniff("RIFFxxxxWEBPVP8 ".toByteArray()))
        assertEquals(PortalMediaContract.Format.MP4, PortalMediaContract.sniff(byteArrayOf(0,0,0,24) + "ftypmp42".toByteArray() + ByteArray(4)))
        assertNull(PortalMediaContract.sniff("<html>expired URL</html>".toByteArray()))
    }

    @Test fun distinguishesHeifAndAvifFromVideoUsingCompatibleBrands() {
        fun box(major: String, compatible: String) = byteArrayOf(0,0,0,20) +
            "ftyp$major".toByteArray() + ByteArray(4) + compatible.toByteArray()
        assertEquals(PortalMediaContract.Format.HEIC, PortalMediaContract.sniff(box("mif1", "heic")))
        assertEquals(PortalMediaContract.Format.HEIF, PortalMediaContract.sniff(box("mif1", "miaf")))
        assertEquals(PortalMediaContract.Format.AVIF, PortalMediaContract.sniff(box("mif1", "avif")))
        assertEquals(PortalMediaContract.Format.MP4, PortalMediaContract.sniff(box("iso6", "mp42")))
        assertNull(PortalMediaContract.sniff(byteArrayOf(0,0,0,8) + "ftypheic".toByteArray()))
    }

    @Test fun tokensRejectTraversalAndUnsupportedFormats() {
        assertTrue(PortalMediaContract.safeToken("0123456789abcdef0123456789abcdef.gif"))
        assertFalse(PortalMediaContract.safeToken("../0123456789abcdef0123456789abcdef.mp4"))
        assertFalse(PortalMediaContract.safeToken("short.mp4"))
        assertFalse(PortalMediaContract.safeToken("0123456789abcdef0123456789abcdef.html"))
        assertEquals("video/mp4", PortalMediaContract.tokenMime("0123456789abcdef0123456789abcdef.mp4"))
    }

    @Test fun editorWriteCapabilityCannotChangeShareOriginal() {
        val editor = listOf(PortalTempImageContract.MEDIA_EDITOR_PACKAGE)
        assertTrue(PortalMediaContract.isDirectEditWriter(PortalMediaContract.EDIT_SEGMENT, editor))
        assertFalse(PortalMediaContract.isDirectEditWriter(PortalMediaContract.SEGMENT, editor))
        assertFalse(PortalMediaContract.isDirectEditWriter(PortalMediaContract.EDIT_SEGMENT, listOf("com.example.receiver")))
        assertFalse(PortalMediaContract.isDirectEditWriter("../edit", editor))
        assertTrue(PortalMediaContract.safeSegment(PortalMediaContract.SEGMENT))
        assertTrue(PortalMediaContract.safeSegment(PortalMediaContract.EDIT_SEGMENT))
        assertFalse(PortalMediaContract.safeSegment("../edit"))
    }

    @Test fun downloadsAcceptRemoteMediaAndRejectInvalidSources() {
        assertTrue(PortalMediaContract.allowedUrl("https://sns-video-bd.xhscdn.com/a?sign=x"))
        assertTrue(PortalMediaContract.allowedUrl("https://sns-img-hw.xhscdn.com/image"))
        assertTrue(PortalMediaContract.allowedUrl("https://wx1.sinaimg.cn/large/a.gif"))
        assertTrue(PortalMediaContract.allowedUrl("https://v3.douyinvod.com/video/tos/a?x-expires=1"))
        assertFalse(PortalMediaContract.allowedUrl("https://douyinvod.com.evil.invalid/a"))
        assertFalse(PortalMediaContract.allowedUrl("http://127.0.0.1/a.mp4"))
        assertFalse(PortalMediaContract.allowedUrl("https://xhscdn.com.evil.invalid/a"))
        assertFalse(PortalMediaContract.allowedUrl("https://user:pass@sns-video-bd.xhscdn.com/a"))
        assertFalse(PortalMediaContract.allowedUrl("file:///data/user/0/private"))
    }

    @Test fun standardMediaDoesNotRequireAppSpecificCdn() {
        val image = "https://images.example.org/media/a%2Fb?format=gif&signature=a%2B%3D"
        assertEquals(image, PortalMediaContract.imageSource(image))
        assertFalse(PortalMediaContract.allowedUrl("https://user:pass@images.example.org/media"))
        assertFalse(PortalMediaContract.allowedUrl("https://images.example.org:8443/media"))
        assertFalse(PortalMediaContract.allowedUrl("http://localhost/media"))
        assertFalse(PortalMediaContract.allowedUrl("http://192.168.1.2/media"))
        assertFalse(PortalMediaContract.allowedUrl("http://[::1]/media"))
    }

    @Test fun onlyCurrentSessionMayPublishDownloadedMedia() {
        val session = PortalMediaContract.Session()
        val first = session.begin()
        val second = session.begin()
        assertFalse(session.isCurrent(first))
        assertTrue(session.isCurrent(second))
        session.clear()
        assertFalse(session.isCurrent(second))
    }
    @Test fun upgradesCdnWithoutReencodingSignedAddress() {
        val source = "http://sns-na-i1.xhscdn.com:80/a%2Fb?sign=a%2Fb%2B%3D&v=1"
        assertEquals("https://sns-na-i1.xhscdn.com/a%2Fb?sign=a%2Fb%2B%3D&v=1", PortalMediaContract.requestUrl(source))
        val secure = "https://sns-na-i1.xhscdn.com/a?sign=x"
        assertEquals(secure, PortalMediaContract.requestUrl(secure))
        assertNull(PortalMediaContract.requestUrl("http://127.0.0.1/private"))
    }
}
