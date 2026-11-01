package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenShape
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 两个预览画布（扇形 / 侧滑）共用的**屏幕外形**画法：真实四角圆角、挖孔、壁纸铺满裁切。
 *
 * 形状来自 [ScreenShape]（当前所在屏幕的真值，见 `state/DeviceScreen`）；
 * 没有它（读不到）时圆角按 [ScreenShape.FALLBACK_CORNER_RATIO] 估，与改造前的观感一致。
 */
internal object ScreenFrame {

    /**
     * 手机轮廓：照 HyperOS 设置「自由窗口」页的示意图 —— 一圈粗的浅蓝灰描边，不铺底、不画挖孔
     * （用户 2026-10-25 给的截图）。两张预览共用，改这里两边一起变。
     */
    val FRAME_WIDTH = 5.dp
    fun frameColor(surface: androidx.compose.ui.graphics.Color) =
        if (surface.luminance() > 0.5f) androidx.compose.ui.graphics.Color(0xFFC3C9DE) else androidx.compose.ui.graphics.Color(0xFF4A5069)

    /** 画布上的四角半径（px）：屏幕真值 × 缩放；没有真值按可见区短边估。顺序：左上、右上、右下、左下。 */
    fun radii(screen: ScreenShape?, scale: Float, visibleW: Float, visibleH: Float): FloatArray =
        screen?.corners?.let { c -> FloatArray(4) { c[it] * scale } }
            ?: FloatArray(4) { min(visibleW, visibleH) * ScreenShape.FALLBACK_CORNER_RATIO }

    /** 四角各自半径的圆角矩形路径（`inset` < 0 = 向外扩，半径同步加大，给外框用）。 */
    fun path(left: Float, top: Float, width: Float, height: Float, r: FloatArray, inset: Float = 0f): Path {
        fun c(i: Int) = (r[i] - inset).coerceAtLeast(0f).let { CornerRadius(it, it) }
        return Path().apply {
            addRoundRect(
                RoundRect(
                    left + inset, top + inset, left + width - inset, top + height - inset,
                    topLeftCornerRadius = c(0),
                    topRightCornerRadius = c(1),
                    bottomRightCornerRadius = c(2),
                    bottomLeftCornerRadius = c(3),
                ),
            )
        }
    }

    /** 挖孔：按外接矩形画成胶囊（打孔屏外接矩形是正方形 → 正圆；刘海 → 两端圆的长条）。纯黑，与真机一致。 */
    fun DrawScope.drawCutouts(screen: ScreenShape?, sx: (Float) -> Float, sy: (Float) -> Float, scale: Float) {
        val holes = screen?.cutouts ?: return
        for (h in holes) {
            val w = h.width * scale
            val hh = h.height * scale
            if (w <= 0f || hh <= 0f) continue
            val r = min(w, hh) / 2f
            drawRoundRect(
                color = Color.Black,
                topLeft = androidx.compose.ui.geometry.Offset(sx(h.left), sy(h.top)),
                size = androidx.compose.ui.geometry.Size(w, hh),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }

    /**
     * 壁纸按「铺满裁切」画进屏幕矩形（等比放大到盖满，居中裁掉多出来的部分）。
     *
     * 不能直接拉伸：SystemUI 推来的是壁纸原图比例（本机 2656×2656 方图），拉进竖长的
     * 屏幕矩形会被压扁 —— 真机桌面也是居中裁着显示的。
     */
    fun DrawScope.drawCover(image: ImageBitmap, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f || image.width <= 0 || image.height <= 0) return
        val scale = maxOf(width / image.width, height / image.height)
        val srcW = (width / scale).coerceAtMost(image.width.toFloat())
        val srcH = (height / scale).coerceAtMost(image.height.toFloat())
        val srcX = ((image.width - srcW) / 2f).coerceAtLeast(0f)
        val srcY = ((image.height - srcH) / 2f).coerceAtLeast(0f)
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX.roundToInt(), srcY.roundToInt()),
            srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
