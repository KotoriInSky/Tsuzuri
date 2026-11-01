package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalMediaRulesTest {
    @Test fun productionRulesUseCompiledCurrentItemPlansAndKeepLegacyFields() {
        val plans = PortalMediaRules.compiled(PortalMediaRules.NOTE_PAGE)
        assertTrue(plans.isNotEmpty())
        val fields = plans.flatMap { it.fields }.toSet()
        PortalMediaRules.imageModels.forEach { model ->
            (PortalMediaRules.modelImageFields + PortalMediaRules.modelVideoFields).forEach { leaf ->
                assertTrue(PortalMediaRules.modelPath(model, leaf) in fields)
            }
        }
        assertEquals(PortalMediaRules.imageModels.size + PortalMediaRules.commentModels.size +
            PortalMediaRules.commentPictureModels.size + PortalMediaRules.playerPrefixes.size, plans.size)
        assertEquals(plans.size, plans.map { it.item }.distinct().size)
        assertTrue(plans.take(PortalMediaRules.imageModels.size).all { plan ->
            plan.reads.any { read -> read.second.steps.any { it.name == "json" } }
        })
    }
    @Test fun unknownPagesAndAppsKeepRomRules() {
        assertNull(PortalMediaRules.forPage("com.xingin.xhs", 9481803, "other.Page"))
        assertNull(PortalMediaRules.forPage("other.app", 9481803, PortalMediaRules.NOTE_PAGE))
    }
    @Test fun semanticMediaPagesTryCandidatesAcrossVersions() {
        val verified = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)
        assertTrue(verified != null)
        assertEquals(verified, PortalMediaRules.forPage("com.xingin.xhs", 9481804, PortalMediaRules.NOTE_PAGE))
        assertTrue(verified.getValue(PortalMediaRules.NOTE_IMAGE).contains("/imageBean/Object/livePhoto/String"))
        assertNull(PortalMediaRules.forPage("com.xingin.xhs", 0, PortalMediaRules.NOTE_PAGE))
    }
    @Test fun mediaPathsUseOnlyFieldsAndPreserveImageCatchType() {
        val rules = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)!!
        assertTrue(rules.isNotEmpty())
        for (path in rules.values) {
            assertEquals(1, path.split(',').count { it == "viewBitmap" })
            assertTrue(path.split(',').filter { it != "viewBitmap" }.all { it.split('/').size % 2 == 0 })
            assertTrue('[' !in path && '(' !in path)
            assertTrue("mDrawable/Bitmap" !in path)
        }
    }
    @Test fun browserAndCommentPagesExposeFullSourcesInsteadOfOnlyViewPixels() {
        val note = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)!!
        assertTrue(PortalMediaRules.BROWSER_IMAGE in note)
        assertTrue(PortalMediaRules.MATRIX_BROWSER_IMAGE in note)
        val comments = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.COMMENT_PAGE)!!
        assertTrue(PortalMediaRules.COMMENT_BROWSER_IMAGE in comments)
        assertTrue(comments.getValue(PortalMediaRules.COMMENT_BROWSER_IMAGE).contains("w/Object/a/Object/h/Object/originalUrl/String"))
        assertTrue(comments.values.any { it.contains("n/Object/mBitmapState/Object/mBitmap/Object") })
    }
    @Test fun commentPhotoAndLiveVideoComeFromSameItem() {
        val plans = PortalMediaRules.compiled(PortalMediaRules.COMMENT_PAGE)
        val model = "w.a.h"
        val image = "https://sns-img-qc.xhscdn.com/full.jpg"
        val video = "https://sns-video-bd.xhscdn.com/live.mp4"
        val selected = PortalRuleMedia.pick(plans, mapOf(
            PortalMediaRules.modelPath(model, "originalUrl") to image,
            PortalMediaRules.modelPath(model, "livePhotoData.videoUrl") to video))!!
        assertEquals(image, selected.source.image)
        assertEquals(video, selected.source.video)
        assertTrue(selected.live)
    }
    @Test fun sourceConfigurationFitsBinderAndHasNoRawModelList() {
        for (page in listOf(PortalMediaRules.NOTE_PAGE, PortalMediaRules.VIDEO_PAGE, PortalMediaRules.COMMENT_PAGE)) {
            val rules = PortalMediaRules.forPage("com.xingin.xhs", 9481803, page)!!
            val size = rules.entries.sumOf { (k, v) -> 2 * (k.length + v.length) }
            assertTrue(size < 900 * 1024, "$page 配置传输大小=$size")
            assertTrue(rules.values.none { it.contains("/items/") })
        }
    }
}
