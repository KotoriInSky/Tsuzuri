package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P
import kotlin.test.*

class PortalRuleMediaTest {
    private val photo = "https://img.xhscdn.com/a.gif?sign=a%2Fb"
    private val video = "https://video.xhscdn.com/a.mp4?sign=a%2Fb"
    @Test fun compilerMaintainsSameItemPairingAndStopsAtNearestStaticPhoto() {
        val plans = PortalMediaRules.compiled(PortalMediaRules.NOTE_PAGE)
        val near = PortalMediaRules.imageModels[0]
        val far = PortalMediaRules.imageModels[1]
        val values = mapOf(PortalMediaRules.modelPath(near, "url_anim") to photo,
            PortalMediaRules.modelPath(far, "original") to "$photo-other",
            PortalMediaRules.modelPath(far, "livePhotoUrl") to video)
        val result = PortalRuleMedia.pick(plans, values)!!
        assertEquals(PortalMediaSelection.Source(photo, null), result.source)
        assertFalse(result.live)
    }
    @Test fun allNativeCurrentItemChoicesAgreeWithExistingSelector() {
        val plans = PortalMediaRules.compiled(PortalMediaRules.NOTE_PAGE)
        for (model in PortalMediaRules.imageModels) {
            val values = mapOf(PortalMediaRules.modelPath(model, "original") to photo,
                PortalMediaRules.modelPath(model, "livePhotoUrl") to video)
            val result = PortalRuleMedia.pick(plans, values)!!
            assertEquals(PortalMediaSelection.pick(values), result.source)
            assertTrue(result.live)
        }
    }
    @Test fun listAndJsonSnapshotsAreEvaluatedBeforeSourceSelection() {
        val root = P.Node("\$item", "\$visibleView", emptyList())
        val rule = P.Rule("current", "sample", listOf("Detail"), listOf("Image"), emptyList(), root,
            listOf(P.Output(P.Kind.PHOTO, listOf("photos", "[last]").map(PortalRuleSyntax::parse)),
                P.Output(P.Kind.LIVE, listOf("videoJson", "[toJson]", "url").map(PortalRuleSyntax::parse))))
        val plan = PortalRuleCompiler.compile(rule, mapOf("\$item" to P.Binding(listOf("model"), "selected")),
            mapOf(listOf("model", "photos") to P.Codec.STRING_LIST, listOf("model", "videoJson") to P.Codec.JSON)).plan!!
        val values = mapOf(plan.reads[0].second.key to listOf("https://img.xhscdn.com/cover", photo),
            plan.reads[1].second.key to "{probe}")
        val result = PortalRuleMedia.pick(listOf(plan), values) { mapOf("url" to video) }!!
        assertEquals(PortalMediaSelection.Source(photo, video), result.source)
        assertTrue(result.live)
        assertEquals("selected", result.item)
    }
    @Test fun unknownUriAndNonStringListCannotBecomeDownloads() {
        val plans = PortalMediaRules.compiled(PortalMediaRules.NOTE_PAGE)
        val model = PortalMediaRules.imageModels[0]
        assertNull(PortalRuleMedia.pick(plans, mapOf(PortalMediaRules.modelPath(model, "original") to "file:///private/photo.gif")))
        assertNull(PortalRuleMedia.pick(plans, mapOf(PortalMediaRules.modelPath(model, "original") to Any())))
    }
}
