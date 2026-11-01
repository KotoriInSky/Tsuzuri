package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Binding
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Codec
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Kind
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Node
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Output
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Rule
import kotlin.test.*

class PortalRuleTranslationTest {
    private fun steps(vararg values: String) = values.map(PortalRuleSyntax::parse)
    private fun rule(vararg outputs: Output) = Rule("current", "sample.app", listOf("Detail"), listOf("PictureView"),
        emptyList(), Node("\$item", "\$visibleView", steps("getAdapter()", "items", "[\$position]")), outputs.toList())
    private val binding = mapOf("\$item" to Binding(listOf("holder", "current"), "selected"))

    @Test fun currentItemBindingReplacesAdapterTraversalWithoutCallingMethods() {
        val result = PortalRuleCompiler.compile(rule(Output(Kind.PHOTO, steps("original", "url"))), binding)
        assertTrue(result.issues.isEmpty())
        assertEquals("holder/Object/current/Object/original/Object/url/String", result.plan!!.fields.single())
        assertEquals("selected", result.plan.item)
    }
    @Test fun missingBindingReportsMethodInsteadOfDroppingIt() {
        val result = PortalRuleCompiler.compile(rule(Output(Kind.PHOTO, steps("url"))), emptyMap())
        assertNull(result.plan)
        assertTrue(result.issues.any { it.operation == "getAdapter" })
    }
    @Test fun superclassDirectiveCannotAccidentallyReadShadowingField() {
        val result = PortalRuleCompiler.compile(rule(Output(Kind.PHOTO, steps("[super]", "b", "original", "url"))), binding)
        assertNull(result.plan)
        assertTrue(result.issues.any { it.operation == "super" })
    }
    @Test fun listSelectionRequiresDeclaredTransferableSnapshot() {
        val source = listOf("holder", "current", "urls")
        val sample = rule(Output(Kind.PHOTO, steps("urls", "[last]")))
        assertNull(PortalRuleCompiler.compile(sample, binding).plan)
        val result = PortalRuleCompiler.compile(sample, binding, mapOf(source to Codec.STRING_LIST))
        assertEquals("holder/Object/current/Object/urls/String", result.plan!!.fields.single())
        assertEquals(steps("[last]"), result.plan.reads.single().second.steps)
    }
    @Test fun jsonProcessingStaysOnReceiverAndSignedAddressIsUnchanged() {
        val sample = rule(Output(Kind.LIVE, steps("videoInfoJson", "[toJson]", "stream", "h265", "[0]", "master_url")))
        val result = PortalRuleCompiler.compile(sample, binding,
            mapOf(listOf("holder", "current", "videoInfoJson") to Codec.JSON))
        assertTrue(result.issues.isEmpty())
        assertEquals("holder/Object/current/Object/videoInfoJson/String", result.plan!!.fields.single())
        val address = "https://video.xhscdn.com/a.mp4?sign=a%2Fb"
        val tree = mapOf("stream" to mapOf("h265" to listOf(mapOf("master_url" to address))))
        assertEquals(address, PortalRuleValues.evaluate("{probe}", result.plan.reads.single().second.steps,
            decodeJson = { tree }))
    }
    @Test fun unknownInstructionNeverBecomesAFieldName() {
        val result = PortalRuleCompiler.compile(rule(Output(Kind.PHOTO, steps("{_newOperation}"))), binding)
        assertNull(result.plan)
        assertTrue(result.issues.isNotEmpty())
    }
    @Test fun batchRulesRemainExplicitlyUnsupportedForCurrentItemCapture() {
        val sample = rule(Output(Kind.PHOTO, steps("url"))).copy(batch = true)
        assertNull(PortalRuleCompiler.compile(sample, binding).plan)
    }
    @Test fun malformedFieldCannotInjectAnotherExtractorInstruction() {
        assertFailsWith<IllegalArgumentException> { PortalRuleSyntax.fields(listOf("url,conversionBitmap")) }
        assertFailsWith<IllegalArgumentException> { PortalRuleSyntax.fields(listOf("getBitmap()")) }
    }
    @Test fun patternUsesFirstMatchAndLastUsesFinalElement() {
        val urls = listOf("cover.png", "a.jpeg?sign=1", "b.jpeg")
        assertEquals(urls[1], PortalRuleValues.evaluate(urls, steps("[pattern].*\\.jpeg.*")))
        assertEquals(urls[2], PortalRuleValues.evaluate(urls, steps("[last]")))
    }
    @Test fun transformedListKeepsEmptyPositionsBeforeBooleanIndexSelection() {
        val items = listOf(mapOf("codec" to "av1"), emptyMap(), mapOf("codec" to "h264"))
        assertEquals(2, PortalRuleValues.evaluate(items, steps("{_transformList}\$codec", "{_filterList}"),
            transformers = mapOf("\$codec" to steps("codec", "{_regexMatch}h264|hevc"))))
        assertEquals(-1, PortalRuleValues.evaluate(listOf(false, null), steps("{_filterList}")))
    }
    @Test fun variableIndexModuloAndRegexSplitFollowListSemantics() {
        assertEquals(1, PortalRuleValues.evaluate(7, steps("{_mod}\$count"), mapOf("\$count" to 3)))
        assertNull(PortalRuleValues.evaluate(7, steps("{_mod}\$count"), mapOf("\$count" to 0)))
        assertEquals("b", PortalRuleValues.evaluate(listOf("a", "b"), steps("[\$index]"), mapOf("\$index" to 1)))
        assertEquals("a", PortalRuleValues.evaluate("a;b;", steps("[split];", "[0]")))
        assertEquals(false, PortalRuleValues.evaluate("xh264x", steps("{_regexMatch}h264")))
    }
    @Test fun receiverNeverInvokesForeignMethodsAndBoundsInvalidIndices() {
        assertNull(PortalRuleValues.evaluate(listOf("a"), steps("[-1]")))
        assertNull(PortalRuleValues.evaluate(listOf("a"), steps("[4]")))
        assertNull(PortalRuleValues.evaluate(Any(), steps("getPlayer()")))
        assertEquals(2, PortalRuleValues.evaluate(listOf("a", "b"), steps("size()")))
        assertEquals("value", PortalRuleValues.evaluate(mapOf("gif_video" to "value"), steps("{gif_video}")))
    }
    @Test fun verifiedMediaSnapshotReplacesPrivateObjectToStringOnSource() {
        val sample = rule(Output(Kind.LIVE, steps("livePhoto", "media", "toString()", "[toJson]", "stream", "h264", "[0]", "master_url")))
        val logical = listOf("holder", "current", "livePhoto", "media")
        val wire = listOf("holder", "current", "livePhoto")
        val result = PortalRuleCompiler.compile(sample, binding, mapOf(logical to Codec.JSON), mapOf(logical to wire))
        assertEquals("holder/Object/current/Object/livePhoto/String", result.plan!!.fields.single())
        assertEquals(steps("toString()", "[toJson]", "stream", "h264", "[0]", "master_url"), result.plan.reads.single().second.steps)
    }
    @Test fun wireReplacementRequiresSnapshotProofAndValidFields() {
        val logical = listOf("holder", "current", "url")
        val sample = rule(Output(Kind.PHOTO, steps("url")))
        val unproved = PortalRuleCompiler.compile(sample, binding, wireFields = mapOf(logical to listOf("other")))
        assertNull(unproved.plan)
        assertTrue(unproved.issues.any { it.operation == "snapshot" })
        val invalid = PortalRuleCompiler.compile(sample, binding, mapOf(logical to Codec.TEXT),
            mapOf(logical to listOf("url,viewBitmap")))
        assertNull(invalid.plan)
        assertTrue(invalid.issues.any { it.operation == "field" })
    }
    @Test fun sourceTypeAndVisibilityCannotBeSilentlyDiscarded() {
        val sample = rule(Output(Kind.PHOTO, steps("url")))
        val typed = sample.copy(target = sample.target.copy(requiredType = "sample.Image"))
        assertNull(PortalRuleCompiler.compile(typed, binding).plan)
        val visible = sample.copy(target = sample.target.copy(visible = true))
        assertNull(PortalRuleCompiler.compile(visible, binding).plan)
    }
    @Test fun nestedTransformersMustHaveResolvedDependencies() {
        val sample = rule(Output(Kind.PHOTO, steps("urls", "{_transformList}\$outer", "[0]"))).copy(
            transformers = mapOf("\$outer" to steps("{_transformList}\$missing")))
        val result = PortalRuleCompiler.compile(sample, binding,
            mapOf(listOf("holder", "current", "urls") to Codec.STRING_LIST))
        assertNull(result.plan)
        assertTrue(result.issues.any { it.operation == "transform" })
    }
}
