package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import io.github.kotoriinsky.tsuzuri.hook.feature.sideindex.SideIndexGlassPanel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreview
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreviewInk
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreviewScene
import kotlin.math.min

/**
 * 侧滑索引参数预览：几何来自 core，图标与名称来自当前应用清单。
 * 壁纸快照在背景层模糊，玻璃高光复用浮窗渲染器；不模拟背景折射或手势动画。
 * 保留整屏比例以显示字母条、面板、气泡和边缘把手的相对位置。
 * 手指与夹取提示是预览标注，分组数量是示意。
 */
val SideIndexPreviewHeight: Dp = 240.dp

/** 名字占位条的宽度占格子内容宽的比例。预览没有真实应用名，占位条只表达「名字在这儿、占这么宽」。 */
private const val NAME_PLACEHOLDER_WIDTH_RATIO = 0.62f

/** 名字占位条用的样例字（**只为量行高**，不画出来）。 */
private const val NAME_PROBE = "应用"

/**
 * 侧滑索引的预览画布。
 *
 * @param preview `sideIndexPreview()` 的产物 —— 参数一变就重画（[SideIndexPreview] 是纯数据，
 *   Compose 的重组/重绘自己会跟上，**不需要重启任何进程**）。
 */
@Composable
fun SideIndexPreviewCanvas(
    preview: SideIndexPreview, modifier: Modifier = Modifier,
    icons: List<ImageBitmap?> = emptyList(), labels: List<String> = emptyList(),
    wallpaper: ImageBitmap? = null, glass: Boolean = false, blurRadiusDp: Float = 0f,
    showHotZones: Boolean = false,
    /** 当前所在屏幕的真实圆角与挖孔（[ScreenShape]）；`null` = 读不到，圆角按估值画。 */
    screen: ScreenShape? = null,
) {
    val context = LocalContext.current
    val screenDensity = context.resources.displayMetrics.density
    val backdrop = remember { WallpaperBackdrop() }
    val glassPainter = if (glass) remember(context) { SideIndexGlassPanel(context, 0f) } else null
    val colors = LocalFpKit.current.colors()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SideIndexPreviewHeight)
            .clipToBounds(),
    ) {
        // 画布尺寸（px）→ 「屏幕像素 → 画布像素」的等比缩放。
        // 缩放系数、字号折算要在画之前算好，所以放在 BoxWithConstraints 里而不是 Canvas 里。
        val canvasWpx = with(density) { maxWidth.toPx() }
        val canvasHpx = with(density) { maxHeight.toPx() }
        val scale = if (preview.screenW > 0f && preview.screenH > 0f) {
            min(canvasWpx / preview.screenW, canvasHpx / preview.screenH)
        } else {
            0f
        }
        val m = Scale(preview, scale, density.density, density.fontScale)

        // 名字占位条的高度 = 真实字体的行高（量一次，字号不变就不重量）。
        // 放在这里量是因为它要用到 `scale`（字号 = 真机的 sp × 缩放）。
        val nameBarHeight = remember(m.nameSp) {
            if (m.nameSp <= 0f) {
                NAME_MIN_HEIGHT_PX
            } else {
                textMeasurer.measure(NAME_PROBE, TextStyle(fontSize = m.nameSp.sp))
                    .size.height.toFloat().safeHeight()
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            if (scale <= 0f) return@Canvas // 屏幕尺寸退化：什么都不画，绝不画一堆 NaN
            val phoneW = preview.screenW * scale
            val phoneH = preview.screenH * scale
            val ox = (size.width - phoneW) / 2f
            val oy = (size.height - phoneH) / 2f
            val sx: (Float) -> Float = { x -> ox + x * scale }
            val sy: (Float) -> Float = { y -> oy + y * scale }
            // 四角圆角：当前屏幕的真值（ScreenShape），读不到时按短边估（改造前的观感）
            val radii = ScreenFrame.radii(screen, scale, phoneW, phoneH)
            val phonePath = ScreenFrame.path(ox, oy, phoneW, phoneH, radii)

            // ① 屏幕轮廓 + 遮罩。真机的层序是「遮罩在最下、条与面板在上」，
            //    所以这里先铺底，再叠那层全屏渐变（`SideIndexOverlay.ensureWindow` 的第一句）。
            if (wallpaper != null) {
                clipPath(phonePath) { backdrop.draw(drawContext.canvas.nativeCanvas, wallpaper.asAndroidBitmap(), ox, oy,
                    phoneW.roundToInt(), phoneH.roundToInt(), blurRadiusDp * screenDensity * scale) }
            }
            drawPath(
                phonePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(SideIndexPreviewInk.DIM_TOP),
                        Color(SideIndexPreviewInk.DIM_BOTTOM),
                    ),
                    startY = oy,
                    endY = oy + phoneH,
                ),
            )
            // 挖孔不画、灰底不铺，只留一小圈边（用户 2026-10-25）
            val frame = ScreenFrame.FRAME_WIDTH.toPx()
            // 描边落在屏幕内侧（内缩半个线宽），圆角不被裁成半截
            drawPath(
                ScreenFrame.path(ox, oy, phoneW, phoneH, radii, inset = frame / 2f),
                color = ScreenFrame.frameColor(colors.surface),
                style = Stroke(width = frame),
            )

            // ①½ 真实起手热区（「触发」页才画）：整条边缘带扣上下保留区，与 hook 判定同一份算式
            //    （core SideIndexHotZone）。画在把手和面板之下 —— 它是底图上的一层标注，不是界面元素。
            if (showHotZones) {
                for (z in preview.hotZones) {
                    val w = z.width * scale
                    val h = z.height * scale
                    if (w <= 0f || h <= 0f) continue
                    drawRect(colors.accent.copy(alpha = 0.3f), Offset(sx(z.left), sy(z.top)), Size(w, h))
                    drawRect(colors.accent, Offset(sx(z.left), sy(z.top)), Size(w, h), style = Stroke(width = 1.5.dp.toPx()))
                }
            }

            // ② 边缘把手。画在遮罩**之上**：真机上手势进行中它其实是隐藏的
            //    （`SideIndexHandle.isVisible` 的 `!gestureActive`），而把手的外观正是
            //    这一页要调的东西 —— 见文件注释的第 3 条标注。
            preview.handle?.let { h ->
                drawRect(
                    color = Color(SideIndexPreviewInk.HANDLE_FILL),
                    topLeft = Offset(sx(h.rect.left), sy(h.rect.top)),
                    size = Size(h.rect.width * scale, h.rect.height * scale),
                )
                drawRect(
                    color = Color(SideIndexPreviewInk.HANDLE_BORDER),
                    topLeft = Offset(sx(h.rect.left), sy(h.rect.top)),
                    size = Size(h.rect.width * scale, h.rect.height * scale),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // ③ 面板（先画它：真机的 z 序里条压在面板上，`buildPanel` 末尾还会再 `bar.bringToFront()`。
            //    先画面板、后画条，就得到同一个结果）
            if (preview.scene == SideIndexPreviewScene.PANEL) {
                glassPainter?.let { renderer ->
                    val rect = preview.panel.viewport
                    val native = drawContext.canvas.nativeCanvas
                    val saved = native.save()
                    native.translate(sx(rect.left), sy(rect.top))
                    renderer.drawMaterial(native, (rect.width * scale).roundToInt(), (rect.height * scale).roundToInt(), 18f * screenDensity * scale)
                    native.restoreToCount(saved)
                }
                drawPanel(preview, colors, scale, sx, sy, nameBarHeight, icons, labels, m.nameSp, textMeasurer)
            }

            // ④ 字母条
            drawLetterBar(preview, colors, scale, sx, sy, m, textMeasurer)

            // ⑤ 大字母气泡。两个场景都画 —— 真机只在「有面板且有应用」时才建它
            //    （`updateBubble` 由 `buildPanel` 调用），而那个条件已经由 core 算进 `visible`。
            if (preview.bubble.visible) {
                drawBubble(preview, colors, scale, sx, sy, m, textMeasurer)
            }

            // ⑥ 手指标注（虚线，见文件注释第 1 条）
            drawFingerAnnotation(preview, colors, scale, sx, sy)

            // ⑦ 夹取提示（需求 #5c）。列数被联动夹掉时**必须写出来** —— 否则
            //    「拖了列数、预览里的格子却少了」会被当成画面错乱，而不是「这个组合放不下」。
            //    这也是本文件第 4 处**标注**（真机上没有这个东西，所以画成带底的浮字）。
            if (preview.panel.columns != preview.panel.requestedColumns) {
                drawClampNotice(preview, colors, textMeasurer)
            }
        }
    }
}

