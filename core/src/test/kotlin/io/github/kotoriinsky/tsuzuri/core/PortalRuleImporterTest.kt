package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.*

class PortalRuleImporterTest {
    private fun node(name: String = "\$item") = mapOf("name" to name, "root" to "\$visibleView", "type" to 0,
        "params" to listOf("model"))
    private fun target() = mapOf("object" to node(), "pic_url" to listOf(mapOf("params" to listOf("url"))),
        "live_photo_url" to emptyList<Any>(), "video_url" to emptyList<Any>(), "video_cover" to emptyList<Any>(), "pic_type" to emptyList<Any>())
    private fun visible() = mapOf("visible_view" to mapOf("params" to listOf("Container", "Picture")),
        "objects" to listOf(node("\$holder")), "target" to target())
    @Test fun importsAllTargetsAndKeepsMultiplePageNamesAndBatchScope() {
        val pages = listOf(mapOf("names" to listOf("PageA", "PageB"), "visible_images" to listOf(visible(), visible()),
            "all_images" to listOf(mapOf("objects" to emptyList<Any>(), "targets" to listOf(target(), target())))))
        val result = PortalRuleImporter.importPages("sample.app", pages)
        assertTrue(result.issues.isEmpty())
        assertEquals(4, result.rules.size)
        assertEquals(4, result.rules.map { it.id }.distinct().size)
        assertTrue(result.rules.all { it.pages == listOf("PageA", "PageB") })
        assertEquals(2, result.rules.count { it.batch })
        assertEquals(listOf("Container", "Picture"), result.rules.first { !it.batch }.views)
    }
    @Test fun malformedTargetAndUnknownMediaKindAreReportedWithoutSilentLoss() {
        val malformed = visible() + ("target" to (target() + ("future_media" to emptyList<Any>())))
        val result = PortalRuleImporter.importPages("sample.app", listOf(mapOf("name" to "Page",
            "visible_images" to listOf(malformed, mapOf("target" to "invalid")))))
        assertTrue(result.issues.any { it.operation == "future_media" })
        assertTrue(result.issues.any { it.operation == "target" })
    }
    @Test fun transformerAndNonFieldOperationsArePreserved() {
        val output = target() + ("live_photo_url" to listOf(mapOf("params" to listOf("media", "toString()", "[toJson]", "stream", "h265", "[0]", "master_url"))))
        val page = mapOf("name" to "Page", "visible_images" to listOf(visible() + ("target" to output)),
            "transformers" to mapOf("\$codec" to mapOf("params" to listOf("codec", "{_regexMatch}h264|hevc"))))
        val result = PortalRuleImporter.importPages("sample.app", listOf(page))
        assertTrue(result.issues.isEmpty())
        assertEquals(7, result.rules.single().outputs.last().steps.size)
        assertEquals("matches", result.rules.single().transformers.getValue("\$codec").last().name)
    }
    @Test fun malformedSelectorsAndTransformersNeverDisappearFromDiagnostics() {
        val page = mapOf("name" to "Page", "transformers" to listOf("invalid"),
            "visible_images" to listOf(visible() + ("visible_view" to mapOf("params" to listOf("Picture"),
                "future_selector" to true))))
        val result = PortalRuleImporter.importPages("sample.app", listOf(page))
        assertTrue(result.issues.any { it.operation == "transformers" })
        assertTrue(result.issues.any { it.operation == "future_selector" })
        assertNull(PortalRuleCompiler.compile(result.rules.single(), emptyMap()).plan)
    }
}
