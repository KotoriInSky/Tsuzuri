package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.runtime.Composable
import io.github.kotoriinsky.tsuzuri.core.FeaturePreview
import io.github.kotoriinsky.tsuzuri.core.PrefCategories
import io.github.kotoriinsky.tsuzuri.core.PrefCategory
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.PrefGroup
import io.github.kotoriinsky.tsuzuri.core.Prefs

/**
 * 预览台的**唯一分派处**：分组页与类别页都调它。
 *
 * 为什么必须只有一处：分派规则（哪个特性、哪一组才画预览）有两个使用方 ——
 * 预览本身，以及参数行的「不影响预览」标注。两份判据一旦漂移，就会出现
 * 「给本该有预览的页面标注『不影响预览』」——那是在宣称一件不成立的事，
 * 比不标注更糟。
 *
 * 2026-10-17 类别页落地时抽出来：原来分组页里有一份私有实现，类别页差点再抄一份。
 *
 * @param groups 这一屏里**实际要渲染的分组**。类别页传整个类别的分组
 *               （预览要同时反映图标直径与排布这类跨组参数），分组页传单个分组。
 */
@Composable
internal fun FeaturePreviewBlock(spec: FeatureSpec, prefs: Prefs, groups: List<PrefGroup>) {
    when (spec.presentation.preview) {
        // 读值用**整个特性**的参数表：扇形排列那一组的图标直径声明在「外观」类里
        FeaturePreview.FAN -> FanPreviewSection(
            featureKey = spec.key,
            groupSpecs = groups.flatMap { it.prefs },
            featureSpecs = spec.prefs,
            prefs = prefs,
        )
        FeaturePreview.SIDE_INDEX -> if (groups.any { it.id in spec.presentation.previewGroups }) {
            // 「触发」类别里把真实起手热区画出来（那一页调的就是它）；其它类别不画，免得盖住面板
            SideIndexPreviewSection(spec, prefs, showHotZones = groups.any { it.isTriggerGroup() })
        }
        FeaturePreview.STATUS_BAR -> StatusBarPreviewFor(spec, prefs)
        FeaturePreview.NONE -> Unit
    }
}

/** 完美状态栏的预览（详情页内联时也从这里画）。只画开启后的样子，不随参数变化。 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun StatusBarPreviewFor(spec: FeatureSpec, prefs: Prefs) {
    CleanStatusBarPreview()
}

/** 分组属于「触发」类别（映射表唯一来源是 [PrefCategories]）。 */
internal fun PrefGroup.isTriggerGroup(): Boolean =
    PrefCategories.declarationOf(id).category == PrefCategory.TRIGGER

/** 这一屏会不会画出预览（`null` = 不会）。判据与 [FeaturePreviewBlock] 严格一致。 */
internal fun previewKindOf(spec: FeatureSpec, groups: List<PrefGroup>): FeaturePreview? =
    when (spec.presentation.preview) {
        FeaturePreview.FAN -> FeaturePreview.FAN
        FeaturePreview.SIDE_INDEX -> if (groups.any { it.id in spec.presentation.previewGroups }) {
            FeaturePreview.SIDE_INDEX
        } else {
            null
        }
        FeaturePreview.STATUS_BAR -> FeaturePreview.STATUS_BAR
        FeaturePreview.NONE -> null
    }