/** 夹取提示的字号（画布上的 sp，不随屏幕缩放 —— 它是标注，不是被预览的对象）。 */
private const val CLAMP_NOTICE_SP = 10f

/**
 * 「放不下 N 列，已按 M 列排」。
 *
 * 为什么值得画：列数 × 格宽是**联动夹取**的（见 `core/SideIndexPreview.panelMetrics`），
 * 用户把列数拖到 7、图标拖到 80dp 时真机只会排下 4 列 —— 没有这一行字，
 * 他的结论会是「这个滑杆坏了」，而不是「这个组合放不下」。
 * 真机侧的同一个信息在安装报告的 `geometry=` 行（`cols=4/7(夹)`）。
 */
private fun DrawScope.drawClampNotice(
    preview: SideIndexPreview,
    colors: FpColors,
    textMeasurer: TextMeasurer,
) {
    val layout = textMeasurer.measure(
        "放不下 ${preview.panel.requestedColumns} 列，已按 ${preview.panel.columns} 列排",
        TextStyle(fontSize = CLAMP_NOTICE_SP.sp, color = Color.Unspecified),
    )
    val pad = 3.dp.toPx()
    val left = pad
    val top = pad
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad * 2
    drawRoundRect(
        color = colors.surfaceVariant.copy(alpha = 0.92f),
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
    )
    drawRoundRect(
        color = colors.outline,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawText(
        textLayoutResult = layout,
        color = colors.accent,
        topLeft = Offset(left + pad, top + pad),
    )
}

/**
 * 绘制期的换算器：把 core 给的**屏幕 px** 换成画布上的 sp。
 *
 * 两类字号在 core 里的单位本来就不同，这里必须分开处理，否则「字母大小不跟着系统字号变、
 * 而应用名跟着变」这条真机差异会在预览里被抹平：
 *
 *  - 字母条字号来自 `AppIndex.BarLayout.letterTextPx`，真机是 `COMPLEX_UNIT_PX`
 *    （`SideIndexOverlay.kt:652`）—— **不随系统字号缩放**。所以折成画布 sp 时要把
 *    `fontScale` **一起除掉**：`X.sp` 渲染回 px 时会再乘一次 `density × fontScale`，
 *    除掉正是为了抵消它，从而与真机的 px 口径一致。
 *  - 应用名与大字母是真机的 `COMPLEX_UNIT_SP`（`SideIndexOverlay.kt:958` / `1281`）——
 *    **跟着系统字号缩放**，所以按 sp 口径给（渲染时自带 `fontScale`）。
 */
private class Scale(
    preview: SideIndexPreview,
    /** 屏幕 px → 画布 px 的缩放系数。 */
    val scale: Float,
    density: Float,
    /** 系统字号缩放：只用来把「真机 px」那一路折成画布 sp。 */
    fontScale: Float,
) {
    /** 字母条字号（画布 sp）。真机是 px，所以折算时要除掉 density 与 fontScale。 */
    val letterSp: Float = run {
        val px = preview.bar.letterTextPx * scale
        val denom = density * (if (fontScale > 0f) fontScale else 1f)
        (if (denom > 0f) px / denom else 0f).coerceAtLeast(0f)
    }

    /** 应用名字号（画布 sp）。真机是 sp，按 sp 口径给（自带 fontScale）。 */
    val nameSp: Float = (preview.panel.nameFontSp * scale).coerceAtLeast(0f)

    /** 大字母字号（画布 sp）。同上。 */
    val bubbleSp: Float = (preview.bubble.fontSp * scale).coerceAtLeast(0f)
}

/** 字母条：逐格画字母，选中那一格换成参考实现的高亮色（Flyme 只换色、不缩放字号）。 */
private fun DrawScope.drawLetterBar(
    preview: SideIndexPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
    m: Scale,
    textMeasurer: TextMeasurer,
) {
    val bar = preview.bar
    if (bar.count <= 0 || bar.itemHeight <= 0f) return
    // 字母条底下那块条：真机上它没有背景（只是 `TextView` 的列），所以这里**不铺底色** ——
    // 铺一块就会让用户以为条有背景，而真机上它只是字母本身。
    for (i in 0 until bar.count) {
        val cy = sy(bar.letterCenterY(i))
        val cx = sx(bar.letterCenterX)
        val style = TextStyle(fontSize = m.letterSp.sp, color = Color.Unspecified)
        val layout = textMeasurer.measure(bar.letters[i], style)
        val color = if (i == bar.slot) {
            Color(SideIndexPreviewInk.LETTER_SELECTED)
        } else {
            Color(SideIndexPreviewInk.LETTER_IDLE)
        }
        drawText(
            textLayoutResult = layout,
            color = color,
            topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
        )
    }
}

/**
 * 面板：视口内裁剪之后画格子（图标 + 名字占位），再画细条。
 *
 * 细条**不参与裁剪**：真机上它是 `ScrollView` 的兄弟节点（`ASAppGroupLayout` 的结构，
 * `SideIndexOverlay.ensurePanel` 里 `group.addView(scroll)` / `group.addView(thumbView)`），
 * 所以视口滚不滚它都在。
 */
private fun DrawScope.drawPanel(
    preview: SideIndexPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
    nameBarHeight: Float,
    icons: List<ImageBitmap?>, labels: List<String>, nameSp: Float, textMeasurer: TextMeasurer,
) {
    val panel = preview.panel
    val viewport = panel.viewport
    val lift = preview.highlightLiftPx * scale

    clipRect(
        left = sx(viewport.left),
        top = sy(viewport.top),
        right = sx(viewport.right),
        bottom = sy(viewport.bottom),
    ) {
        for (cell in panel.cells) {
            if (!cell.inViewport) continue // 真机由 ScrollView 裁掉，这里照同一条（也省掉画不出来的格子）
            val isHit = cell.index == panel.highlighted
            // 真机的高亮是**把格子里面的内容块抬起来**（`setHighlight` 对 Cell.content 的
            // translationY，抬升量算出来的）—— 图标与名字一起走，所以两者用同一个偏移，
            // 而且**格子矩形本身不动**（命中表的矩形就是原位）。
            val dy = if (isHit) -lift else 0f

            if (isHit && preview.highlightNoLiftFallback) {
                // 抬升量算成 0 那一档（格子刚好等于内容块高）：真机改用一层底色表达选中，
                // 预览照画 —— 不画就会出现「预览里那格没有任何选中痕迹」（`SideIndexPreviewInk.CELL_SELECT_BG`）。
                val r = panel.selectBgRadius * scale
                drawRoundRect(
                    color = Color(SideIndexPreviewInk.CELL_SELECT_BG),
                    topLeft = Offset(sx(cell.cellRect.left), sy(cell.cellRect.top)),
                    size = Size(cell.cellRect.width * scale, cell.cellRect.height * scale),
                    cornerRadius = CornerRadius(r, r),
                )
            }

            val iconTopLeft = Offset(sx(cell.iconRect.left), sy(cell.iconRect.top) + dy)
            val iconW = cell.iconRect.width * scale
            val iconH = cell.iconRect.height * scale
            val radius = panel.iconRadius * scale
            val bitmap = icons.getOrNull(cell.index)
            if (bitmap == null) {
                drawRoundRect(colors.surface, iconTopLeft, Size(iconW, iconH), CornerRadius(radius, radius))
            } else {
                val path = Path().apply { addRoundRect(RoundRect(iconTopLeft.x, iconTopLeft.y, iconTopLeft.x + iconW, iconTopLeft.y + iconH, radius, radius)) }
                clipPath(path) { drawImage(bitmap, dstOffset = IntOffset(iconTopLeft.x.roundToInt(), iconTopLeft.y.roundToInt()), dstSize = IntSize(iconW.roundToInt().coerceAtLeast(1), iconH.roundToInt().coerceAtLeast(1))) }
            }
            if (panel.namesVisible) {
                val label = labels.getOrNull(cell.index)
                if (label != null) {
                    val text = textMeasurer.measure(label, TextStyle(fontSize = nameSp.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (panel.cellW * scale).roundToInt().coerceAtLeast(1)))
                    drawText(text, Color.White, Offset(sx(cell.cellRect.centerX) - text.size.width / 2f, sy(cell.nameTop) + dy))
                } else {
                    val nameWidth = panel.cellW * scale * NAME_PLACEHOLDER_WIDTH_RATIO
                    drawRoundRect(Color(SideIndexPreviewInk.NAME), Offset(sx(cell.cellRect.centerX) - nameWidth / 2f, sy(cell.nameTop) + dy), Size(nameWidth, nameBarHeight), CornerRadius(nameBarHeight / 2f, nameBarHeight / 2f))
                }
            }

            if (isHit) {
                // 标注：手指命中的那一格（**原位**虚线框）。真机只抬起它、不画框，
                // 所以这个框画成虚线、用强调色，与「真机上的东西」区分开。
                drawRect(
                    color = colors.accent,
                    topLeft = Offset(sx(cell.cellRect.left), sy(cell.cellRect.top)),
                    size = Size(cell.cellRect.width * scale, cell.cellRect.height * scale),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
        }
    }

    // 细条：4dp × 184dp、圆角 2dp、贴在面板外侧那条缝里
    panel.thumb?.let { t ->
        val radius = panel.thumbRadius * scale
        drawRoundRect(
            color = Color(SideIndexPreviewInk.THUMB),
            topLeft = Offset(sx(t.left), sy(t.top)),
            size = Size(t.width * scale, t.height * scale),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/** 大字母气泡：50dp 的圆 + 2dp 描边 + 居中的大字（真机是 `GradientDrawable.OVAL`）。 */
private fun DrawScope.drawBubble(
    preview: SideIndexPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
    m: Scale,
    textMeasurer: TextMeasurer,
) {
    val bubble = preview.bubble
    val centerX = sx(bubble.centerX)
    val centerY = sy(bubble.centerY)
    val radius = bubble.size * scale / 2f
    if (radius <= 0f) return
    drawCircle(Color(SideIndexPreviewInk.BUBBLE_BG), radius = radius, center = Offset(centerX, centerY))
    drawCircle(
        Color(SideIndexPreviewInk.BUBBLE_STROKE),
        radius = radius,
        center = Offset(centerX, centerY),
        style = Stroke(width = (BUBBLE_STROKE_DP * scale).coerceAtLeast(0.5f)),
    )
    if (bubble.iconSlot) {
        // 特殊槽位（需求 #10）：真机在这里画的是 **drawable**（图标）而不是字母
        // （`SideIndexOverlay.bindBubbleContent`，照 Niagara 的 handle）。
        // 预览没有真实图标，画一个**中性的圆角方块占位** —— 与面板里图标的占位画法
        // 同一套（`colors.surface` 填充 + `colors.outline` 描边），
        // 尺寸照 Niagara 的 `scrollbar_handle_icon_size` = 28sp 相对 50dp 气泡的比例
        // （core 给的 `pressFadeMs` 同批带出的那套参数里，图标大小在真机侧是
        //  `BUBBLE_ICON_SP`，这里只画占位，不重复推导那个数）。
        val iconSide = bubble.size * scale * ICON_SLOT_SIDE_RATIO
        if (iconSide > 0.5f) {
            val r = iconSide * ICON_SLOT_CORNER_RATIO
            drawRoundRect(
                color = colors.surface,
                topLeft = Offset(centerX - iconSide / 2f, centerY - iconSide / 2f),
                size = Size(iconSide, iconSide),
                cornerRadius = CornerRadius(r, r),
            )
            drawRoundRect(
                color = colors.outline,
                topLeft = Offset(centerX - iconSide / 2f, centerY - iconSide / 2f),
                size = Size(iconSide, iconSide),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        return
    }
    if (bubble.text.isEmpty() || m.bubbleSp <= 0f) return
    val layout = textMeasurer.measure(
        bubble.text,
        TextStyle(fontSize = m.bubbleSp.sp, fontWeight = FontWeight.Bold, color = Color.Unspecified),
    )
    // 真机的大字母是**粗体**（`Typeface.DEFAULT_BOLD`，`updateBubble` 里那句 `typeface`）
    drawText(
        textLayoutResult = layout,
        color = Color.White,
        topLeft = Offset(centerX - layout.size.width / 2f, centerY - layout.size.height / 2f),
    )
}

/** 真机气泡描边的宽度：`updateBubble` 里的 `setStroke(dp(2), ...)`。 */
private const val BUBBLE_STROKE_DP = 2f

/**
 * 特殊槽位图标在气泡里的边长比例：真机是 28sp 图标 / 50dp 气泡（`BUBBLE_ICON_SP`
 * 与 `BUBBLE_DP`，28/50 = 0.56）。预览画的是**占位方块**，尺寸按同一比例。
 */
private const val ICON_SLOT_SIDE_RATIO = 0.56f

/** 占位方块的圆角比例（与面板里的图标占位取同一个观感）。 */
private const val ICON_SLOT_CORNER_RATIO = 0.22f

/**
 * 手指标注：一个虚线圈。
 *
 * **真机上没有这个东西** —— 真机上这里就是用户的手指。画它是因为「面板跟手」这条行为
 * 只能靠手指的位置看懂（面板顶 = 手指 y − 70dp）。画成虚线是为了与真机元素区分开。
 */
private fun DrawScope.drawFingerAnnotation(
    preview: SideIndexPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
) {
    if (!preview.fingerX.isFinite() || !preview.fingerY.isFinite()) return
    val cx = sx(preview.fingerX)
    val cy = sy(preview.fingerY)
    // 半径取屏幕短边的 3%：固定比例，缩小屏幕时不会变成一个点
    val r = (preview.screenW * 0.03f) * scale
    if (r <= 0.5f) return
    drawCircle(
        color = colors.accent.copy(alpha = 0.9f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        ),
    )
    drawCircle(color = colors.accent.copy(alpha = 0.35f), radius = r * 0.22f, center = Offset(cx, cy))
}

/**
 * 名字占位条的高度 = 真实字体在给定字号下的**行高**。
 *
 * 为什么不拍一个 `字号 × 1.2`：那会让「名字占了格子里多少地方」变成我们编的数，
 * 而这一页要回答的恰恰是这个问题（图标调大之后名字还放不放得下）。
 * 量一行真字（[NAME_PROBE]）拿到的就是字体自己的行高。
 */
private const val NAME_MIN_HEIGHT_PX = 1f

/** 保证行高可用（0 会画不出名字，负数会让 `drawRoundRect` 抛）。 */
private fun Float.safeHeight(): Float = if (isFinite() && this > 0f) this else NAME_MIN_HEIGHT_PX
