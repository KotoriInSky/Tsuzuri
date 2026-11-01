package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

/** 自行绑定已核验的当前项结构；不把厂商规则 JSON 打包进模块。 */
internal object PortalMediaRulePlans {
    private fun steps(path: String) = path.split('.').map { P.Step("field", it) }
    fun create(page: String): List<P.Plan> {
        val photo = PortalMediaRules.imageModels.mapIndexed { index, model ->
            val outputs = PortalMediaRules.modelImageFields.map { P.Output(P.Kind.PHOTO, steps(it)) } +
                listOf("h264", "h265").map { codec -> P.Output(P.Kind.LIVE,
                    listOf("livePhoto", "media", "toString()", "[toJson]", "stream", codec, "[0]", "master_url")
                        .map(PortalRuleSyntax::parse)) } +
                PortalMediaRules.modelVideoFields.map { P.Output(P.Kind.LIVE, steps(it)) }
            val rule = P.Rule("photo$index", "com.xingin.xhs", listOf(page), emptyList(), emptyList(),
                P.Node("\$current", "\$visibleView", emptyList()), outputs)
            checkNotNull(PortalRuleBindings.image(rule, model).plan)
        }
        val players = PortalMediaRules.playerPrefixes.mapIndexed { index, model ->
            compile(page, "player$index", model, PortalMediaRules.playerVideoFields.map { P.Output(P.Kind.VIDEO, steps(it)) } +
                P.Output(P.Kind.COVER, steps(PortalMediaRules.playerCoverField)))
        }
        val comments = PortalMediaRules.commentModels.mapIndexed { index, model ->
            compile(page, "comment$index", model,
                listOf("animatedImageUrl", "originalUrl", "url").map { P.Output(P.Kind.PHOTO, steps(it)) } +
                    listOf("livePhotoData.videoUrl", "livePhotoData.videoInfo").map { P.Output(P.Kind.LIVE, steps(it)) })
        }
        val commentPictures = PortalMediaRules.commentPictureModels.mapIndexed { index, model ->
            compile(page, "commentPicture$index", model,
                listOf("animatedUrl", "originalPicUrl", "picUrl").map { P.Output(P.Kind.PHOTO, steps(it)) } +
                    P.Output(P.Kind.LIVE, steps("videoInfo")))
        }
        return if (page == PortalMediaRules.COMMENT_PAGE) comments + commentPictures + players
        else photo + comments + commentPictures + players
    }
    private fun compile(page: String, id: String, model: String, outputs: List<P.Output>): P.Plan {
        val target = P.Node("\$current", "\$visibleView", emptyList())
        val rule = P.Rule(id, "com.xingin.xhs", listOf(page), emptyList(), emptyList(), target, outputs)
        val result = PortalRuleCompiler.compile(rule, mapOf(target.name to P.Binding(model.split('.'), model)))
        check(result.issues.isEmpty()) { "当前项规则编译失败：${result.issues}" }
        return checkNotNull(result.plan)
    }
}
