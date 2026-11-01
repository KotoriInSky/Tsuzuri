package io.github.kotoriinsky.tsuzuri.core

/** 展示状态与运行状态分离：隐藏搁置入口不能卸载已经启用的 hook。 */
data class FeaturePresentation(
    val listed: Boolean = true,
    val preview: FeaturePreview = FeaturePreview.NONE,
    val previewGroups: Set<String> = emptySet(),
    val iconToolsGroup: String? = null,
    /**
     * 在详情页只占**一行入口**的分组：点进去才是这一组的参数（第三层，`Dest.FeatureSection`）。
     * 给「一组同类的细项」用（例如传送门胶囊的八个「不显示…」），免得把详情页撑长。
     */
    val drillGroups: Set<String> = emptySet(),
)

enum class FeaturePreview { NONE, FAN, SIDE_INDEX, STATUS_BAR }

val FeatureSpec.isListedInSettings: Boolean
    get() = presentation.listed && maturity.isUserToggleable

val Catalog.settingsFeatures: List<FeatureSpec>
    get() = features.filter { it.isListedInSettings }
