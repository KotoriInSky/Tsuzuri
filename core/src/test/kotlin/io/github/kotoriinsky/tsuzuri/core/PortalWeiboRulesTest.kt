package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.*

class PortalWeiboRulesTest {
    private fun field(model: String, leaf: String) = PortalRuleSyntax.fields("$model.$leaf".split('.'))
    @Test fun gifUsesOriginalFileAndIgnoresItsPlaybackVideo() {
        val model = "mParent.mParent.r.picInfo"
        val original = "https://wx1.sinaimg.cn/large/probe.gif"
        val result = PortalRuleMedia.pick(PortalWeiboRules.compiled(PortalWeiboRules.PREVIEW_PAGE), mapOf(
            field(model, "type") to "gif", field(model, "original.url") to original,
            field(model, "video") to "https://f.video.weibocdn.com/gif-playback.mp4"))!!
        assertEquals(PortalMediaSelection.Source(original, null), result.source)
        assertFalse(result.live)
    }
    @Test fun livePhotoRetainsOriginalImageAndSameItemVideo() {
        val model = "mParent.m.picInfo"
        val photo = "https://wx1.sinaimg.cn/large/probe.jpg"
        val video = "https://f.video.weibocdn.com/live.mp4"
        val result = PortalRuleMedia.pick(PortalWeiboRules.compiled(PortalWeiboRules.PREVIEW_PAGE), mapOf(
            field(model, "type") to "livephoto", field(model, "original.url") to photo,
            field(model, "video") to video))!!
        assertEquals(PortalMediaSelection.Source(photo, video), result.source)
        assertTrue(result.live)
    }
    @Test fun previewViewsReceiveFullOriginalRatherThanOnlyBitmaps() {
        val rules = PortalMediaRules.forPage(PortalWeiboRules.PACKAGE, 1, PortalWeiboRules.PREVIEW_PAGE)!!
        assertTrue(rules.getValue(PortalWeiboRules.LIVE_VIEW).contains("m/Object/picInfo/Object/original/Object/url/String"))
        assertTrue(rules.getValue(PortalWeiboRules.PHOTO_VIEW).contains("r/Object/picInfo/Object/original/Object/url/String"))
        assertTrue(rules.values.all { it.split(',').count { field -> field == "viewBitmap" } == 1 })
        assertTrue(rules.entries.sumOf { (k,v) -> 2 * (k.length + v.length) } < 150 * 1024)
    }
}
