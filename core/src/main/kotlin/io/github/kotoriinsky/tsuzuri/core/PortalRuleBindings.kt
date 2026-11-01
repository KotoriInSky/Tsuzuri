package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

/** 结构匹配的兼容绑定；只登记来源 APK 与实机通道已证明的等价关系。 */
object PortalRuleBindings {
    const val IMAGE_TYPE = "com.xingin.entities.ImageBean"
    const val VERIFIED_MODEL = "mParent.mParent.mParent.mLayoutParams.mViewHolder.a.controller.d.a.imageBean"

    fun translate(rule: P.Rule): P.Compilation {
        val noteView = rule.views.size == 2 && rule.views.last() == PortalMediaRules.NOTE_IMAGE &&
            rule.views.first() in setOf("com.xingin.matrix.widgets.MatrixHorizontalRecyclerView",
                "com.xingin.matrix.widgets.NoteFeedHorizontalRecyclerView")
        val currentImage = rule.target.requiredType == IMAGE_TYPE &&
            rule.target.steps.size == 2 && rule.target.steps[0].name == "index" &&
            rule.target.steps[0].argument == "\$position" && rule.target.root == "\$dataSource" &&
            rule.target.steps[1] == P.Step("field", "imageBean")
        return if (!rule.batch && rule.pkg == "com.xingin.xhs" && rule.pages == listOf(PortalMediaRules.NOTE_PAGE) &&
            noteView && currentImage) image(rule, VERIFIED_MODEL)
        else PortalRuleCompiler.compile(rule, emptyMap())
    }

    /** VideoInfoV2 的首段 media JSON 已在服务端规范化；接收端无需调用私有 toString。 */
    fun image(rule: P.Rule, model: String): P.Compilation {
        val fields = model.split('.')
        val logical = fields + listOf("livePhoto", "media")
        return PortalRuleCompiler.compile(rule,
            mapOf(rule.target.name to P.Binding(fields, model, IMAGE_TYPE)),
            mapOf(logical to P.Codec.JSON), mapOf(logical to (fields + "livePhoto")))
    }
}
