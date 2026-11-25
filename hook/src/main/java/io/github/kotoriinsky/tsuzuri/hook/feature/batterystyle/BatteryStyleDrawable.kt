package io.github.kotoriinsky.tsuzuri.hook.feature.batterystyle

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import io.github.kotoriinsky.tsuzuri.core.BatteryStyle
import kotlin.math.max

/**
 * 自绘电池。尺寸**取 HyperOS 自己的电池尺寸资源**（运行时从 SystemUI 资源读，读不到用同值的 dp 兜底），
 * 不按电池视图的整体高度推 —— 那是整条状态栏高，第一版按它画，环形 / 横向都大了一倍（真机 2026-11-25）。
 *
 * | 资源（SystemUI）                               | 值          | 用途                  |
 * |-----------------------------------------------|-------------|----------------------|
 * | `battery_meter_real_width/height`             | 24 × 14 dp  | 横向电池本体 / 环形直径 |
 * | `statusbar_battery_digit_size`                | 9.6 dp      | 电池内数字            |
 * | `status_bar_battery_icon_width/height`        | 9.5 × 14.5 dp | 竖条（AOSP 竖电池）   |
 *
 * 纯数字用宿主百分比文字的字号（[textPx]）。所有图形在内容区里垂直居中。
 * 颜色基准 [baseColor] 由宿主百分比文字的当前颜色给出。
 */
internal class BatteryStyleDrawable(private val res: Resources) : Drawable() {

    var style: String = BatteryStyle.NUMBER
    var colored = true
    var batteryLevel = 0
    var charging = false
    var powerSave = false
    var baseColor = Color.WHITE
    var typeface: Typeface? = null
    /** 宿主百分比文字的字号（px），纯数字样式直接用它，保持与系统一致。 */
    var textPx = 36f

