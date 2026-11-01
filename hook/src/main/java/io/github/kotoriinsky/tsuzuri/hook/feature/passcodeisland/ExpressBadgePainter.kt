package io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Cmd
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Role
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Shape
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Style
import kotlin.math.roundToInt

/**
 * 画承运商自绘徽标（扁平风格，2026-10-22 用户定：参照图标包「补全计划 One」）。
 *
 * 纯色圆角底（圆角 = 宽 × [Style.CORNER]）+ 中间 [Style.GLYPH_SPAN] 见方的扁平几何图形，
 * 没有渐变 / 投影 / 高光 / 描边。图形数据全部来自 core 的 [ExpressBadge.Glyph]（24 网格），
 * 与 `docs/artwork/express-badges/preview.html` 读同一份数据，几何一一对应。
 *
 * 镂空（[Role.CUT]）用 DST_OUT 擦掉**之前**画在图形层上的部件，露出底色；所以图形先画在
 * 独立的透明层上，再整体贴到底色上（直接画在底色上擦会把底色也擦穿）。
 * 全部 Canvas 现画，不用图片 / 字体资源。任何异常返回 null（调用方回落到 App 图标）。
 */
internal object ExpressBadgePainter {

    fun paint(badge: ExpressBadge.Badge, sizePx: Int): Bitmap? = try {
        val s = sizePx.toFloat()
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(badge.background) }
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * Style.CORNER, s * Style.CORNER, bg)

        val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val k = s * Style.GLYPH_SPAN / Style.GRID
        val off = s * (1f - Style.GLYPH_SPAN) / 2f
        lc.translate(off, off)
        lc.scale(k, k)
        val fg = Color.parseColor(badge.foreground)
        val accent = Color.parseColor(badge.accent)
        for (m in badge.glyph.marks) drawMark(lc, m, if (m.role == Role.ACCENT) accent else fg)
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        out
    } catch (_: Throwable) {
        null
    }

    /** 一个部件。坐标、线宽、点距都在 24 网格单位里，由画布的缩放统一放大（与 SVG 的 transform 同理）。 */
    private fun drawMark(c: Canvas, m: ExpressBadge.Mark, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = (m.alpha * 255f).roundToInt().coerceIn(0, 255)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = m.stroke
            style = when {
                !m.fill -> Paint.Style.STROKE
                m.stroke > 0f -> Paint.Style.FILL_AND_STROKE
                else -> Paint.Style.FILL
            }
            if (m.dot > 0f) pathEffect = DashPathEffect(floatArrayOf(Style.DOT_ON, m.dot), 0f)
            if (m.role == Role.CUT) {
                this.color = Color.BLACK
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        }
        when (val sh = m.shape) {
            is Shape.Circle -> c.drawCircle(sh.cx, sh.cy, sh.r, paint)
            is Shape.Rect -> c.drawRoundRect(RectF(sh.x, sh.y, sh.x + sh.w, sh.y + sh.h), sh.rx, sh.rx, paint)
            is Shape.Path -> c.drawPath(toPath(sh.d), paint)
        }
    }

    private fun toPath(d: String): Path = Path().apply {
        for (cmd in ExpressBadge.parsePath(d)) when (cmd) {
            is Cmd.Move -> moveTo(cmd.x, cmd.y)
            is Cmd.Line -> lineTo(cmd.x, cmd.y)
            is Cmd.Quad -> quadTo(cmd.x1, cmd.y1, cmd.x, cmd.y)
            is Cmd.Cubic -> cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x, cmd.y)
            Cmd.Close -> close()
        }
    }
}
