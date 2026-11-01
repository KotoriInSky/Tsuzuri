package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.geometry.FanAnimation
import io.github.kotoriinsky.tsuzuri.core.geometry.FanAnimationTiming
import io.github.kotoriinsky.tsuzuri.core.geometry.FanArcs
import io.github.kotoriinsky.tsuzuri.core.geometry.FanPreview
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapes
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenShape
import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 参数页里的**内嵌小预览**（自绘，不进 [FpKit] 契约）。
 *
 * ## 为什么不进契约
 *
 * 与 [LetterBar]、[ReorderList] 同一条理由：这是一块自绘控件，两套风格在这件事上
 * 没有各自的表达方式（Material 3 与澎湃都没有「扇形几何预览」这种组件），
 * 进契约就是两处实现 + 两处契约测试，而两份实现唯一会做的事是**手感/观感漂移**。
 * 配色仍然从 [LocalFpKit] 取，所以它看起来还是当前风格的一部分。
 *
 * ## 几何**全部**来自 core，这里一个数都不算
 *
 * 每一个坐标、半径、直径都取自 [FanPreview]（= 真机同一份 `FanSolve` 求解的结果）。
 * 这里只做「屏幕像素 → 画布像素」的线性缩放。**不许**在这个文件里推半径、
 * 算角度、猜留距 —— 两份几何必然漂移，而这个项目已经在别处吃过这种亏
 * （预览和真机给出两个答案，用户只会相信预览，然后发现被耍了）。
 *
 * 「形状 / 描边 / 不透明度 / 展开起点」这四组渲染参数也**不在这个文件里解析**：
 * 它们由 `fanPreview()` 一并算好带出来（[FanPreview.iconShapeParams]、
 * [FanPreview.iconEdgeEnabled] / [FanPreview.iconEdgeWidthPx]、
 * [FanPreview.iconAlpha]、[FanPreviewItem.startCenterX] …），这里只负责画。
 * 曾经有一版把「形状默认参数」写在这里（`IconShapes.outline(preview.iconShape, …)`），
 * 后果是用户拖圆角滑块时预览不动、真机在动 —— 所以现在一律走解析后的字段。
 *
 * ## 为什么是「裁剪到角上」而不是整屏
 *
 * 整块屏幕的宽高比接近 1:2.2，把它塞进卡片里画，扇形只有几十像素 —— 什么都看不出来。
 * 所以按 [kind] 取一块**感兴趣区域**：
 *
 *  - 触发区 / 扇形：底角那块正方形（边长 = 短边），扇形与外层图标全在里面；
 *  - 小窗摆放：整块屏幕（窗口本身占屏幕的七成，整屏才看得出位置关系）。
 *
 * 被裁掉的那两条边画成浅色（表示「屏幕还在继续」），真实屏幕边缘画成深色。
 *
 * ## 背景是**中性轮廓**，不是壁纸
 *
 * 本机壁纸是 `com.miui.miwallpaper` 的**动态壁纸服务**，不是静态位图，
 * 而模块在设置界面进程里也没有读壁纸的权限（`WallpaperManager.getDrawable()`
 * 基本必抛）。所以用一块中性的屏幕轮廓当底 —— 预览只要回答「东西在屏幕的哪个位置、
 * 多大」，不需要像不像真机背景。
 */
enum class FanPreviewKind {
    /** 触发区：热区矩形叠在屏幕轮廓上。 */
    TRIGGER,

    /** 小窗摆放：窗口矩形。 */
    WINDOW,

    /** 扇形排列 / 图标与外观：弧、图标位、选中环。 */
    FAN,
}

/** 预览卡片的固定高度（三种预览共用一个高度，页面不会因为换一组参数就跳一下）。 */
val FanPreviewHeight: Dp = 260.dp

/**
 * **小窗摆放**那一档单独用的高度：更高。
 *
 * 为什么它要例外 —— 这一档必须**整屏可见**（`window_x/y_percent` 的语义就是
 * 「窗口在整块屏幕的哪里」，只画下半屏的话 y 一拖框就跑出画面），而"整屏可见"意味着
 * 缩放系数由**高度**决定 → 卡片越矮，手机越小。260dp 时手机只占卡片宽度的**三分之一**，
 * 位置变化只有几十像素，实测反馈就是「怎么调都是一个样子」。
 *
 * 380dp 下：手机占卡片宽度的 ~50%，`x` 全量程能移动 113px、`y` 能移动 451px
 * （窗宽 80% 只剩 20% 的自由度，所以 x 天然比 y 小 —— 这是几何决定的，不是画法问题）。
 */
val WindowPreviewHeight: Dp = 260.dp

