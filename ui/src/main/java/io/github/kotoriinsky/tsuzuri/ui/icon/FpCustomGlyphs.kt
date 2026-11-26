package io.github.kotoriinsky.tsuzuri.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 本仓库自绘的色块图形。
 *
 * 与 [FpGlyphs] 分开放：那边是 Material Icons 的路径、由 `gen_glyphs.py` 生成，
 * 这里是手写的几何，改图形直接改这里的参数。画布同为 24×24，填充黑色（由色块着色）。
 */
object FpCustomGlyphs {

    /**
     * 扇形小窗：一排圆点沿四分之一弧排开（用户 2026-10-27）。
     *
     * 照真机的样子画：扇子以屏幕底角为圆心，应用沿弧排开。圆心本身不画
     * （第二版画了空心圆环当圆心，用户：不需要，一行圆点就行，圆大一些）。
     * 圆心取在左下画布外沿 (5.5, 18.5)，4 个点在 0° / 30° / 60° / 90°，
     * 外框约 (3, 3)–(21, 21)，占满画布。
     */
    val FanWindow: ImageVector by lazy {
        val cx = 5.5f
        val cy = 18.5f
        ImageVector.Builder(
            name = "Fp.FanWindow",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 角度从水平向右起、逆时针（向上）量
                for (deg in listOf(0f, 30f, 60f, 90f)) dotOnArc(cx, cy, radius = 13f, deg = deg, r = 2.6f)
            }
        }.build()
    }

    /** 横向圆角电池（24×24）：壳体 + 右侧电极帽，用于「电池样式」功能图标。 */
    val Battery: ImageVector by lazy {
        ImageVector.Builder(
            name = "Fp.Battery",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 电池主体：圆角矩形 (2.5, 6.5) - (19.5, 17.5)，r=2.5
                moveTo(5f, 6.5f)
                lineTo(17f, 6.5f)
                arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2.5f, dy1 = 2.5f)
                lineTo(19.5f, 15f)
                arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -2.5f, dy1 = 2.5f)
                lineTo(5f, 17.5f)
                arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -2.5f, dy1 = -2.5f)
                lineTo(2.5f, 9f)
                arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2.5f, dy1 = -2.5f)
                close()
                // 内部电芯条（负空间留白改用外壳空心？改为右极帽 + 主体实心，与同页色块内白色实心风一致）
                // 右侧正极帽：(20.5, 9.8) - (22.2, 14.2)，圆角
                moveTo(20.5f, 9.8f)
                lineTo(21.2f, 9.8f)
                arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 1f, dy1 = 1f)
                lineTo(22.2f, 13.2f)
                arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -1f, dy1 = 1f)
                lineTo(20.5f, 14.2f)
                close()
            }
        }.build()
    }
}

private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
    moveTo(x - r, y)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
    close()
}

/** 在以 (cx, cy) 为圆心、[radius] 为半径的弧上 [deg] 度处画一个半径 [r] 的点（屏幕坐标 y 向下）。 */
private fun PathBuilder.dotOnArc(cx: Float, cy: Float, radius: Float, deg: Float, r: Float) {
    val rad = Math.toRadians(deg.toDouble())
    circle(cx + radius * cos(rad).toFloat(), cy - radius * sin(rad).toFloat(), r)
}
