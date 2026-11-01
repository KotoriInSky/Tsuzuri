package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * M3 滑杆轨道上的关键点（默认值、推荐范围两端），叠画在 Slider 上方（2026-10-22 用户：
 * 「在滑杆轨道内部快速确认」）。MiuiX 那边用库自带的 `keyPoints`，这里给 M3 画同样的点，
 * 两套风格观感一致。
 *
 * [modifier] 应当与 Slider 同大小（`matchParentSize`）；[trackInset] 是轨道两端让出的半个滑块宽。
 */
@Composable
internal fun SliderKeyPoints(
    range: ClosedFloatingPointRange<Float>,
    points: List<Float>,
    color: Color,
    trackInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: return
    Canvas(modifier) {
        val inset = trackInset.toPx()
        val w = size.width - inset * 2
        for (p in points) {
            val f = ((p - range.start) / span).coerceIn(0f, 1f)
            drawCircle(color, radius = 2.dp.toPx(), center = Offset(inset + f * w, size.height / 2f))
        }
    }
}
