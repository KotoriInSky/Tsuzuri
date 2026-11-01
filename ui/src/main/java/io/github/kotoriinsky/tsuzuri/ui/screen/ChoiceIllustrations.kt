package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIllustrationColors
import kotlin.math.min

/**
 * 视觉参数的图卡示意图。
 *
 * 只给「选项本身就是一种看得见的形态」的参数画图；文字类选择（清单来源、振动强度…）不在这里，
 * 它们继续走分段 / 下拉。返回 `null` = 这个参数或这个取值没有示意图 → 调用方整行退回文字选择
 * （一个参数要么全部选项都有图，要么全部没有，混着不好比）。
 *
 * 位置类示意图尽量取真实几何：Flyme 小窗用 [WindowBounds.defaults]（与 hook 同一份实测值），
 * 系统默认那档是 MIUI 自己算的，这里只能示意。
 */
internal fun choiceIllustration(prefKey: String, value: String): (DrawScope.(FpIllustrationColors) -> Unit)? {
    val name = prefKey.substringAfter('.')
    val feature = prefKey.substringBefore('.')
    return when (name) {
        "corners" -> when (value) {
            "both_bottom" -> { c -> phone(c).also { cornerFan(it, c, left = true); cornerFan(it, c, left = false) } }
            "bottom_left" -> { c -> cornerFan(phone(c), c, left = true) }
            "bottom_right" -> { c -> cornerFan(phone(c), c, left = false) }
            else -> null
        }
        "fan_row_stagger" -> when (value) {
            "aligned" -> { c -> dotRows(c, stagger = false) }
            "half" -> { c -> dotRows(c, stagger = true) }
            else -> null
        }
        "icon_shape" -> when (value) {
            "circle" -> { c -> drawCircle(c.accent, radius = shapeSide() / 2f, center = center) }
            "rounded_square" -> { c -> roundedShape(c, cornerFraction = 0.22f) }
            "squircle" -> { c -> squircle(c) }
            "teardrop" -> { c -> teardrop(c) }
            "original" -> { c -> originalIcon(c) }
            else -> null
        }
        "side" -> when (value) {
            "right" -> { c -> edgeBar(phone(c), c, left = false) }
            "left" -> { c -> edgeBar(phone(c), c, left = true) }
            "both" -> { c -> phone(c).also { edgeBar(it, c, left = true); edgeBar(it, c, left = false) } }
            else -> null
        }
        "material" -> when (value) {
            "none" -> { c -> plainPanel(c) }
            "blur" -> { c -> materialPanel(c, glass = false) }
            "glass" -> { c -> materialPanel(c, glass = true) }
            else -> null
        }
        // 复制直达的「提示方式」（用户 2026-10-23：两个方式都示意一下）
        "interaction_mode" -> if (feature != "passcode_island") null else when (value) {
            "native" -> { c -> nativeCapsule(phone(c), c) }
            "island" -> { c -> superIsland(phone(c), c) }
            else -> null
        }
        "window_preset" -> when (value) {
            WindowBounds.PRESET_SYSTEM -> { c -> systemWindow(phone(c), c) }
            WindowBounds.PRESET_FLYME -> { c -> flymeWindow(phone(c), c) }
            WindowBounds.PRESET_CUSTOM -> { c -> customWindow(phone(c), c) }
            // 复制直达的「打开方式」多一档全屏
            "fullscreen" -> if (feature == "passcode_island") { c -> fullscreen(phone(c), c) } else null
            else -> null
        }
        else -> null
    }
}

// ── 画法 ────────────────────────────────────────────────────────────────────

/** 竖屏手机外框（画布内居中，宽高比 ≈ 0.5），返回屏幕区域。 */
private fun DrawScope.phone(c: FpIllustrationColors): Rect {
    val h = size.height
    val w = min(size.width, h * 0.52f)
    val rect = Rect(Offset((size.width - w) / 2f, 0f), Size(w, h))
    val r = CornerRadius(w * 0.16f)
    drawRoundRect(c.surface, rect.topLeft, rect.size, r)
    drawRoundRect(c.line, rect.topLeft, rect.size, r, style = Stroke(width = 1.6f * density))
    return rect
}

private fun DrawScope.cornerFan(screen: Rect, c: FpIllustrationColors, left: Boolean) {
    val radius = screen.width * 0.55f
    val cx = if (left) screen.left else screen.right
    val start = if (left) 270f else 180f
    drawArc(
        c.accent.copy(alpha = 0.85f),
        startAngle = start,
        sweepAngle = 90f,
        useCenter = true,
        topLeft = Offset(cx - radius, screen.bottom - radius),
        size = Size(radius * 2, radius * 2),
    )
}

private fun DrawScope.edgeBar(screen: Rect, c: FpIllustrationColors, left: Boolean) {
    val w = screen.width * 0.12f
    val x = if (left) screen.left + w * 0.6f else screen.right - w * 1.6f
    drawRoundRect(
        c.accent,
        Offset(x, screen.top + screen.height * 0.18f),
        Size(w, screen.height * 0.64f),
        CornerRadius(w / 2f),
    )
}

