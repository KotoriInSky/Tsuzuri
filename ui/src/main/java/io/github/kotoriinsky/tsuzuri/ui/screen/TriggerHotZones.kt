package io.github.kotoriinsky.tsuzuri.ui.screen

import io.github.kotoriinsky.tsuzuri.core.FeaturePreview
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.geometry.FanHotZone
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.core.geometry.SideIndexHotZone

/**
 * 「触发」页要在屏幕上高亮的真实热区（屏幕 px）。算式只来自 core：
 * 扇形 = [FanHotZone]（与 hook `FanGeometry.cornerAt` 同一判据），侧滑 = [SideIndexHotZone]
 * （hook 的 DOWN 判定直接调它）。
 *
 * 屏幕尺寸用当前所在屏幕的整屏尺寸（`rememberScreenShape`），与预览同一口径；
 * 与 SystemUI 那侧（它的 `displayMetrics`）是否逐像素一致要真机核对。
 *
 * 读不到参数（特性没有这几个键）就返回空 —— 不画，而不是按默认值画一块可能不对的区域。
 */
internal fun triggerHotZones(
    spec: FeatureSpec,
    prefs: Prefs,
    screenW: Float,
    screenH: Float,
    density: Float,
): List<ScreenRect> {
    fun slider(suffix: String) = spec.prefs.filterIsInstance<PrefSpec.Slider>()
        .firstOrNull { it.key.endsWith(".$suffix") }?.let { prefs.value(it) }
    fun choice(suffix: String) = spec.prefs.filterIsInstance<PrefSpec.Choice>()
        .firstOrNull { it.key.endsWith(".$suffix") }?.let { prefs.value(it) }
    fun toggle(suffix: String) = spec.prefs.filterIsInstance<PrefSpec.Toggle>()
        .firstOrNull { it.key.endsWith(".$suffix") }?.let { prefs.value(it) }

    return when (spec.presentation.preview) {
        FeaturePreview.STATUS_BAR -> emptyList()
        FeaturePreview.FAN -> {
            val w = slider("hot_width_percent") ?: return emptyList()
            val h = slider("hot_height_percent") ?: return emptyList()
            // 当下朝向被关掉 = 这里起手不会触发，就不画
            val landscape = screenW > screenH
            val orientationOn = if (landscape) toggle("landscape_enabled") else toggle("portrait_enabled")
            if (orientationOn == false) return emptyList()
            val corners = choice("corners") ?: "both_bottom"
            FanHotZone.zones(
                screenW, screenH, w, h,
                allowLeft = corners != "bottom_right",
                allowRight = corners != "bottom_left",
            )
        }
        FeaturePreview.SIDE_INDEX -> {
            val width = slider("trigger_width") ?: return emptyList()
            val side = choice("side") ?: return emptyList()
            SideIndexHotZone.zones(
                screenW, screenH, density, width,
                allowLeft = side != "right",
                allowRight = side != "left",
            )
        }
        FeaturePreview.NONE -> emptyList()
    }
}
