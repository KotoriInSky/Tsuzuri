package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.*

class PortalDouyinRulesTest {
    private val page = "com.ss.android.ugc.aweme.splash.SplashActivity"
    private val plans = PortalMediaRules.compiled(page)
    private fun field(model: String, leaf: String) = PortalRuleSyntax.fields("$model.$leaf".split('.'))
    @Test fun selectsPhotoAndLiveVideoFromPrimaryItemAndUsesLastVideoAddress() {
        val model = PortalDouyinRules.models[2]
        val photo = "https://p3.douyinpic.com/full.jpeg"
        val video = "https://v3.douyinvod.com/live.mp4"
        val selected = PortalRuleMedia.pick(plans, mapOf(
            field(model, "urlList") to listOf("https://p3.douyinpic.com/small.webp", photo),
            field(model, "video.playAddr.urlList") to listOf("https://v1.douyinvod.com/live.mp4", video)))!!
        assertEquals(photo, selected.source.image)
        assertEquals(video, selected.source.video)
        assertTrue(selected.live)
        assertEquals(model, selected.item)
    }
    @Test fun doesNotPairPhotoWithDifferentAncestorVideo() {
        val photo = "https://p3.douyinpic.com/full.jpeg"
        val selected = PortalRuleMedia.pick(plans, mapOf(
            field(PortalDouyinRules.models[1], "urlList") to listOf(photo),
            field(PortalDouyinRules.models[3], "video.playAddr.urlList") to listOf("https://v3.douyinvod.com/other.mp4")))!!
        assertEquals(photo, selected.source.image)
        assertNull(selected.source.video)
        assertFalse(selected.live)
    }
    @Test fun selectsCurrentCommentLivePhotoInsteadOfWorkCoverOrNeighbor() {
        val model = PortalDouyinRules.commentModels[3]
        val photo = "https://p3.douyinpic.com/current.jpeg"
        val video = "https://v3.douyinvod.com/current.mp4"
        val snapshot = "[]"
        val list = listOf(
            mapOf("originUrl" to mapOf("urlList" to listOf("https://p3.douyinpic.com/neighbor.jpeg")),
                "liveVideo" to mapOf("urlList" to listOf("https://v3.douyinvod.com/neighbor.mp4"))),
            mapOf("originUrl" to mapOf("urlList" to listOf(photo)),
                "liveVideo" to mapOf("urlList" to listOf(video))))
        val packet = mapOf(
            PortalRuleSyntax.fields(model.split('.')) to snapshot,
            PortalRuleSyntax.fields(("mParent.".repeat(3) + "d.primary.e").split('.')) to 1,
            field(PortalDouyinRules.models[3], "urlList") to listOf("https://p3.douyinpic.com/work-cover.jpeg"))
        val result = assertNotNull(PortalRuleMedia.pick(plans, packet) { list })
        assertEquals(photo, result.source.image)
        assertEquals(video, result.source.video)
        assertTrue(result.live)
        assertFalse(PortalRuleMedia.pick(plans, packet - PortalRuleSyntax.fields(
            ("mParent.".repeat(3) + "d.primary.e").split('.'))) { list }!!.live)
    }
    @Test fun commentListSingleImageReadsViewHolderCommentWithLivePhoto() {
        val photo = "https://p3.douyinpic.com/list-origin.jpeg"
        val video = "https://v3.douyinvod.com/list-live.mp4"
        val list = listOf(mapOf("originUrl" to mapOf("urlList" to listOf(photo)),
            "liveVideo" to mapOf("urlList" to listOf(video))))
        for (model in PortalDouyinRules.listCommentModels) {
            val packet = mapOf(PortalRuleSyntax.fields(model.split('.')) to "[]")
            val result = assertNotNull(PortalRuleMedia.pick(plans, packet) { list })
            assertEquals(photo, result.source.image)
            assertEquals(video, result.source.video)
            assertTrue(result.live)
        }
        val config = PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 310703, page)!!
        val rule = config.getValue(PortalDouyinRules.LIST_IMAGE)
        assertTrue(rule.contains("mLayoutParams/Object/mViewHolder/Object/b/Object/imageList"))
        assertFalse(rule.contains("primary/Object/f"))
    }
    @Test fun commentEmojiPrefersAnimatedGifOverStaticCover() {
        val gif = "https://p3.douyinpic.com/sticker.gif"
        for (model in PortalDouyinRules.emojiModels) {
            val result = assertNotNull(PortalRuleMedia.pick(plans, mapOf(
                field(model, "animateUrl.urlList") to listOf(gif, "https://p3.douyinpic.com/sticker.webp"),
                field(model, "staticUrl.urlList") to listOf("https://p3.douyinpic.com/static.png"))))
            assertEquals(gif, result.source.image)
            assertFalse(result.live)
            // 真机形态：动图 heif 在前、静态 jpeg 兜底在末尾。
            val heif = "https://p3.douyinpic.com/a~tplv-x-sticker-v2:q86.heif?sig=1"
            val tplv = assertNotNull(PortalRuleMedia.pick(plans, mapOf(field(model, "animateUrl.urlList") to
                listOf(heif, heif.replace("p3", "p9"), "https://p3.douyinpic.com/a~tplv-x-sticker-v2:q86.jpeg?sig=1"))))
            assertEquals(heif, tplv.source.image)
        }
        val rule = PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 310703, page)!!.getValue(PortalDouyinRules.LIST_IMAGE)
        assertTrue(rule.contains("mParent/Object/mParent/Object/mLayoutParams/Object/mViewHolder/Object/b/Object/emoji/Object/animateUrl/Object/urlList/String"))
    }
    @Test fun gifAndNonJpegFilesRetainTheirActualSourceAddress() {
        for (ext in listOf("gif", "png", "jpg")) {
            val url = "https://p3.douyinpic.com/source.$ext"
            assertEquals(url, PortalRuleMedia.pick(plans, mapOf(field(PortalDouyinRules.models[0], "urlList") to listOf(url)))!!.source.image)
        }
    }
    @Test fun allPhotoActivitiesAreRegisteredWithoutChangingSourceAppScope() {
        for (activity in PortalDouyinRules.pages) {
            val config = PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 310703, activity)!!
            assertEquals(PortalDouyinRules.views.toSet(), config.keys)
            assertTrue(config.getValue(PortalDouyinRules.PAGER).contains("primary/Object/f/Object/urlStruct/Object/video/Object/playAddr/Object/urlList/String"))
            // 评论实况播放面 → VideoPinchViewContainer → ConstraintLayout → HorizonViewPager
            assertTrue(config.getValue(PortalDouyinRules.LIVE_SURFACE).contains(
                "mParent/Object/".repeat(3) + "d/Object/primary/Object/f/Object/urlStruct/Object/video/Object/playAddr/Object/urlList/String"))
            // adapter 的 g / b 是对象列表，不能整段下发；ViewHolder 上的 b 是当前 Comment，允许。
            assertTrue(config.values.none { "/g/Object" in it || "d/Object/b/Object/" in it })
            assertTrue(config.entries.sumOf { (k,v) -> 2 * (k.length + v.length) } < 400 * 1024)
        }
        assertNull(PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 0, page))
        assertNull(PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 310703, "unknown.Page"))
        assertNull(PortalMediaRules.forPage(PortalDouyinRules.PACKAGE, 310703,
            "com.ss.android.ugc.aweme.longervideo.landscape.home.activity.LandscapeFeedActivity"))
    }
}