private fun DrawScope.dotRows(c: FpIllustrationColors, stagger: Boolean) {
    val r = size.minDimension * 0.08f
    val gap = r * 2.8f
    val rowY = listOf(size.height * 0.36f, size.height * 0.64f)
    rowY.forEachIndexed { row, y ->
        val shift = if (stagger && row == 1) gap / 2f else 0f
        val count = 4
        val total = gap * (count - 1)
        val x0 = (size.width - total) / 2f - (if (stagger) gap / 4f else 0f) + shift
        repeat(count) { i ->
            drawCircle(if (row == 0) c.accent else c.accent.copy(alpha = 0.55f), r, Offset(x0 + i * gap, y))
        }
    }
}

private fun DrawScope.shapeSide() = size.minDimension * 0.72f

private fun DrawScope.shapeRect(): Rect {
    val s = shapeSide()
    return Rect(Offset((size.width - s) / 2f, (size.height - s) / 2f), Size(s, s))
}

private fun DrawScope.roundedShape(c: FpIllustrationColors, cornerFraction: Float) {
    val r = shapeRect()
    drawRoundRect(c.accent, r.topLeft, r.size, CornerRadius(r.width * cornerFraction))
}

/** 超椭圆（n = 4）：比圆角方形更「连续」的转角，用参数方程采样成路径。 */
private fun DrawScope.squircle(c: FpIllustrationColors) {
    val r = shapeRect()
    val a = r.width / 2f
    val path = Path()
    val steps = 64
    for (i in 0..steps) {
        val t = (i.toDouble() / steps) * 2 * Math.PI
        val ct = kotlin.math.cos(t)
        val st = kotlin.math.sin(t)
        val x = r.center.x + a * Math.signum(ct).toFloat() * Math.pow(kotlin.math.abs(ct), 0.5).toFloat()
        val y = r.center.y + a * Math.signum(st).toFloat() * Math.pow(kotlin.math.abs(st), 0.5).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, c.accent)
}

/** 水滴：三个圆角 + 左上一个尖角。 */
private fun DrawScope.teardrop(c: FpIllustrationColors) {
    val r = shapeRect()
    val rad = r.width / 2f
    val path = Path().apply {
        moveTo(r.left, r.top)
        lineTo(r.center.x, r.top)
        arcTo(Rect(r.center.x - rad, r.top, r.right, r.top + 2 * rad), 270f, 90f, false)
        arcTo(Rect(r.center.x - rad, r.top, r.right, r.bottom), 0f, 90f, false)
        arcTo(Rect(r.left, r.top, r.right, r.bottom), 90f, 90f, false)
        close()
    }
    drawPath(path, c.accent)
}

/** 原始形状：应用自己的图标外形，示意成一个小圆角方块里放一个图形。 */
private fun DrawScope.originalIcon(c: FpIllustrationColors) {
    val r = shapeRect()
    drawRoundRect(c.fill, r.topLeft, r.size, CornerRadius(r.width * 0.12f))
    drawRoundRect(c.line, r.topLeft, r.size, CornerRadius(r.width * 0.12f), style = Stroke(width = 1.6f * density))
    drawCircle(c.accent, r.width * 0.2f, r.center)
}

/** 背景「无」：不透明的纯色面板，看不到背后（与模糊 / 玻璃两张同一位置、同一尺寸）。 */
private fun DrawScope.plainPanel(c: FpIllustrationColors) {
    val panel = Rect(Offset(size.width * 0.14f, size.height * 0.1f), Size(size.width * 0.72f, size.height * 0.8f))
    drawRoundRect(c.accent.copy(alpha = 0.7f), panel.topLeft, panel.size, CornerRadius(panel.width * 0.18f))
}

private fun DrawScope.materialPanel(c: FpIllustrationColors, glass: Boolean) {
    val panel = Rect(Offset(size.width * 0.14f, size.height * 0.1f), Size(size.width * 0.72f, size.height * 0.8f))
    val corner = CornerRadius(panel.width * 0.18f)
    if (!glass) {
        // 高斯模糊：柔和的纵向渐变，看不清背后
        drawRoundRect(
            Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.45f), c.accent.copy(alpha = 0.18f)), panel.top, panel.bottom),
            panel.topLeft,
            panel.size,
            corner,
        )
    } else {
        // 玻璃高光：通透底 + 一道斜向高光 + 亮边
        drawRoundRect(c.accent.copy(alpha = 0.16f), panel.topLeft, panel.size, corner)
        drawRoundRect(Color.White.copy(alpha = 0.8f), panel.topLeft, panel.size, corner, style = Stroke(width = 1.4f * density))
        drawLine(
            Color.White.copy(alpha = 0.9f),
            Offset(panel.left + panel.width * 0.2f, panel.bottom - panel.height * 0.15f),
            Offset(panel.right - panel.width * 0.15f, panel.top + panel.height * 0.2f),
            strokeWidth = panel.width * 0.08f,
        )
    }
}