/**
 * 屏幕轮廓的填充色：比卡片底色稍深/稍浅一点，取「表面变体」色即可。
 *
 */
@Composable
fun FanPreviewCanvas(
    preview: FanPreview,
    kind: FanPreviewKind,
    modifier: Modifier = Modifier,
    /**
     * 每一项要画的**真实图标**（与 `preview.items` 的 `index` 对齐）。
     *
     * 取自 `:hook` 的 `FanIconPreview`（= 真机 `FanTargets.appIcon` 那条四级回退链的
     * 同一份实现，包含图标包、`iconback/mask/upon` 合成与单项覆盖），
     * 所以画出来的就是真机上会出现的那张图。空列表或某一项为 `null` 时退回占位填充 ——
     * 预览**不允许**因为一个图标解析失败而整块空白。
     */
    icons: List<ImageBitmap?> = emptyList(),
    /**
     * 当前桌面的壁纸（`WallpaperManager` 读出来的，见 `PreviewWallpaper`）。
     *
     * `null` = 没读到（动态壁纸 / 权限门 / 系统返回 null）-- 那时画中性示意底图，
     * **不兜一张假的壁纸**：用户会相信预览。调用方负责在卡片上说明原因。
     */
    wallpaper: ImageBitmap? = null,
    /**
     * 动效演示用：这一档的时长表（`null` = 只画终态，也就是「静态预览」）。
     *
     * 为什么给的是**时长表 + 取当前毫秒的 lambda**，而不是一个算好的状态对象：
     * 动画每帧都在变，如果毫秒数在组合里被读出来，整块参数区每帧都会重组（60 次/秒）。
     * 读发生在**绘制阶段**（`elapsedMs()` 在 Canvas 的 draw lambda 里调），
     * 于是每帧只重画、不重组 —— 这是 Compose 里做自绘动画的标准姿势。
     */
    animationTiming: FanAnimationTiming? = null,
    /** 当前动画已经跑了多少毫秒（只有 [animationTiming] 非空时会被调用）。 */
    elapsedMs: () -> Float = { 0f },
    /** `fan_rotation`：展开期每项的自转幅度（度）。 */
    rotationDegrees: Float = 0f,
    /** 当前所在屏幕的真实圆角与挖孔（[ScreenShape]）；`null` = 读不到，圆角按估值画。 */
    screen: ScreenShape? = null,
    /** 覆盖默认高度（「内容」页的扇形预览要更高，见 FanPreviewSection）。null = 按 [kind] 取默认。 */
    height: Dp? = null,
) {
    val colors = LocalFpKit.current.colors()

    val openToRight = !preview.leftEnabled && preview.rightEnabled

    // ⚠️ 这份状态 **在绘制 lambda 里才构造**：`elapsedMs()` 读的是调用方持有的动画状态，
    // 在组合阶段构造它 = 每帧重组整页（见参数注释）。
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height ?: if (kind == FanPreviewKind.WINDOW) WindowPreviewHeight else FanPreviewHeight)
            .clipToBounds()
            .fillMaxSize(),
    ) {
        val anim = animationTiming?.let {
            FanAnimation(it, elapsedMs(), rotationDegrees, openToRight)
        }
        // ── 比例：两个基准，按**这一档要看什么**选 ──────────────────────────
        // 两种都是"真机等比"（同一个 scale 同时作用在 x 与 y，不做非等比拉伸），
        // 区别只在**以哪条边为准**：
        //
        //  · 小窗位置：**以高度为准**（整屏都进画面）。它的语义就是"窗口在屏幕的哪里"，
        //    `window_y_percent` 算的是 `(屏高 - 窗高) * y%` —— 只画下半块屏幕的话，
        //    y 小时那个框会直接跑出可见区，用户拖滑杆看到的却是"框没了"。
        //  · 触发区 / 扇形：**以宽度为准**，贴底裁。它们的活动区本来就在下半部分，
        //    全宽等比才能让 18% 的热区、扇形图标有足够的分辨率。
        //
        // 代价写在明处：扇形档看不到屏幕上半部分（上边缘用浅色虚线标出来 = 那是裁的，
        // 不是屏幕真边）。
        val windowMode = kind == FanPreviewKind.WINDOW
        val scale = if (windowMode) {
            min(size.width / preview.screenW, size.height / preview.screenH)
        } else {
            size.width / preview.screenW
        }
        val visibleH = min(preview.screenH, size.height / scale)
        val cropTop = if (windowMode) 0f else preview.screenH - visibleH
        val cropW = preview.screenW
        val cropH = min(preview.screenH, visibleH)
        val ox = (size.width - cropW * scale) / 2f
        val oy = (size.height - cropH * scale) / 2f
        // 这里必须是 lambda 而不是局部函数：局部函数**不能当值传**（Kotlin 只允许 `::f`
        // 那种引用，而局部函数拿不到），而下面每个绘制函数都要用这两个映射
        val sx: (Float) -> Float = { x -> ox + x * scale }
        val sy: (Float) -> Float = { y -> oy + (y - cropTop) * scale }

        // 四角圆角：当前屏幕的真值（ScreenShape），读不到时按可见区短边估（改造前的观感）
        val radii = ScreenFrame.radii(screen, scale, cropW * scale, cropH * scale)
        // ── 设备框 ────────────────────────────────────────────────────────
        // 屏幕外一圈底色。上半部分被裁掉，所以只看得到左右与底边 --
        // 这一圈是「这是一台手机」的唯一视觉提示，也是"比例感"的来源，不能省。
        // 2026-09-19 加宽到 6dp 并在屏幕底边内补了一条手势条：原来 3dp 的一圈
        // 在一张灰底卡片里几乎看不出是台手机，而「这是真机等比缩放的预览」
        // 正是这一整块要传达的第一件事。
        // 2026-10-21：外框跟着**整块屏幕**的四角走（原来是按可见区画一个统一圆角的框，
        // 上半截被裁掉时连裁口的两个角也是圆的）；被裁掉的部分由 clipToBounds 收掉。
        // 2026-10-25：灰色实心外框撤掉，改由 drawScreenOutline 末尾的一小圈描边表达「这是一台手机」

        drawScreenOutline(
            preview, colors, scale, sx, sy, cropW, cropH, cropTop,
            edgeTop = cropTop <= 0f,
            wallpaper = wallpaper,
            radii = radii,
            screen = screen,
        )

        // 底部手势条：真机屏幕底缘那一条横杠。画在**屏幕内**（用户看到的屏幕里本来就有它），
        // 位置与真机一致（贴底约 9dp、宽占屏宽三分之一），颜色跟着主题走。
        val barW = cropW * scale * 0.34f
        val barH = 3.5.dp.toPx()
        drawRoundRect(
            color = colors.outline.copy(alpha = 0.35f),
            topLeft = Offset(
                ox + (cropW * scale - barW) / 2f,
                sy(cropTop + cropH) - barH * 2.6f,
            ),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f),
        )

        // ── 只画这一档要调的东西 ─────────────────────────────────────────────
        // 「每项展示特定内容」：预览是**这一页滑块的可视化**，不是整机截图。
        // 三档全画会让画面变成一锅粥（热区框 + 扇形图标 + 小窗框叠在一起），
        // 用户的注意力会被与当前参数无关的东西带走；真要看全貌就去真机划一下。
        //
        // 屏幕轮廓、圆角、壁纸仍然画 —— 那是**比例与背景**，不是"别的档位的内容"。
        when (kind) {
            FanPreviewKind.TRIGGER -> drawHotZones(preview, colors, scale, sx, sy)
            FanPreviewKind.WINDOW -> drawWindow(preview, colors, scale, sx, sy)
            FanPreviewKind.FAN -> drawFan(preview, colors, scale, sx, sy, openToRight, icons, anim)
        }

        // ── 标注层最后画：整屏小地图──
        // 裁掉屏幕上半部分之后（扇形与触发区两档都是为了看清细节才裁的），用户就失去了
        // 「我在看整台手机的哪一块」这个参照。小地图把整屏缩到右上角，并把**当前视野带**
        // 涂上底色 —— 裁切是刻意的，但不该悄悄发生。
        // 整屏本来就在画面里时（小窗摆放那一档的常见情形）不画：那时它是多余的重复。
        if (cropTop > 0f || preview.screenH * scale > size.height + 1f) {
            drawViewportMiniMap(preview, colors, visibleTop = cropTop, visibleHeight = cropH)
        }
    }
}