    private val dm = res.displayMetrics
    private fun dimen(name: String, fallbackDp: Float): Float {
        val id = res.getIdentifier(name, "dimen", "com.android.systemui")
        val v = if (id != 0) runCatching { res.getDimension(id) }.getOrNull() else null
        return v?.takeIf { it > 0f } ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, fallbackDp, dm)
    }

    private val capsuleW = dimen("battery_meter_real_width", 24f)
    private val capsuleH = dimen("battery_meter_real_height", 14f)
    private val digitPx = dimen("statusbar_battery_digit_size", 9.6f)
    private val vertW = dimen("status_bar_battery_icon_width", 9.5f)
    private val vertH = dimen("status_bar_battery_icon_height", 14.5f)
    private val dp1 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, dm)

    /*
     * 间距与充电闪电照搬宿主的**可见**尺寸（真机 2026-11-25，520dpi 截图逐像素量）：
     * 信号 → 胶囊 15px、胶囊 → 闪电 9px、闪电 18×38px，即 4.6 / 2.8 / 5.5×11.7 dp。
     * 不用子视图的边距推：闪电 ImageView 自带内边距，视图边界和看到的闪电对不上。
     * HyperOS 的闪电是电池**右侧**一个独立图标，跟状态栏同色，电池里照常显示电量。
     */
    /** 电池图形左侧留白（宿主 battery_icon_container 的左边距）。 */
    var leadPx = 4.6f * dp1
    /** 电池图形与右侧闪电的间距。 */
    var boltGapPx = 2.8f * dp1
    var boltWPx = 5.5f * dp1
    var boltHPx = 11.7f * dp1

    /** 快充（宿主 `mQuickCharging`）：换宿主的快充闪电。 */
    var quickCharging = false

    /*
     * 闪电直接用宿主自己的图（`hollow_battery_meter_charging` / `..._quick_charging`），
     * 按 [baseColor] 着色 —— 宿主深浅色切换时换的是同形状的另一张图，着色后效果一致。
     * 取不到才退回自绘路径。
     */
    private fun hostBolt(quick: Boolean): Drawable? {
        val name = if (quick) "hollow_battery_meter_quick_charging" else "hollow_battery_meter_charging"
        return boltCache.getOrPut(name) {
            val id = res.getIdentifier(name, "drawable", "com.android.systemui")
            if (id == 0) null else runCatching { res.getDrawable(id, null)?.mutate() }.getOrNull()
        }
    }
    private val boltCache = HashMap<String, Drawable?>()

    /*
     * 宿主闪电图按**固有尺寸**画（宿主 ImageView 是 wrap_content，不缩放）：520dpi 上 26×65px，
     * 可见闪电只占中间 18×38，四周是透明边。第一版按可见高度把整张图缩到 38px，闪电小了一圈。
     * 间距照旧按「可见」算：图的左透明边从 [boltGapPx] 里扣掉。
     */
    private fun hostBoltSize(): Pair<Int, Int>? = hostBolt(quickCharging)
        ?.let { it.intrinsicWidth to it.intrinsicHeight }?.takeIf { it.first > 0 && it.second > 0 }

    /** 闪电图左侧透明边（按可见闪电居中估）。 */
    private fun boltInset(): Float = hostBoltSize()?.let { max(0f, (it.first - boltWPx) / 2f) } ?: 0f

    /** 闪电占多宽（不含左透明边，它算进间距里）。 */
    private fun boltWidth(): Float = hostBoltSize()?.let { it.first - boltInset() } ?: boltWPx

    /** 量文字专用，别跟绘制共用 [paint]：draw() 中途量宽会把已设好的颜色 reset 掉（闪电变黑就是这么来的）。 */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val clear = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private val red = Color.rgb(0xFF, 0x3B, 0x30)
    private val green = Color.rgb(0x34, 0xC7, 0x59)
    private val orange = Color.rgb(0xFF, 0x95, 0x00)

    /** 状态色：彩色关闭或没有特殊状态时就是基准色。 */
    private fun stateColor(): Int = when {
        !colored -> baseColor
        charging -> green
        powerSave -> orange
        BatteryStyle.isLow(batteryLevel, charging) -> red
        else -> baseColor
    }

    private fun lvl() = batteryLevel.coerceIn(0, 100)

    private val ringD get() = capsuleH
    private val capW get() = max(1.5f * dp1, capsuleW * 0.07f)

    /** 电池图形本身多宽（不含左侧留白与闪电）。 */
    private fun shapeWidth(): Float = when (style) {
        BatteryStyle.RING -> ringD
        BatteryStyle.VERTICAL -> vertW
        BatteryStyle.HORIZONTAL -> capsuleW + capW + dp1
        else -> {
            measurePaint.typeface = typeface; measurePaint.textSize = textPx
            measurePaint.measureText(lvl().toString())
        }
    }

    /** 这个样式要多宽（px，不含宿主 padding）：留白 + 图形 + 充电时的间距与闪电。 */
    fun contentWidth(): Int =
        (leadPx + shapeWidth() + (if (charging) boltGapPx + boltWidth() else 0f) + 1f).toInt()

    private val shapeLeft get() = bounds.left + leadPx

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        when (style) {
            BatteryStyle.RING -> drawRing(canvas)
            BatteryStyle.VERTICAL -> drawVertical(canvas)
            BatteryStyle.HORIZONTAL -> drawHorizontal(canvas)
            else -> drawNumber(canvas)
        }
        if (charging) drawBolt(canvas)
    }

    /** 与宿主一致：右侧独立闪电，与电池同一个基准色（深浅色都跟着走）。 */
    private fun drawBolt(c: Canvas) {
        // 位置先算完再碰画笔
        val x = shapeLeft + shapeWidth() + boltGapPx
        val host = hostBolt(quickCharging)
        val size = hostBoltSize()
        if (host != null && size != null) {
            val (iw, ih) = size
            val l = (x - boltInset()).toInt()
            val t = (bounds.exactCenterY() - ih / 2f).toInt()
            host.setBounds(l, t, l + iw, t + ih)
            host.setTint(baseColor)
            host.draw(c)
            return
        }
        fresh(); paint.color = baseColor
        bolt(x, bounds.exactCenterY() - boltHPx / 2f, boltWPx, boltHPx)
        c.drawPath(path, paint)
    }

    private fun fresh() { paint.reset(); paint.isAntiAlias = true }

    // ── 纯数字（Flyme 式「⚡ 97」）────────────────────────────────────

    private fun drawNumber(c: Canvas) {
        val cy = bounds.exactCenterY()
        fresh()
        paint.typeface = typeface
        paint.textSize = textPx
        paint.color = stateColor()
        val fm = paint.fontMetrics
        c.drawText(lvl().toString(), shapeLeft, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    // ── 环形 ──────────────────────────────────────────────────────────

    private fun drawRing(c: Canvas) {
        val d = ringD
        val stroke = max(1.2f * dp1, d * 0.11f)
        val r = d / 2f - stroke / 2f
        val cx = shapeLeft + d / 2f
        val cy = bounds.exactCenterY()
        rect.set(cx - r, cy - r, cx + r, cy + r)
        fresh()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = baseColor; paint.alpha = 0x4D
        c.drawArc(rect, 0f, 360f, false, paint)
        paint.color = stateColor()
        val sweep = 360f * lvl() / 100f
        if (sweep > 0f) c.drawArc(rect, -90f, sweep, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = baseColor
        paint.typeface = Typeface.create(typeface, Typeface.BOLD)
        val text = lvl().toString()
        paint.textSize = d * (if (text.length >= 3) 0.34f else 0.44f)
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    // ── 竖条（AOSP 竖电池尺寸）────────────────────────────────────────

    private fun drawVertical(c: Canvas) {
        val w = vertW; val h = vertH
        val left = shapeLeft
        val top = bounds.exactCenterY() - h / 2f
        val stroke = max(1f * dp1, w * 0.12f)
        val capH = h * 0.08f; val capW2 = w * 0.44f
        val bodyTop = top + capH; val bodyBottom = top + h
        val radius = w * 0.2f
        val save = c.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), null)
        fresh(); paint.color = baseColor
        rect.set(left + (w - capW2) / 2f, top, left + (w + capW2) / 2f, bodyTop + radius)
        c.drawRoundRect(rect, capH * 0.4f, capH * 0.4f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
        rect.set(left + stroke / 2f, bodyTop + stroke / 2f, left + w - stroke / 2f, bodyBottom - stroke / 2f)
        c.drawRoundRect(rect, radius, radius, paint)
        val inset = stroke * 1.7f
        val innerTop = bodyTop + inset; val innerBottom = bodyBottom - inset
        val fillTop = innerBottom - (innerBottom - innerTop) * lvl() / 100f
        paint.style = Paint.Style.FILL; paint.color = stateColor()
        rect.set(left + inset, fillTop, left + w - inset, innerBottom)
        c.drawRoundRect(rect, radius * 0.4f, radius * 0.4f, paint)
        c.restoreToCount(save)
    }

    // ── 横向（HyperOS 胶囊同尺寸）─────────────────────────────────────

    private fun drawHorizontal(c: Canvas) {
        val bw = capsuleW; val bh = capsuleH
        val left = shapeLeft
        val top = bounds.exactCenterY() - bh / 2f
        val radius = bh * 0.32f
        val save = c.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), null)
        fresh()
        // 本体底色（淡）+ 正极
        paint.color = baseColor; paint.alpha = 0x59
        rect.set(left, top, left + bw, top + bh)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.alpha = 0x80
        val capL = left + bw + dp1
        rect.set(capL, bounds.exactCenterY() - bh * 0.2f, capL + capW, bounds.exactCenterY() + bh * 0.2f)
        c.drawRoundRect(rect, capW * 0.5f, capW * 0.5f, paint)
        // 填充
        paint.alpha = 0xFF; paint.color = stateColor()
        c.save()
        c.clipRect(left, top, left + bw * lvl() / 100f, top + bh)
        rect.set(left, top, left + bw, top + bh)
        c.drawRoundRect(rect, radius, radius, paint)
        c.restore()
        // 数字 / 闪电镂空
        paint.xfermode = clear
        paint.typeface = Typeface.create(typeface, Typeface.BOLD)
        paint.textSize = digitPx
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(lvl().toString(), left + bw / 2f, top + bh / 2f - (fm.ascent + fm.descent) / 2f, paint)
        paint.xfermode = null
        c.restoreToCount(save)
    }

    /** 闪电，放在 (x, y, w, h) 框里。 */
    private fun bolt(x: Float, y: Float, w: Float, h: Float) {
        path.reset()
        path.moveTo(x + w * 0.62f, y)
        path.lineTo(x, y + h * 0.58f)
        path.lineTo(x + w * 0.46f, y + h * 0.58f)
        path.lineTo(x + w * 0.36f, y + h)
        path.lineTo(x + w, y + h * 0.40f)
        path.lineTo(x + w * 0.54f, y + h * 0.40f)
        path.close()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