/** 系统默认：澎湃默认小窗的实测比例（宽 70%、高 51.4%、居中、上沿 10.9%；2026-10-25 真机，见 FanPreviewCanvas）。 */
private fun DrawScope.systemWindow(screen: Rect, c: FpIllustrationColors) {
    val w = screen.width * 0.7f
    val h = screen.height * 0.514f
    windowRect(Rect(Offset(screen.center.x - w / 2f, screen.top + screen.height * 0.109f), Size(w, h)), c)
}

/** Flyme：与 hook 同一份实测默认值（宽 80% / 高 63.5% / 中心 50%、41.7%）。 */
private fun DrawScope.flymeWindow(screen: Rect, c: FpIllustrationColors) {
    val r = WindowBounds.defaults(screen.width, screen.height)
    windowRect(Rect(screen.left + r.left, screen.top + r.top, screen.left + r.right, screen.top + r.bottom), c)
}

private fun DrawScope.customWindow(screen: Rect, c: FpIllustrationColors) {
    val rect = Rect(
        Offset(screen.left + screen.width * 0.18f, screen.top + screen.height * 0.26f),
        Size(screen.width * 0.64f, screen.height * 0.4f),
    )
    drawRoundRect(
        c.accent,
        rect.topLeft,
        rect.size,
        CornerRadius(rect.width * 0.08f),
        style = Stroke(width = 1.6f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))),
    )
    val k = rect.width * 0.1f
    for (p in listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)) {
        drawCircle(c.accent, k / 2f, p)
    }
}

private fun DrawScope.fullscreen(screen: Rect, c: FpIllustrationColors) {
    val inset = screen.width * 0.06f
    windowRect(Rect(screen.left + inset, screen.top + inset, screen.right - inset, screen.bottom - inset), c)
}

/** 原生胶囊：贴在屏幕右侧边缘靠下，一颗横向小药丸（用户 2026-10-23：「胶囊是底部侧边的」）。 */
private fun DrawScope.nativeCapsule(screen: Rect, c: FpIllustrationColors) {
    val w = screen.width * 0.5f
    val h = screen.height * 0.075f
    val top = screen.top + screen.height * 0.72f
    val right = screen.right - screen.width * 0.04f
    val left = right - w
    // 原生胶囊是白底（用户 2026-10-23），描一圈线免得在浅色手机底上看不见
    drawRoundRect(Color.White, Offset(left, top), Size(w, h), CornerRadius(h / 2f))
    drawRoundRect(c.line, Offset(left, top), Size(w, h), CornerRadius(h / 2f), style = Stroke(width = 1.2f * density))
    drawCircle(c.accent, h * 0.32f, Offset(left + h * 0.55f, top + h / 2f))
    drawRoundRect(
        Color.Black.copy(alpha = 0.35f),
        Offset(left + h * 1.1f, top + h * 0.42f),
        Size(w - h * 1.6f, h * 0.16f),
        CornerRadius(h * 0.08f),
    )
}

/** 超级岛：顶部展开的深色卡片 —— 封面、两行文字、「分享 / 打开」两颗按钮。 */
private fun DrawScope.superIsland(screen: Rect, c: FpIllustrationColors) {
    val pad = screen.width * 0.06f
    val w = screen.width - pad * 2f
    val h = screen.height * 0.24f
    val top = screen.top + screen.height * 0.035f
    val left = screen.left + pad
    drawRoundRect(IslandInk, Offset(left, top), Size(w, h), CornerRadius(h * 0.24f))
    val inner = w * 0.08f
    val cover = h * 0.36f
    drawRoundRect(c.accent.copy(alpha = 0.9f), Offset(left + inner, top + inner), Size(cover, cover), CornerRadius(cover * 0.24f))
    val textLeft = left + inner * 1.6f + cover
    val textW = w - (textLeft - left) - inner
    drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(textLeft, top + inner + cover * 0.12f), Size(textW, cover * 0.2f), CornerRadius(cover * 0.1f))
    drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(textLeft, top + inner + cover * 0.6f), Size(textW * 0.55f, cover * 0.16f), CornerRadius(cover * 0.08f))
    val btnTop = top + inner * 1.6f + cover
    val btnH = h - (btnTop - top) - inner
    val btnW = (w - inner * 3f) / 2f
    drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(left + inner, btnTop), Size(btnW, btnH), CornerRadius(btnH / 2f))
    drawRoundRect(c.accent, Offset(left + inner * 2f + btnW, btnTop), Size(btnW, btnH), CornerRadius(btnH / 2f))
}

/** 超级岛的底色：深浅色模式下都是深色。 */
private val IslandInk = Color(0xFF26262B)

private fun DrawScope.windowRect(rect: Rect, c: FpIllustrationColors) {
    drawRoundRect(c.accent.copy(alpha = 0.85f), rect.topLeft, rect.size, CornerRadius(rect.width * 0.08f))
}