/**
 * 屏幕本体 + 四条边（真实边缘深、被裁出来的边浅）。
 *
 * 有壁纸时壁纸按**整块屏幕**铺（不是压缩进可见的那一段）-- 裁掉的是上面看不到的部分，
 * 不是把内容挤扁。2026-10-21 起按「铺满裁切」画（[ScreenFrame.drawCover]）：SystemUI 推来的是
 * 壁纸原图比例（本机 2656×2656 方图），原来的「直接拉伸」会把它压扁。
 * 屏幕轮廓用当前屏幕的真实四角圆角，并画出挖孔（[ScreenShape]）。
 */
private fun DrawScope.drawScreenOutline(
    preview: FanPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
    cropW: Float,
    cropH: Float,
    /** 可见区在**屏幕坐标**里的上边缘（被裁掉的高度）。底衬与手势条都要用它定位。 */
    cropTop: Float,
    edgeTop: Boolean,
    wallpaper: ImageBitmap?,
    /** 画布上的四角半径（左上、右上、右下、左下），见 [ScreenFrame.radii]。 */
    radii: FloatArray,
    screen: ScreenShape?,
) {
    val w = cropW * scale
    val left = sx(0f)

    // 整块屏幕的矩形（上边在 sy(0)，被裁掉的部分落在画布之外，由 clipToBounds 收掉）。
    // 壁纸、底衬、描边三者共用这一条路径 —— 各自算一遍必然在某一版里对上不齐。
    val screenTop = sy(0f)
    val screenH = preview.screenH * scale
    val screenBottom = sy(cropTop + cropH)
    val screenPath = ScreenFrame.path(left, screenTop, w, screenH, radii)

    if (wallpaper == null) {
        // 没读到壁纸 → **中性示意底图**（不是纯色块，更不是一张假壁纸）。
        // 2026-09-19 改：原来是 `surfaceVariant` 纯色，结果整块预览是一张灰纸 ——
        // 用户看到的不是「一台手机的屏幕」，而是「坏了的一块区域」（用户原话：
        // 「实时预览的效果根本就没有做」）。现在给一层上下渐变的屏幕底：
        // 它不模仿任何壁纸，但把「这是一块屏幕」说清楚了。
        // **仍然不许**兜一张看着很像真壁纸的默认图 —— 用户会相信预览（类注释第 3 条）。
        // 2026-10-25：灰白渐变底也撤掉（用户：不要灰底白底），没有壁纸就只留轮廓
        Unit
    } else {
        // 铺满裁切而不是拉伸：壁纸原图比例常与屏幕不同（本机是 2656² 方图），见 ScreenFrame.drawCover
        clipPath(screenPath) { with(ScreenFrame) { drawCover(wallpaper, left, screenTop, w, screenH) } }
    }

    // ── 底衬（scrim）──
    // 屏幕底部 58% 高度，从透明渐到 26% 黑。**不是装饰**：真机上就是这么压的
    // （花哨壁纸上扇形图标与它的白描边对比度不足），预览不画的话，
    // 用户会以为真机上的图标比预览里更清楚，然后拿这个差异去怀疑别的东西。
    // 压到底衬上的内容只有图标（弧线、热区框都在它之上画），所以不会把标注压暗。
    val scrimTop = screenBottom - screenH * SCRIM_HEIGHT_RATIO
    if (screenBottom > scrimTop) {
        clipPath(screenPath) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = SCRIM_MAX_ALPHA),
                    startY = scrimTop,
                    endY = screenBottom,
                ),
                topLeft = Offset(left, scrimTop),
                size = Size(w, screenBottom - scrimTop),
            )
        }
    }

    // 挖孔不画（用户 2026-10-25：挖孔就不要管了）

    // 描边沿整块屏幕的真实轮廓走（含圆角）；上边被裁掉时，在裁口画一条浅色线 = 「这是裁的，不是屏幕边」
    val stroke = ScreenFrame.FRAME_WIDTH.toPx()
    // 描边整条落在屏幕**内侧**（路径内缩半个线宽、半径同步缩）：居中描边会有一半画到屏幕外，
    // 贴着画布边时被裁掉，圆角就成了「半截粗、半截细」的怪曲线（真机 2026-10-25）
    drawPath(
        ScreenFrame.path(left, screenTop, w, screenH, radii, inset = stroke / 2f),
        color = ScreenFrame.frameColor(colors.surface),
        style = Stroke(width = stroke),
    )
    if (!edgeTop) {
        val cutY = sy(cropTop)
        drawLine(colors.outline.copy(alpha = 0.35f), Offset(left, cutY), Offset(left + w, cutY), strokeWidth = stroke)
    }
}

