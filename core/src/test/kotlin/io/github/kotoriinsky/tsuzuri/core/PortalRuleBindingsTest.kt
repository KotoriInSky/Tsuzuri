package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P
import kotlin.test.*

class PortalRuleBindingsTest {
    private fun steps(vararg raw: String) = raw.map(PortalRuleSyntax::parse)
    private fun sample() = P.Rule("sample", "com.xingin.xhs", listOf(PortalMediaRules.NOTE_PAGE),
        listOf("com.xingin.matrix.widgets.MatrixHorizontalRecyclerView", PortalMediaRules.NOTE_IMAGE), emptyList(),
        P.Node("\$item", "\$dataSource", steps("[\$position]", "imageBean"), requiredType = PortalRuleBindings.IMAGE_TYPE),
        listOf(P.Output(P.Kind.PHOTO, steps("url")),
            P.Output(P.Kind.LIVE, steps("livePhoto", "media", "toString()", "[toJson]", "stream", "h264", "[0]", "master_url"))))

    @Test fun noteCurrentItemCompilesWithoutAdapterTraversal() {
        val result = PortalRuleBindings.translate(sample())
        assertTrue(result.issues.isEmpty())
        assertEquals(listOf(PortalMediaRules.modelPath(PortalRuleBindings.VERIFIED_MODEL, "url"),
            PortalMediaRules.modelPath(PortalRuleBindings.VERIFIED_MODEL, "livePhoto")), result.plan!!.fields)
        assertTrue(result.plan.reads.last().second.steps.any { it.name == "json" })
    }
    @Test fun sameAliasOnCommentModelOrUnknownPageDoesNotReuseImageBinding() {
        assertNull(PortalRuleBindings.translate(sample().copy(pages = listOf("Comment.Page"))).plan)
        assertNull(PortalRuleBindings.translate(sample().copy(views = listOf("Comment.Picture"))).plan)
        assertNull(PortalRuleBindings.translate(sample().let { it.copy(target = it.target.copy(requiredType = "Comment.Model")) }).plan)
        assertNull(PortalRuleBindings.translate(sample().copy(pkg = "other.app")).plan)
        val otherIndex = sample().let { it.copy(target = it.target.copy(steps = steps("[\$otherPosition]", "imageBean"))) }
        assertNull(PortalRuleBindings.translate(otherIndex).plan)
    }
}