/**
 * 整屏小地图：整屏轮廓 + **当前视野带** + 小窗位置。
 *
 * 只画与「我在看哪儿」有关的三样东西，不画扇形/热区 —— 缩到 30dp 宽时它们都是一个点，
 * 画上去只会让人以为图标糊了。颜色取当前主题的描边色系（与 [FanPreviewCanvas] 同一套）。
 */
private fun DrawScope.drawViewportMiniMap(
    preview: FanPreview,
    colors: FpColors,
    visibleTop: Float,
    visibleHeight: Float,
) {
    val margin = 10.dp.toPx()
    val mmW = 30.dp.toPx()
    val mmH = mmW * preview.screenH / preview.screenW
    val left = size.width - margin - mmW
    val top = margin
    val r = mmW * 0.16f
    val clip = Path().apply { addRoundRect(RoundRect(left, top, left + mmW, top + mmH, r, r)) }

    drawRoundRect(
        color = colors.surface.copy(alpha = 0.92f),
        topLeft = Offset(left, top),
        size = Size(mmW, mmH),
        cornerRadius = CornerRadius(r, r),
    )
    clipPath(clip) {
        // 当前视野带
        val bandTop = top + mmH * (visibleTop / preview.screenH)
        val bandH = mmH * (visibleHeight / preview.screenH)
        drawRect(
            color = colors.accent.copy(alpha = 0.18f),
            topLeft = Offset(left, bandTop),
            size = Size(mmW, bandH),
        )
        // 小窗（`window_preset = system` 时没有矩形，这一层自然不画）
        preview.windowRect?.let { wr ->
            drawRect(
                color = colors.accent,
                topLeft = Offset(
                    left + mmW * (wr.left / preview.screenW),
                    top + mmH * (wr.top / preview.screenH),
                ),
                size = Size(
                    mmW * (wr.width / preview.screenW),
                    mmH * (wr.height / preview.screenH),
                ),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
    drawRoundRect(
        color = colors.outline.copy(alpha = 0.6f),
        topLeft = Offset(left, top),
        size = Size(mmW, mmH),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 设备外框的宽度：宽到一眼能看出是台手机，又不至于把屏幕压得太小。 */
private val DeviceBezel = 6.dp

/** 底衬覆盖屏幕底部的高度比例（与真机实测的压暗区一致）。 */
private const val SCRIM_HEIGHT_RATIO = 0.58f

/** 底衬最深处的黑度。再深会压掉壁纸的细节，更浅则对比度不够。 */
private const val SCRIM_MAX_ALPHA = 0.26f

/**
 * 图标细白描边的**固定**半透明度。
 *
 * 与真机的 `FanOverlay.ICON_EDGE_COLOR = 0x88FFFFFF` 对齐（`0x88 / 255 ≈ 0.533`）——
 * 这是参照实现 `FanOverlayView.java:117-119` 的设计值，不是「被调淡的残值」。
 *
 * ⚠️ 它**不跟 `icon_alpha`**（2026-09-19 解耦）：真机上细边是独立一笔、恒定 53%，
 * 只有**图标本体**受 `icon_alpha` 控制。预览若继续跟着淡，就会与真机对不上。
 */
private const val ICON_EDGE_COLOR_ALPHA = 0.533f

/**
 * 裁切边缘的**抗锯齿底**半透明度，与真机 `FanOverlay.ICON_AA_BACKING_COLOR`
 * （`0xF0FFFFFF`，`0xF0 / 255 ≈ 0.941`）对齐。
 *
 * 为什么需要它：`clipPath` 带抗锯齿 → 形状边缘那圈像素 alpha 是中间值
 * （典型 0.3–0.6）→ 缩放到当前帧尺寸时再被采样混一次 → 边界发虚。
 * 用户报的「`icon_alpha` 拉到 100% 还是有点透明」就是这一圈，
 * **不是** alpha 参数没生效（那个参数是乘性的，100% 时乘 1）。
 *
 * ⚠️ 这个值与真机**必须同步**：两边不一致会让预览与真机在边界上出现
 * 肉眼可辨的差异，而那正是这块代码反复在防的「预览说谎」。
 */
private const val ICON_AA_BACKING_ALPHA = 0.941f

/** 触发区：两个下角的热区矩形，不可用的那一角画淡（如实表达「这一角不响应」）。 */
private fun DrawScope.drawHotZones(
    preview: FanPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
) {
    fun rect(r: io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect, enabled: Boolean) {
        val w = r.width * scale
        val h = r.height * scale
        if (w <= 0f || h <= 0f) return
        drawRect(
            color = if (enabled) colors.accent.copy(alpha = 0.35f) else colors.outline.copy(alpha = 0.15f),
            topLeft = Offset(sx(r.left), sy(r.top)),
            size = Size(w, h),
        )
        drawRect(
            color = if (enabled) colors.accent else colors.outline.copy(alpha = 0.5f),
            topLeft = Offset(sx(r.left), sy(r.top)),
            size = Size(w, h),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    rect(preview.hotLeft, preview.leftEnabled)
    rect(preview.hotRight, preview.rightEnabled)
}

/**
 * 出厂默认的小窗矩形（= Flyme 实测那一块）。
 *
 * 抽成函数是因为它有**两个**用处，而两处必须算出同一个矩形：关闭态的虚线参照框、
 * 以及打开态下「离默认有多远」的对比参照。散成两段就地计算的代码，迟早只改一处。
 *
 * 几何仍然只来自 [WindowBounds] —— 这里不出现任何数字。
 */
private fun defaultWindowRect(preview: FanPreview): ScreenRect =
    WindowBounds.defaults(preview.screenW, preview.screenH)

/** 小窗摆放：窗口矩形（`window_preset = system` 时 **null**，那时画的是默认参照框）。 */
private fun DrawScope.drawWindow(
    preview: FanPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
) {
    // `window_preset = system`（windowRect == null）：按**澎湃默认位置**画一块实心蓝窗，
    // 比例为真机实测（2026-10-25，1220×2656 竖屏）：澎湃默认小窗逻辑尺寸 1220×1952、缩放 0.70，
    // 屏上落在 x 183–1036 / y 289–1654 → 宽 70%、高 51.4%、水平居中、上沿 10.9%。不画虚线参照框、
    // 不再配「位置由系统决定」的小字（用户 2026-10-25）。四个滑杆在这个预设下本来就不参与运算。
    val system = preview.windowRect == null
    val r = preview.windowRect ?: run {
        val sw = preview.screenW * 0.7f
        val sh = preview.screenH * 0.514f
        val left = (preview.screenW - sw) / 2f
        val top = preview.screenH * 0.109f
        ScreenRect(left, top, left + sw, top + sh)
    }
    val w = r.width * scale
    val h = r.height * scale
    if (w <= 0f || h <= 0f) return
    val radius = min(w, h) * 0.08f

    // 1) 当前窗口：先铺填充（描边留到最后 —— 见下面第 3 步）
    drawRoundRect(
        color = colors.accent.copy(alpha = 0.28f),
        topLeft = Offset(sx(r.left), sy(r.top)),
        size = Size(w, h),
        cornerRadius = CornerRadius(radius, radius),
    )

    // 2) 出厂默认的参照框（虚线）
    //
    // 「这个参数到底有没有生效」在位置类参数上很难自证：窗口本来就占 80% 宽，
    // `x` 的自由度只有 20% 屏宽，拖到头也只挪一小段。所以额外画一圈**默认值**的框，
    // 让「离默认有多远」变成一眼可见的东西。
    //
    // **画在填充之后、描边之前**是刻意的：两块矩形只差一点时（比如只挪了 x），
    // 先画的虚线会被后画的实心填充盖住 —— 参照框就白画了。这个顺序下它永远不会被埋。
    // 只在当前位置 ≠ 默认时才画：与默认重合时再画一圈虚线，只会让人以为有两个窗口。
    // 几何仍然取自 core（[defaultWindowRect]）—— 这里一个数都不自己算。
    val fallback = defaultWindowRect(preview)
    if (!system && fallback != r && fallback.width > 0f && fallback.height > 0f) {
        val fw = fallback.width * scale
        val fh = fallback.height * scale
        drawRoundRect(
            color = colors.outline.copy(alpha = 0.85f),
            topLeft = Offset(sx(fallback.left), sy(fallback.top)),
            size = Size(fw, fh),
            cornerRadius = CornerRadius(min(fw, fh) * 0.08f, min(fw, fh) * 0.08f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            ),
        )
    }

    // 3) 当前窗口的边：最后画，保证它在最上面
    drawRoundRect(
        color = colors.accent,
        topLeft = Offset(sx(r.left), sy(r.top)),
        size = Size(w, h),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * 扇形：弧、每一项的图标位、第 1 项的选中态（放大 + 描环），
 * 以及在调展开行程时额外画的**起点虚影**。
 *
 * 选中态画在**第 1 项**上：它是用户清单里的第一项，也是他最认得出的那个 ——
 * 用它来展示「选中放大 / 选中描环」两个参数的效果，比画一个随机的项容易看懂。
 *
 * ## 形状 / 描边 / 不透明度：三个数都取自 [FanPreview]，这里一个都不推
 *
 *  - **形状**用 `IconShapes.outline(preview.iconShapeParams, …)`（**解析后**的参数）——
 *    用户拖圆角/柔度滑块时预览要跟着变，用形状默认参数的话预览就是死的；
 *  - **描边**的开关与线宽来自 `iconEdgeEnabled` / `iconEdgeWidthPx`（已由 core 做 dp→px），
 *    它的 alpha **固定 53%、不跟 `iconAlpha`**（2026-09-19 解耦）；
 *  - **不透明度**来自 `preview.iconAlpha`，只作用于**图标本体**
 *    （`drawImage` 的 `alpha`），与描边**各自独立**。
 *
 * ⚠️ 这两条是 2026-09-19 解的耦，与上一版**正好相反**：上一版两者共用同一个 alpha
 * （因为真机上细边烙在图标位图里，天然一起淡）。改成解耦的理由见
 * `FanOverlay.iconEdgePaint` —— 简言之「边是边界标记，跟着淡就失去意义」。
 * 预览必须跟着改，否则会重新制造出「预览里描边跟着淡、真机上不淡」这类对照。
 */
private fun DrawScope.drawFan(
    preview: FanPreview,
    colors: FpColors,
    scale: Float,
    sx: (Float) -> Float,
    sy: (Float) -> Float,
    openToRight: Boolean,
    icons: List<ImageBitmap?>,
    /**
     * 这一帧的动画状态（`null` = 画终态）。
     *
     * 三个量与真机同源（见 [FanAnimation]）：位置在**起点与终点之间**插值、直径乘
     * `0.78 + 0.22 × reveal`、每项绕自身中心自转（幅度按项错峰、到点归零）。
     * 起止两点都来自 core 的 [FanPreviewItem]，这里只做插值 —— 不重推几何。
     */
    anim: FanAnimation?,
) {
    val cx = sx(preview.cornerX(openToRight))
    val cy = sy(preview.cornerY)
    val fade = anim?.alpha ?: 1f

    // 弧（半径 = **求解后**的半径，不是配置初值）—— 只作参考线，画淡一点
    val r = preview.radius * scale
    if (r > 0f) {
        drawArc(
            color = colors.outline.copy(alpha = 0.4f),
            // 角度取自 core 的 FanArcs（不是这里的字面量：改了扇的张角，参考线要跟着走）。
            // 屏幕坐标里 y 朝下，而几何的角度是「x 朝屏内、y 朝上」，所以取负值：
            // 12°..82° 在 drawArc 里就是「从 −82° 起、扫过 70°」
            startAngle = -FanArcs.END_DEG.toFloat(),
            sweepAngle = FanArcs.SPAN_DEG.toFloat(),
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    // 弧心（留距的直接体现：它不在屏幕角上）
    drawCircle(colors.outline.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(cx, cy))

    val iconAlpha = preview.iconAlpha
    val edgeWidthPx = preview.iconEdgeWidthPx * scale
    val drawEdge = preview.iconEdgeEnabled && edgeWidthPx > 0f
    for (item in preview.items) {
        val selected = item.index == 0
        // 直径：选中放大 × 展开期的 0.78→1 倍率（两者都是乘数，真机也是这么叠的）
        val diameter = (if (selected) item.diameter * preview.selectionScale else item.diameter) *
            (anim?.diameterScale ?: 1f)
        val radiusPx = diameter / 2f * scale
        if (radiusPx <= 0.5f) continue
        val endCenter = Offset(sx(item.centerX(openToRight)), sy(item.centerY))
        // 展开期：从**起点**（core 给的那一项 startCenter*）线性走到终点。
        // 起点本身就是「按 1 − reveal_amount% 缩到弧心附近」的位置，所以这里只需要一个 lerp ——
        // 插值公式与真机的 `圆心 + (中心 − 圆心) × revealFactor` 逐项等价。
        val center = if (anim == null) endCenter else {
            val startCenter = Offset(sx(item.startCenterX(openToRight)), sy(item.startCenterY))
            Offset(
                startCenter.x + (endCenter.x - startCenter.x) * anim.reveal,
                startCenter.y + (endCenter.y - startCenter.y) * anim.reveal,
            )
        }

        // ⚠️ 这里原来画过一层「展开起点虚影 + 一条从弧心指过去的虚线」。2026-09-19 删掉：
        // 动效那一页改成**真播一遍展开动画**（`FanAnimation`），而真正的那一项正在从
        // 起点飞出来，再叠一层静态虚影只会让人以为画重了。
        // 静态页面上也不再需要它 —— 用户要判断的是终态的排布。

        // 形状走 core 的 IconShapes.outline（与真机同一份公式）：圆心到轮廓的偏移
        // 就是「离中心 -size/2」——轮廓的坐标系是 [0,size]²、原点在左上
        val path = Path()
        val outline = IconShapes.outline(preview.iconShapeParams, diameter)
        var i = 0
        while (i < outline.size) {
            val px = center.x + (outline[i] - diameter / 2f) * scale
            val py = center.y + (outline[i + 1] - diameter / 2f) * scale
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            i += 2
        }
        path.close()

        // 有真图标就画真图标，没有才退回占位填充。两支都**跟随 icon_alpha**
        // （真机同一条），所以调低不透明度时预览里就该看出「这一项有多淡」；
        // 展开期再乘一次整体淡入（窗口那一层的 alpha），于是第一帧是「淡淡的影子」。
        val alpha = iconAlpha * fade
        // 自转绕**图标自身中心**（与真机 `rotate(angle, x2, y2)` 一致）。
        // 幅度按项错峰、到点归零 —— 这就是参数页里「自转」那个滑杆在调的东西。
        rotate(anim?.rotationFor(item.index) ?: 0f, pivot = center) {
            val icon = icons.getOrNull(item.index)
            if (icon != null) {
                val side = (radiusPx * 2f).roundToInt().coerceAtLeast(1)
                // 形状裁切用**同一个 path**：它就是 core 的 IconShapes.outline 采样结果，
                // 与真机 `IconShapeMask` 用的是同一个函数。位图侧的 cover 放大系数
                // 也来自同一处（FanIconPreview.COVER_SCALE），所以「图标出血多少」两边一致。
                clipPath(path) {
                    // 抗锯齿底：与真机 `FanOverlay.circularIcon` 的 `ICON_AA_BACKING_COLOR`
                    // 是**同一件事**（真机那边也在 clip 之后、drawable 之前铺一层近全透明的白）。
                    //
                    // 为什么两边都要：`clipPath` 带抗锯齿 → 边缘那圈像素 alpha 是中间值
                    // （0.3–0.6），而缩放到当前帧尺寸时又会被采样混一次 → 边界发虚。
                    // 用户报的「`icon_alpha` 拉到 100% 还是有点透明」就是这一圈，
                    // **不是** alpha 参数的问题（它是乘性的，100% 时乘 1）。
                    //
                    // 预览必须同一条公式，否则会出现「预览里边缘是干净的、真机上发虚」
                    // （或反过来）—— 那正是这段代码反复在防的「预览说谎」。
                    // 用 `Color.White` + 与真机相同的 alpha，色相差异由预览的主题化底色吸收。
                    drawRect(
                        color = Color.White.copy(alpha = ICON_AA_BACKING_ALPHA),
                        topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
                        size = Size(radiusPx * 2f, radiusPx * 2f),
                    )
                    drawImage(
                        image = icon,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(icon.width, icon.height),
                        dstOffset = IntOffset(
                            (center.x - radiusPx).roundToInt(),
                            (center.y - radiusPx).roundToInt(),
                        ),
                        dstSize = IntSize(side, side),
                        alpha = alpha,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else {
                drawPath(path, color = colors.surface.copy(alpha = alpha))
            }
            if (drawEdge) {
                // ⚠️ 描边用**固定的 alpha**，**不跟 `iconAlpha`**（2026-09-19 解耦）。
                //
                // 上一版这里写的是 `colors.outline.copy(alpha = alpha)`，与真机
                // 「细边烙在位图里、跟图标一起淡」对应。真机现在改成独立一笔、恒为
                // `ICON_EDGE_COLOR`（53% 白），预览必须同一条公式 —— 否则会重新制造出
                // 「预览里描边跟着淡、真机上不淡」这类说不清的对照（正是这段注释原先
                // 想防住的那种问题，只是方向反了）。
                //
                // 为什么锚在 `colors.outline` 而不是硬编码白色：预览的底色是主题化的，
                // 它会自己调 `ICON_EDGE_COLOR_ALPHA` 这一个量（半透明度），色相仍跟主题走
                // —— 与真机「白色 + 53%」在**相对关系**上一致（都是「半透明的描边」），
                // 而预览本来就不该假装自己是壁纸。
                // 真机的细边整条画在图标**外面**（2026-10-29，压在图标最外圈会显得图标发透）：
                // 轮廓按圆心放大半个线宽再描，线宽同比缩回，效果是边贴着图标外缘、不盖图标
                val k = (radiusPx + edgeWidthPx / 2f) / radiusPx
                withTransform({ scale(k, k, pivot = center) }) {
                    drawPath(
                        path,
                        color = colors.outline.copy(alpha = ICON_EDGE_COLOR_ALPHA),
                        style = Stroke(width = edgeWidthPx / k),
                    )
                }
            }
        }
        if (selected && preview.selectionRing) {
            // 选中描环：半径与线宽都取自 core（`hitRadius` / `ringStrokePx`），不是这里拍的。
            // 它**不受 iconAlpha 影响** —— 选中环跟着淡掉的话，选中的那一项反而最看不清；
            // 但它受展开期的整体淡入影响（真机上淡入作用在整层 View 上）。
            drawCircle(
                color = colors.accent.copy(alpha = fade),
                radius = preview.hitRadius * scale,
                center = center,
                style = Stroke(width = preview.ringStrokePx * scale),
            )
        }
    }
}
