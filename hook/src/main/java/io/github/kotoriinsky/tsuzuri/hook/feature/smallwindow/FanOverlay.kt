package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import io.github.kotoriinsky.tsuzuri.core.geometry.FanArcs
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.OverlayArbiter
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShape
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapes
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 扇形的视觉常量。放在**文件级**是因为 [FanOverlay]（建窗口）和 [FanView]（自绘）
 * 都要用，而两边的 `companion object` 都是 private、互相看不见。
 */

/** 选中描环：**深色（近黑）**，实测。 */
private const val RING_COLOR = 0xFF1B1B1B.toInt()

/** 「⋯」按钮的蓝色。具体色值未实测。 */
private const val MORE_COLOR = 0xFF2B7BF3.toInt()

/** 展开进度离 1 小于它就算动画已停：此时图标按整像素、不缩放地画（见 `FanView.drawSlot`）。 */
private const val STEADY_EPSILON = 1e-3f

/**
 * 扇形的一页：应用项 + 与之一一对应的圆形图标位图（扇上不显示名字，所以没有文字）。
 * [selectedIcons] 是按「选中放大后」的尺寸另画的一份（选中放大 ≤ 1 时为空，用 [icons]）。
 */
internal class FanPage(val items: List<FanTarget>, val icons: List<Bitmap?>, val selectedIcons: List<Bitmap?> = emptyList())

/**
 * 细白边的绘制材料 —— [FanOverlay] 备好、[FanView] 每帧拿来描一笔。
 *
 * ## 为什么要有这么一个东西（2026-09-19 解耦的产物）
 *
 * 细边原先是**烙在图标位图里**的（与 drawable 画进同一张 `ARGB_8888`），
 * 于是位图由 `iconPaint`（alpha = `icon_alpha`）画出去时边也跟着淡。
 * 那条路有一个用户实测的后果：**`icon_alpha` 拉到 100% 时图标依然有点透明** ——
 * 因为 `ICON_EDGE_COLOR` 自带 53% 的 alpha，而它是图标边界唯一的视觉来源。
 * 完整论证见 [FanOverlay] 的 `iconEdgePaint`。
 *
 * 解耦之后边必须在**绘制期**画，而三样材料分属两个类：
 *
 * | 材料 | 属于 | 为什么 |
 * | --- | --- | --- |
 * | `Paint`（颜色 53% 白 + 线宽） | [FanOverlay] | 线宽每屏只设一次，`prepareShape` 里 |
 * | 正圆半径 `circleRadius` | [FanOverlay] | `IconShapeMask.prepare` 的产物 |
 * | 多边形路径 `path` | [FanOverlay] | 同上（[IconShapes] 采样结果） |
 *
 * 所以用这个不可变快照把三样一起递进 [FanView] —— 与 [FanPage] 递图标位图同一个思路。
 *
 * ## 这两个字段互斥，恰好有一个有效
 *
 * `circleRadius > 0` ⟺ 当前形状是**正圆**（缺省那档，走 `drawCircle`）；
 * 否则按 [path] 描边。这个「二选一」是从 [IconShapeMask.Prepared.edgeCircleRadius]
 * 原样继承的语义，不要在这里重新判断形状枚举 —— 那会让两处各判一次、迟早不一致。
 */
internal class FanEdgeDrawing(
    /** 画笔（含 `ICON_EDGE_COLOR` 与最终线宽）。**alpha 恒为 53%，不受任何参数影响。** */
    val paint: Paint,
    /** 正圆的细边半径（**按 `spec.iconDiameter` 算好的值**）；`0` = 按 [path] 描边。 */
    val circleRadius: Float,
    /** 非正圆形状的轮廓路径（坐标原点在其包围盒左上角）。`circleRadius > 0` 时不使用。 */
    val path: Path,
)

/**
 * 底角扇形的浮层。
 *
 * ```
 * FrameLayout (root, 全屏, FLAG_NOT_TOUCHABLE)
 *  └─ FanView  自绘：圆形图标 + 选中描环 + 末尾「⋯」翻页槽
 * ```
 *
 * ## 几何全部来自 [FanSpec]
 *
 * 每一项的中心由 [FanSpec.itemX] / [FanSpec.itemY] 给出，命中由 [FanSpec.hitTest] 给出，
 * **绘制与命中共用同一份顶点数组**（在 [FanConfig.spec] 里算好）——
 * 一旦各算各的，就会出现「看到的图标」和「碰到的图标」对不上。
 *
 * ## 窗口规格照 Flyme 的实测
 *
 * Flyme 那边 `dumpsys window` 的原文是
 * `mAttrs={(0,0)(fillxfill) ty=APPLICATION_OVERLAY fmt=RGBA_8888
 * fl = LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS HARDWARE_ACCELERATED}`、`Requested w=1200 h=2670`
 * —— **全屏窗口，扇只是画在里面**（不是贴角的小窗）。这里逐条对齐：
 * 全屏 + `LAYOUT_IN_SCREEN` + `LAYOUT_NO_LIMITS` + `RGBA_8888` + `HARDWARE_ACCELERATED`。
 *
 * 两处**有依据的偏离**：
 *
 *  1. **多加 `FLAG_NOT_TOUCHABLE`**。Flyme 的窗口是可触摸的（它的 flags 里没有这一条），
 *     但我们**必须**不可触摸：触摸来自 WMShell 的输入监视通道，再加一个能吃触摸的窗口
 *     就是同一条边缘上的第二个 touch owner。命中走 [FanSpec.hitTest]。
 *  2. **多加 `FLAG_NOT_FOCUSABLE`**：绝不抢输入焦点、绝不改 IME 目标。
 *
 * 变暗：Flyme 用魅族私有 flag `SHOW_WINDOW_MODE_DIM` + 一个独立的 `windowModeDim` 窗口
 * 我们没有那个 flag，**选了 `FLAG_DIM_BEHIND` + `dimAmount`** ——
 * 它就是 WM 提供的「独立变暗层」，语义和 Flyme 的独立 dim 窗口最接近，而且不用自己再画
 * 一层全屏 alpha（那要多一次全屏合成）。代价是变暗没有淡入：Flyme 的 dim 动画在
 * 未实测，没有可对齐的目标值。
 *
 * 模糊：对照机上桌面被强模糊，这里**尝试** `setBlurBehindRadius`（公开 API 31+），
 * 设备不支持时 WM 会忽略它（不崩）。这是与侧滑索引浮层不同的一处选择 —— 那边明确不做模糊
 * （多一次 GPU 合成），但底角扇的模糊是实测到的 Flyme 视觉要素，且只在手势那 1~2 秒存在。
 *
 * ## 线程纪律
 *
 * 所有 public 方法**只允许在主线程**调（`addView` / `removeViewImmediate` 是 ViewRootImpl
 * 的操作，从别的线程调会把 View 树挂到那个线程上，之后主线程的任何交互都会出问题）。
 * 调用方是 [CornerSmallWindow] 里的主线程 Handler。这里再兜一道：不在主线程就记一条日志
 * 并返回，宁可什么都不做，也不能把宿主的窗口系统弄脏。
 */
internal class FanOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val vibratorUsable = vibrator?.hasVibrator() ?: false

    /**
     * 扇形是不是正展开着。
     *
     * **输入线程读它**：面板已展开时不再装甲，
     * 也避免一次手势在扇形上又弹出第二个扇形。
     */
    @Volatile
    var visible = false
        private set

    /**
     * **悬停中**：抬手没选中任何图标 → 菜单留在屏上（2026-09-20 用户拍板，照 Flyme，
     * `GestureAppLauncher.p()` 那条「没有高亮图标 → return true、不收窗」）。
     *
     * **输入线程在 `onDown` 里读它**，决定这次 DOWN 是「续选」（落在图标上 → 直接进选图
     * 模式）还是「收掉悬停、放行」。与 [visible] 一样是 volatile 引用读。
     *
     * 与 Flyme 的差别（有意的）：Flyme 悬停期间底角新手势被拒、没有超时、
     * 菜单不可触摸只能等外部事件 —— 照抄等于一个点不动的死菜单。
     * 我们的退出：下一次手势收尾（命中 → 启动 / 落空 → 收），或 [HOVER_TIMEOUT_MS] 超时。
     */
    @Volatile
    var hovering = false
        private set

    /**
     * 已经卸载。
     *
     * 卸掉之后可能还有一两个排进主线程队列的渲染请求（`removeCallbacks` 摘不掉已经在跑的
     * 那个），没有这道闸门它们会把窗口重新建出来 —— 那就违反了「关态必须与未安装完全一致」。
     */
    @Volatile
    private var released = false

    private var root: FrameLayout? = null
    private var fan: FanView? = null
    private var animator: ValueAnimator? = null

    private var spec: FanSpec? = null
    private var openToRight = false

    /** 全部页。应用超过一页时，每页末尾多一个「⋯」槽用来翻页。 */
    private var pages: List<FanPage> = emptyList()
    private var page = 0

    /** 「⋯」占的槽位下标；-1 = 没有（应用装得下，不需要更多）。 */
    private var moreSlot = -1

    /** 上一次命中的槽位，用于「⋯」的边沿触发（停在上面不该连续翻页）。 */
    private var lastSlot = -1

    private var lastTickAt = -1L

    /**
     * 预渲染图标用的**复用**对象（见 [circularIcon]）。
     *
     * 它们只在 `show()` 里被主线程用一次，但 `FanOverlay` 是常驻单例
     * （[CornerSmallWindow] 只建一个），每次展开都新建 `Path` / `Paint` 纯属浪费；
     * 复用还能顺手保证「绘制期的分配」只有位图那一种。
     */
    private val iconClip = Path()

    /**
     * 细白边的轮廓。与 [iconClip] 同一件事的第二条路径（比它再缩一个线宽）。
     *
     * 为什么要两条：[iconClip] 决定**裁**到哪里，细边必须画在裁切**之后**且**再往里**
     * 缩半个线宽 —— 否则线的外半边会被裁掉（原来是 `drawCircle(半径 − 线宽/2)`，
     * 换成形状之后同样要「按轮廓缩进去描」）。
     */
    private val iconEdgePath = Path()

    /**
     * 选中尺寸那一份位图用的裁切轮廓与（用不到的）描边轮廓。
     *
     * 为什么要第二份位图（2026-10-29，用户：「拉到 100% 图标还是发虚」）：位图原来只按静止尺寸画，
     * 选中那一项要放大到 `selectionScale`（默认 122%），等于把小图拉大来画，必然发糊。
     * 按选中尺寸另画一份，选中时直接用它，不再放大采样。
     */
    private val iconClipSelected = Path()
    private val scratchEdgePath = Path()

    /** 这一屏要不要按形状裁（`false` = 方形：保留原图边界，见 [IconShapes]）。 */
    private var clipIcons = true

    /**
     * 细边的**圆**半径（px）；`> 0` 时用 `drawCircle` 画、`0` 时按 [iconEdgePath] 描边。
     *
     * 缺省形状（正圆）走的就是 `drawCircle` —— 与上一版逐字相同的那一句，
     * 见 [IconShapeMask.Prepared] 的说明。
     */
    private var edgeCircleRadius = 0f

    /**
     * 细白边的画笔。**画在 [`FanView`] 上**（不再烙在图标位图里）。
     *
     * ## 线宽从哪来（②）
     *
     * `strokeWidth` **不在字段初始化时定**，而是在 [prepareShape] 里按
     * `spec.iconEdgeWidthPx` 设一次：那个值 = `icon_edge_width`(dp) × density，
     * 换算在几何发布时做完了（[FanIconEdge] 的注释里写了「dp 当 px 用」那个历史 bug）。
     * 在这里再乘一次 density 就会变成 dp²，是很容易写出来的一种错。
     *
     * ## ⚠️ 这条边**故意不跟** `icon_alpha`（2026-09-19 拍板，别改回去）
     *
     * 上一版把细边**烙在图标位图里面**（和 drawable 画进同一张 `ARGB_8888`），
     * 于是描边由 `iconPaint`（alpha = `icon_alpha`）一起画出去 —— 两者天然共用同一个
     * alpha，`icon_alpha` 一降低边就淡。当时的注释把这写成「耦合的正确实现」。
     *
     * **那是错的**，理由是一条用户实测现象：`icon_alpha` 拉到 100% 时图标**依然看着有点透明**。
     * 因为 [ICON_EDGE_COLOR] 自带 `0x88`（= 53%）的 alpha，而这条边在「没有白底」
     * 的前提下**是图标边界唯一的视觉来源**（见 [circularIcon] 的证据段）——
     * 它最高只有 53% 白，与壁纸混色后就表现为「整个图标发灰」。
     * 换句话说：`icon_alpha` 号称是「图标不透明度」，实际却连边都管不住，
     * 而它**唯一能调的那一层**（位图）在 100% 时本来就没有可调空间。
     *
     * 现在两者**解耦**：
     *
     * ```
     * 图标本体  位图 → iconPaint（alpha = icon_alpha）   ← 可调
     * 细白边    独立一笔 → 本画笔（ICON_EDGE_COLOR 固定） ← 恒定 53%
     * ```
     *
     * 「细边恒定 53%」不是我拍的：参照实现 `FanOverlayView.java:117-119` 初始化
     * `iconStrokePaint` 时就是 `setColor(-1996488705)`（= `0x88FFFFFF`），
     * 全程**只设这一次**、任何地方都不再改 alpha（那边压根没有「图标不透明度」这个概念）。
     * 所以 53% 是**设计值**，不是「被调淡之后的残值」。
     *
     * 代价（写清楚，别让下一个人以为漏了）：解耦之后**拉低 `icon_alpha` 时图标会淡、
     * 白边保持实心**。这与上一版的行为**正好相反**，是这次拍板要的效果 ——
     * 用户要的是「调图标的不透明度」，而边是**边界标记**（见 [ICON_EDGE_COLOR] 的说明），
     * 它跟着淡就失去了存在的意义。真要连边一起淡，那是另一个参数，不是这条。
     */
    private val iconEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 初始值只是占位：每次展开都会在 [prepareShape] 里按当前 spec 重设
        strokeWidth = FanIconEdge.DEFAULT_WIDTH_DP * density
        color = ICON_EDGE_COLOR
    }

    /** 这一屏的细边要不要画（`icon_edge`）。见 [FanIconEdge]。 */
    private var edgeEnabled = true

    // ── 生命周期（全部主线程） ─────────────────────────────────────────

    /**
     * 展开扇形。**主线程**。
     *
     * @param x,y 手指当前位置：弹出这一帧就要有高亮的项，所以顺手做一次命中
     * @return 是否真的展开了
     */
    fun show(
        spec: FanSpec,
        pages: List<List<FanTarget>>,
        openToRight: Boolean,
        x: Float,
        y: Float,
    ): Boolean {
        if (released) return false
        if (!onMain("show")) return false
        if (pages.isEmpty() || pages[0].isEmpty()) return false
        val wm = this.wm ?: return false

        dismissInternal()

        val root = buildWindow(wm) ?: return false
        this.spec = spec
        this.openToRight = openToRight
        this.page = 0
        this.lastSlot = -1
        // 形状轮廓**每屏建一次**（形状只与 形状/尺寸/线宽 有关，每一项都一样）。
        // 必须在 `circularIcon` 之前：那里要拿它去裁每一个图标。
        prepareShape(spec)
        val restSize = restIconSize(spec)
        val selSize = selectedIconSize(spec)
        this.pages = pages.map { page ->
            FanPage(
                page,
                page.map { t -> circularIcon(spec, t, restSize, iconClip) },
                if (selSize > restSize) page.map { t -> circularIcon(spec, t, selSize, iconClipSelected) } else emptyList(),
            )
        }
        // 图标不透明度（`icon_alpha`）**在展开前设一次**、不在 onDraw 里每帧算：
        // 它设在 [FanView] 的 `iconPaint` / `placeholderPaint` 上（那两把画笔是**每次展开新造**的，
        // 所以每次展开都会按当前 spec 重新设一遍 —— 这正是「拖滑杆改完立刻生效」的链路：
        // 拖滑杆 → CornerSmallWindow 重发几何 → 下一次展开读到新的 spec）。
        // ⚠️ **只动画图标的那几把画笔**：选中描环（`ringPaint`）、「⋯」按钮（`morePaint`）、
        // 以及**细白边**（`iconEdgePaint`，见那里的论证）都保持自己的不透明度 ——
        // 环跟着变透明的话「选中的那一项」反而最看不清，正好和选中反馈的意图相反；
        // 白边是边界标记，跟着淡就失去意义（2026-09-19 从「跟图标一起淡」改成恒定 53%）。

        // 槽位数**由几何给出**（`CornerSmallWindow.publish` 算好后放进 spec），
        // 浮层不自己数一遍：多页时末位是「⋯」翻页槽，它也是几何里的一个槽，
        // 参与层分配与半径修正。两边各数一次迟早会不一致。
        this.moreSlot = if (pages.size > 1) spec.count - 1 else -1

        // 细白边的材料在 `prepareShape` 里已经备好（画笔线宽、正圆半径、多边形路径）。
        // `edgeEnabled` 为 false（`icon_edge` 关掉，或线宽 0）时给 `null` —— **一笔都不画**，
        // 那是用户要的「完全没有描边」，不是「画一条透明/极细的边」。
        val edgeDrawing = if (edgeEnabled) {
            FanEdgeDrawing(iconEdgePaint, edgeCircleRadius, iconEdgePath)
        } else {
            null
        }
        val v = FanView(context, spec, openToRight, moreSlot, edgeDrawing)
        v.setPage(this.pages[0])
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        fan = v
        visible = true
        // 让侧滑索引知道「扇形在场」：它接管之前会查这个标志（见 OverlayArbiter）
        OverlayArbiter.fanShowing = true

        lastSlot = spec.hitTest(x, y, openToRight)
        v.selection = lastSlot
        startReveal(v, spec)
        // 呼出那一下的振动（只在「刚弹出」和「选择变化」时振）。
        // 走 trigger_haptic 而不是 haptic —— 这两个是**分开**的开关
        // （HyperGesture 的 `BottomTriggerHapticPolicy.shouldVibrate(global, bottomTrigger)`），
        // 用户可能想留着选择反馈但不要呼出提示。
        if (spec.triggerHaptic) tick()
        // 兜底：输入流被系统中途掐断（另一根手指按了 home 等）时，别让浮层永久留着
        main.postDelayed(staleTick, STALE_TIMEOUT_MS)
        return true
    }

    /** 手指位置更新：重新命中 + 重绘 + 「⋯」翻页。**主线程**。 */
    fun pointer(x: Float, y: Float) {
        if (released) return
        if (!onMain("pointer")) return
        val v = fan ?: return
        val s = spec ?: return
        val idx = s.hitTest(x, y, openToRight)

        // 「⋯」是边沿触发：手指刚挪到它上面就换一页；停着不动不会再翻。
        if (idx == moreSlot && moreSlot >= 0 && lastSlot != moreSlot && pages.size > 1) {
            page = (page + 1) % pages.size
            v.setPage(pages[page])
            if (s.haptic) tick()
        }
        lastSlot = idx

        if (idx == v.selection) return
        v.selection = idx
        if (s.haptic) tick()
    }

    /**
     * 抬手提交：算出落在哪个图标圆里，收窗，把那一项交给调用方启动。**主线程**。
     *
     * 返回 null = **什么都不启动**，两种情形：
     *  - 手指不在任何图标的命中圆里（`GestureGeometry.selection` 返回 -1 也是取消）；
     *  - 落在末尾的「⋯」上（它是翻页按钮，不是应用）。
     *
     * 误启动一个应用比什么都不做糟得多（侧滑索引那边也是同一条判断）。
     *
     * ## 未选中时的两种结局（[allowHover]，2026-09-20 拍板「悬停 + 可再选 + 超时」）
     *
     *  - `allowHover = true`（首次手势抬手）：**悬停** —— 菜单留在屏上、清掉高亮，
     *    等 [HOVER_TIMEOUT_MS] 超时或下一次手势收尾。照 Flyme（`GestureAppLauncher.p()`
     *    抬手无高亮 → return true → 不走收窗链），但我们有超时、且下一次手势可直接续选
     *    （Flyme 悬停期间新手势被拒、只能等外部事件，照抄等于一个点不动的死菜单）。
     *  - `allowHover = false`（悬停后的续选手势）：落空 = 收窗 —— 悬停只给**一次**再选机会，
     *    不无限循环。
     *
     * ⚠️ **偏离**：HyperGesture 抬手后会先播一个 150ms 的确认脉冲再收窗
     * （`FanOverlayView.java:677-680` + `FanOverlayController` 的 `confirmationDurationMs`）。
     * 我们**立刻收窗**：小窗启动本身有系统动画，压 150ms 只会让「抬手 → 应用起来」
     * 这个主观延迟变长，而反馈（图标高亮 + 启动动画）已经够了。
     */
    fun commit(x: Float, y: Float, allowHover: Boolean = true): FanTarget? {
        if (released) return null
        if (!onMain("commit")) return null
        val s = spec
        val p = pages.getOrNull(page)
        val idx = if (s != null) s.hitTest(x, y, openToRight) else -1
        val hit = if (idx >= 0 && idx == moreSlot) null else p?.items?.getOrNull(idx)
        if (hit != null) {
            dismissInternal()
            return hit
        }
        if (allowHover) {
            enterHover()
        } else {
            dismissInternal()
        }
        return null
    }

    /**
     * 进入悬停：菜单保留、清高亮、起超时。**主线程**。
     *
     * 悬停期间 `visible` 仍为 true（窗口还在），`OverlayArbiter.fanShowing` 也不清 ——
     * 侧滑索引仍然要让位（屏上确实有个扇形）。
     */
    private fun enterHover() {
        val v = fan
        if (v == null || spec == null) {
            dismissInternal()
            return
        }
        hovering = true
        v.selection = -1
        lastSlot = -1
        // show() 排下的 staleTick（10s 兜底）在悬停期间没有保护对象（没有活动手势），
        // 留着只会把 8s 的悬停截短 —— 摘掉，悬停的寿命归 [hoverTick] 管。
        main.removeCallbacks(staleTick)
        main.removeCallbacks(hoverTick)
        main.postDelayed(hoverTick, HOVER_TIMEOUT_MS)
        Log.i(TAG, "扇形悬停：抬手未选中，保留 " + HOVER_TIMEOUT_MS / 1000 + "s（或下一次手势收尾）")
    }

    /**
     * 结束悬停（不收窗）—— 下一次手势已被当作续选手势接住时调，摘掉超时，
     * 免得手势做到一半被它收走。输入线程可调（`removeCallbacks` 跨线程安全，
     * [hovering] 是 volatile）。
     */
    fun endHover() {
        if (!hovering) return
        hovering = false
        main.removeCallbacks(hoverTick)
    }

    /** 收窗。**主线程**。手势结束、取消、异常路径都走它。 */
    fun dismiss() {
        if (!onMain("dismiss")) return
        dismissInternal()
    }

    /**
     * 卸载时收干净。可以**从任何线程**调（[CornerSmallWindow.uninstall] 跑在框架线程上）：
     * 不在主线程就转投主线程。
     */
    fun release() {
        released = true
        if (onMain("release")) {
            dismissInternal()
        } else {
            main.post { dismissInternal() }
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 收窗 + **拆掉窗口**。
     *
     * 每次展开都重新建窗口、收起就拆掉（侧滑索引的浮层也是这个做法）：窗口常驻会在 dumpsys
     * 里多出一个空窗口，而「不用时必须和没装模块一样」是硬要求（规范 §9）。
     * 代价是每次展开多一次 `addView`（1ms 量级），换来的是关态干净。
     */
    private fun dismissInternal() {
        visible = false
        hovering = false
        main.removeCallbacks(hoverTick)
        OverlayArbiter.fanShowing = false
        main.removeCallbacks(staleTick)
        animator?.cancel()
        animator = null
        val r = root
        val v = fan
        if (r != null && v != null) r.removeView(v)
        fan = null
        root?.let { runCatching { wm?.removeViewImmediate(it) } }
        root = null
        spec = null
        pages = emptyList()
        page = 0
        moreSlot = -1
        lastSlot = -1
    }

    private fun buildWindow(w: WindowManager): FrameLayout? {
        val r = FrameLayout(context)

        // 层级与格式照 Flyme 实测，只多两条必需的 flag（见类注释）。
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val lp = WindowManager.LayoutParams(
            // 显式用物理屏幕尺寸，而不是 MATCH_PARENT。
            //
            // 实测症状：扇形离屏幕边缘差几个 pixel，贴边的项被裁掉一截。
            // 原因是 overlay 窗口即使带了 LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS，
            // `MATCH_PARENT` 仍可能被系统按状态栏 / 导航栏的 inset 收窄。
            // 给死尺寸就没有这个解释空间 —— 我们最终要覆盖的是整块屏。
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            // RGBA_8888：Flyme 实测就是它。它带 alpha 通道、逐像素合成，
            // 正是「全屏窗口上只画一个扇形」需要的。
            PixelFormat.RGBA_8888,
        ).apply {
            // 显式声明「不吃系统栏 inset」：光有 LAYOUT_IN_SCREEN / NO_LIMITS 还不够，
            // 窗口内容默认仍会被状态栏与导航栏 inset 裁掉 —— 变暗层就盖不到状态栏。
            fitInsetsTypes = 0
            gravity = Gravity.TOP or Gravity.START
            dimAmount = DIM_AMOUNT
        }
        // 跨窗口模糊：Flyme 的底角扇背后是强模糊。取不到/不支持就静默降级。
        runCatching { lp.setBlurBehindRadius((BLUR_DP * density).toInt()) }
        // 不标的话系统把这个触摸穿透的悬浮窗压到 alpha=0.8，图标整体只有 80%（真机 dumpsys 实测）
        io.github.kotoriinsky.tsuzuri.hook.feature.gesture.TrustedOverlay.mark(lp)

        if (!runCatching { w.addView(r, lp) }.isSuccess) {
            Log.w(TAG, "加扇形浮窗失败，本次不展开")
            return null
        }
        root = r
        return r
    }

    /**
     * 展开动画。时长与曲线照 HyperGesture：
     *
     * ```
     * 位移/缩放  基时长 170ms，easeOutBack      FanOverlayView.java:373-374,743-746
     * 每项自转   基时长 150ms，按项错峰         同文件 :702-725
     * 窗口淡入   基时长 110ms                   FanOverlayController.java:117-136
     * duration = max(45, base * 100 / speed)   FanOverlayView.java:727-729
     * stagger  = max(8, round(1200 / speed))   同文件 :723-725
     * ```
     *
     * 注意**只有自转是错峰的，位移和缩放不是**（`revealProgress` 用的是同一个
     * `revealStartedAt`、不分项）—— 上一版按项错开了整个出现时间，那是错的：
     * 它会让后面的项「晚一点才冒出来」，而 HyperGesture 是所有项一起飞出、
     * 只是各自的初始倾角按项延迟归零。所以总时长取两段里更长的那个。
     */
    private fun startReveal(v: FanView, spec: FanSpec) {
        val speed = spec.animationSpeed
        // 上限钳制是硬要求：`speed` 是**除数**，取值离谱时会算出几秒甚至几十秒的时长，
        // 而 `drawSlot` 在进度为 0 时**直接 return** —— 表现为「扇形完全不显示」，
        // 和「手势没触发」症状一模一样。这个耦合没法靠调参避免，只能在代码里兜住。
        val revealMs = max(45f, BASE_REVEAL_MS * 100f / speed).coerceAtMost(MAX_REVEAL_MS)
        val rotationMs = max(45f, BASE_ROTATION_MS * 100f / speed).coerceAtMost(MAX_REVEAL_MS)
        val staggerMs = max(8f, STAGGER_BASE_MS / speed).coerceAtMost(MAX_REVEAL_MS)
        val fadeMs = max(45f, BASE_FADE_MS * 100f / speed).coerceAtMost(MAX_REVEAL_MS)
        // 按层错峰（2026-10-25 用户选定）：同一层一起转，内层先、外层后，每层错开一档。
        // 原来按项编号错峰，内层几项的自转全落在飞出期里看不出来，只剩外层在动。
        // 每层间隔取「原来一项的间隔 × 每层平均项数」（用户反馈减半版层次不明显）。
        val rings = spec.ringCount
        val perRing = spec.count.coerceAtLeast(1).toFloat() / rings
        val ringStaggerMs = (staggerMs * perRing).coerceIn(staggerMs, rotationMs)
        val total = max(revealMs, rotationMs + ringStaggerMs * (rings - 1))
        val tail = max(total, fadeMs)

        v.revealMs = revealMs
        v.rotationMs = rotationMs
        v.staggerMs = ringStaggerMs
        v.fadeMs = fadeMs
        v.animMs = 0f
        v.alpha = 0f

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, tail).apply {
            duration = tail.toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val ms = it.animatedValue as Float
                v.animMs = ms
                v.alpha = (ms / fadeMs).coerceIn(0f, 1f)
                v.invalidate()
            }
            start()
        }
    }

    /**
     * 按当前形状建两个轮廓（裁切 / 细边），供这一屏的所有图标复用。**主线程**，`show()` 里调。
     *
     * 形状来自 `spec.iconShapeParams`（= `icon_shape` + 两个可调数值，由几何发布时解析好
     * 并冻结在快照里，见 [FanIconShape]）—— 所以「改完形状/圆角立刻生效」走的是与其它参数
     * 同一条链路（拖完滑杆 → `CornerSmallWindow` 重发几何 → 下一次展开读到新的 spec），
     * 而这里一次 Binder 都不读。
     *
     * 细边的线宽也在这里定（`spec.iconEdgeWidthPx`，已是 px）：
     *  - 轮廓的缩进取**线宽的一半** —— 描边以路径为中心画，缩进去整条边才落在位图里
     *    （与多边形那条 edge 路径同一条规则）；
     *  - `icon_edge = false` 时**连轮廓都不建**（`edgeEnabled = false`，绘制时直接跳过）。
     */
    private fun prepareShape(spec: FanSpec) {
        val prepared = IconShapeMask.prepare(
            params = spec.iconShapeParams,
            clip = iconClip,
            edge = iconEdgePath,
            // 位图边长取整数（与 [circularIcon] 里 createBitmap 的尺寸同一个表达式），
            // 否则轮廓与位图会差半个像素
            size = max(1f, spec.iconDiameter).toInt().toFloat(),
            // ⚠️ **px 而不是 dp**：这是上一版写死在 circularIcon 里的那个 `0.5f`
            // （`addCircle(center, center, center - 0.5f)`）。保留它才能让「不配形状」
            // （= 正圆）的渲染逐像素不变 —— 顺手「修正」成 0.5dp 会让所有图标的半径
            // 多缩 1.6px，那是这次改动不该顺手改的东西。
            inset = ICON_CLIP_INSET_PX,
            // 负的半个线宽 = 细边整条画在图标**外面**（2026-10-29）。原来往里缩半个线宽，
            // 53% 白的边就压在图标最外圈那 1dp 上，用户看着是「图标边缘有点透」，
            // icon_alpha 拉满也去不掉。挪到外面后图标像素一个不盖，白边仍是外轮廓。
            edgeInset = -spec.iconEdgeWidthPx / 2f,
        )
        clipIcons = prepared.clipIcons
        edgeCircleRadius = prepared.edgeCircleRadius
        val selSize = selectedIconSize(spec)
        if (selSize > restIconSize(spec)) {
            IconShapeMask.prepare(
                params = spec.iconShapeParams,
                clip = iconClipSelected,
                edge = scratchEdgePath,
                size = selSize.toFloat(),
                inset = ICON_CLIP_INSET_PX,
                edgeInset = -spec.iconEdgeWidthPx / 2f,
            )
        }
        edgeEnabled = spec.iconEdgeEnabled && spec.iconEdgeWidthPx > 0f
        // 线宽同时更新到画笔上（每一屏一次，不是每个图标一次）
        iconEdgePaint.strokeWidth = spec.iconEdgeWidthPx
    }

    /**
     * 把一个应用图标渲染成**用户选的形状**的位图（缺省是正圆）。
     *
     * ## 依据（HyperGesture `xposed/FanOverlayView.java`）
     *
     * ```
     * 底座   **没有白底**；它画的是一个近全透明的黑圆 + 投影（`showBackdrop` 另有角部径向渐变）
     *        0x01000000 + shadowLayer(9dp, dy 3dp, 0x5C000000)          :115-116,412-414
     * 裁切   图标按 **cover 放大到 1.16 × 直径**，再被「半径 = 直径/2」的圆裁掉
     *        target = 1.16 * diameter; scale = max(target/iw, target/ih)  :642-647
     *        iconClipPath.addCircle(cx, cy, diameter/2) + clipPath()      :652-657
     * 描边   半径 − 0.5dp 处一条 1dp、53% 白（0x88FFFFFF）的细边         :117-119,423-426
     * 圆角   **没有圆角半径这个参数**，就是纯圆（`addCircle` 做 clipPath）  :652-657
     * ```
     *
     * ## 形状是后加的**一层**（原来的「纯圆」是它的缺省值）
     *
     * 参照实现与我们的缺省都是正圆 —— `icon_shape` 没声明时走的就是原来那条
     * `addCircle` 分支（逐字保留），所以**不配形状 = 和加这个参数之前一模一样**。
     * 用户选了别的形状时，[IconShapeMask] 把轮廓换成对应的多边形
     * （采样在契约层，和设置界面的预览**共用同一个函数**，所以预览与真机逐点一致）。
     *
     * ⚠️ 形状**不参与「谁赢」**：这里收到的 [target] 已经是
     * 「单项覆盖 > 单项图标包 > 整体图标包 > 自带图标」那条链的最终结果，
     * 这一步只是把它裁一刀（见 [IconShapeMask] 的类注释）。
     *
     * ## 为什么扇形这边没有「图标圆角半径」设置项
     *
     * 侧滑索引那边有 `icon_radius`（0–18dp，`core/Catalog.kt` 的 `SIDE_INDEX`）—— 那条
     * pref 对的是**Flyme 侧滑索引把图标直贴、不裁圆角**的形态。扇形这边的形状是
     * **枚举**（圆 / 圆角方 / 超椭圆 / 水滴 / 方），不是一个半径量程：两条证据
     *
     *  1. 参照实现只有 `force_circular_icons` 这个**布尔**（`ConfigContract.java:222`），
     *     形状永远是纯圆，没有任何半径量程；
     *  2. 魅族真机截图里的圆是**正圆**：`meizu-04-corner-clean.png` 上图标边界在 8 个方向
     *     的半径 = 70…74px（均值 ≈72、标准差 ≈1.2），换算是 1.7% —— 圆角方形的轴/对角
     *     半径会差 20% 上下，这里是圆。
     *
     * ```
     *
     * ## ⚠️ 上一版这里是「白底圆 + 缩到 0.76 的图标」，两处都错
     *
     *  - **没有白底**。魅族真机截图（`research/compare/meizu-04-corner-clean.png`）的径向
     *    像素剖线：微信图标从圆心到半径 ≈72px 都是绿色（`100,192,138`），到 72px 直接
     *    变成背景灰 `196,196,196`，**中间没有白色环**；QQ 的白色圆盘半径同样是 ≈72px。
     *    也就是说那些看着像「白底圆」的图标（QQ / AI / VSCode）**是应用图标自己的白底
     *    被裁成了圆**，浮层没有额外画底。参照片（微信绿底铺满整个圆）与参照代码一致。
     *  - **不是 0.76 的「安全区」**，是**放大到 1.16 倍再裁**（`max()` 取 cover，故意让它
     *    出血）。0.76 那个数没有出处 —— 上一版注释写的「启动器画自适应图标的常规安全区」
     *    是我按常识猜的；真机上图标是铺满整个圆的（微信绿底一直长到圆边）。
     *
     * 细白边保留（宽度/开不开现在可调，见 [FanIconEdge]）：去掉白底之后，它是图标边界
     * 唯一的视觉来源 —— 深色图标压在深色壁纸上会糊成一片，而选中描环是画在 `r + 6dp` 上的
     * （在图标**外面**），救不了边界。
     * 它比裁切轮廓**再缩进半个线宽**画，让整条边留在位图里（上游是让边压住裁切圆，
     * 外缘会超出裁切圆半个线宽；我们的位图尺寸就 = 图标直径，超出会被位图边界切掉）。
     *
     * ⚠️ **细白边不在这里画**（2026-09-19 解耦）：它由 [`FanView.drawSlot`]
     * 用 `iconEdgePaint` 单独描一笔，**不经过位图、不经过 `iconPaint`**，所以
     * `icon_alpha` 怎么调它都恒为 53% —— 见 [iconEdgePaint] 里那条完整论证。
     * 这里只负责「drawable 转成形状」这一件事。
     *
     * **这个方法在 [show] 里调**（展开前一次性做好），`onDraw` 只做 `drawBitmap` ——
     * 绘制路径里不许分配 / 不许建位图（见 [FanView.onDraw]）。
     */
    /** 静止尺寸位图的边长：与 [prepareShape] 里轮廓的尺寸同一个表达式，否则轮廓与位图差半个像素。 */
    private fun restIconSize(spec: FanSpec): Int = max(1f, spec.iconDiameter).toInt()

    /** 选中尺寸位图的边长（选中放大 ≤ 1 时与静止尺寸相同，不另画）。 */
    private fun selectedIconSize(spec: FanSpec): Int =
        max(restIconSize(spec), (spec.iconDiameter * spec.selectionScale).roundToInt())

    private fun circularIcon(spec: FanSpec, target: FanTarget, size: Int, iconClip: Path): Bitmap? {
        return try {
            val d = target.icon ?: return null
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val center = size / 2f
            // cover：取 max 而不是 min，图标溢出形状的边界，由下面的 clipPath 裁掉。
            // intrinsic 为 0 的 drawable（部分自适应图标）会让除法炸掉，兜成 1。
            val cover = ICON_COVER_SCALE * size
            val iw = max(1, d.intrinsicWidth)
            val ih = max(1, d.intrinsicHeight)
            val scale = max(cover / iw, cover / ih)
            val dw = (iw * scale).roundToInt()
            val dh = (ih * scale).roundToInt()
            c.save()
            // 轮廓由 [prepareShape] 在展开前建好；方形那一档**不裁**（保留原图边界）
            if (clipIcons) {
                c.clipPath(iconClip)
                // ── 抗锯齿底（2026-09-19 加）───────────────────────────────────
                // 为什么在这里：`clipPath` 是**带抗锯齿**的，圆周那圈像素只有部分覆盖，
                // 于是它们的 alpha 是从 1 渐降到 0 的中间值（典型 0.3–0.6）。
                // 这圈像素紧接着被 `drawSlot` 的 `drawBitmap` **缩放采样**（`diameter` 是
                // 动态的：展开动画 0.78→1.0、选中放大、脉冲），采样又会把邻近的几个
                // 半透明像素混在一起 → 边界整圈带着「发虚」的观感。
                //
                // 更关键的是：这圈混色**不是 `icon_alpha` 造成的**，所以它也**修不掉** ——
                // `icon_alpha` 走 `Paint.setAlpha`，是**乘性**的（`结果 = 位图alpha × paint alpha`），
                // 100% 时乘 1，边缘该多少还是多少。用户报的「拉到 100% 还是有点透明」
                // 就是这个：圆内部是实心的，只有边界那圈是半透明。
                //
                // 在 clip 之后铺一层**近全透明的白**，把这些像素的 alpha 抬到 1（本来就
                // 该是 1 —— 它们在圆内），同时色相影响小到看不见。`ALPHA_8` 那种
                // 「直接把 alpha 拍平」的做法在这里不可行：底图是 `ARGB_8888`，
                // 而且我们要保留 drawable 自己的半透明区域（部分图标带透明边）。
                c.drawColor(ICON_AA_BACKING_COLOR)
            }
            d.setBounds(
                (center - dw / 2f).roundToInt(),
                (center - dh / 2f).roundToInt(),
                (center + dw / 2f).roundToInt(),
                (center + dh / 2f).roundToInt(),
            )
            d.draw(c)
            c.restore()
            // ⚠️ **细白边不在这里画**（2026-09-19 解耦）：
            //
            // 上一版把它和 drawable 画进**同一张位图**，于是位图整体由 `iconPaint`
            // （alpha = `icon_alpha`）画出去时边也跟着淡 —— 结果是「`icon_alpha` 拉满、
            // 图标依然有点透明」，因为 `ICON_EDGE_COLOR` 自带 `0x88`（53%）的 alpha。
            // 边现在改由 [`FanView.drawSlot`] 用 `iconEdgePaint` **单独描一笔**，
            // 不经过位图、不经过 `iconPaint`，所以**恒为 53%**。
            // 理由与「为什么不再烙进位图」的完整论证见 [iconEdgePaint]。
            b
        } catch (t: Throwable) {
            Log.w(TAG, "图标转形状失败，改用占位圆", t)
            null
        }
    }

    /**
     * 振一下。
     *
     * 效果固定 `EFFECT_TICK`（=2），对应 HyperGesture 的 `VibrationEffect.createPredefined(2)`
     * （`FanRuntime.java:3383-3396`）。
     *
     * 时机：**只在扇形弹出和选中项变化时**振。HyperGesture 自己不做时间节流，
     * 靠 `next != lastHapticSelection` 的边沿触发（`FanRuntime.java:1830-1834`）保证
     * 「同一项不重复振」。我们这里额外加一条 35ms 下限：命中是**图标圆**，
     * 手指正好在两个圆的重叠带上蹭时会来回切项（`lastSlot` 每次都在变、边沿触发挡不住），
     * 听起来像坏了。
     */
    private fun tick() {
        if (!vibratorUsable) return
        val now = SystemClock.uptimeMillis()
        if (lastTickAt >= 0 && now - lastTickAt < TICK_MIN_INTERVAL_MS) return
        lastTickAt = now
        runCatching {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    private fun onMain(what: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        Log.e(TAG, "扇形浮层的 $what 被非主线程调用了，已忽略（ViewRootImpl 只能在主线程碰）")
        return false
    }

    /**
     * 停留超时的兜底收窗。
     *
     * Flyme 用 `MEIZU_FLAG_INTERCEPT_HOME_KEY` 在菜单展开时拦 home 键，
     * 我们没有那个私有 flag。能做两件事：**下一次按下时收掉旧浮层**（见探测器）和这条超时
     * 兜底 —— 输入流被系统中途掐断（另一根手指按 home、来电、手势被抢走）时没有 UP/CANCEL
     * 到达，浮层就会一直留着。
     */
    private val staleTick = Runnable {
        if (visible) {
            Log.w(TAG, "扇形停留超过 ${STALE_TIMEOUT_MS}ms，兜底收起")
            dismissInternal()
        }
    }

    /**
     * 悬停超时：到点收窗。**主线程**（`enterHover` 排的）。
     *
     * Flyme 的悬停没有超时（菜单一直挂着等外部事件）—— 那在本机上就是「屏幕角落
     * 永远浮着一块半透明的扇子」。我们拍板了超时（[HOVER_TIMEOUT_MS]），
     * 与「下一次手势收尾」一起构成悬停的两条退出路径。
     */
    private val hoverTick = Runnable {
        if (hovering) {
            Log.i(TAG, "扇形悬停超时，收起")
            dismissInternal()
        }
    }

    private companion object {
        const val TAG = "FP-Fan"
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT

        /**
         * 悬停时长（毫秒）。抬手未选中后菜单保留这么久，期间下一次手势可直接续选。
         * 8s = 「来得及换个图标再点一次」，又不至于长到忘记它在。
         * Flyme 没有超时（只能等外部事件）—— 那条缺陷我们有意不抄，见 [hovering]。
         */
        const val HOVER_TIMEOUT_MS = 8_000L

        /** 位移/缩放的展开基时长（`FanOverlayView.java:373` 的 170ms）。 */
        const val BASE_REVEAL_MS = 170f

        /**
         * 展开时长上限（毫秒）。
         *
         * 见 `startReveal` 里那段说明：`animation_speed` 是除数，取值离谱时
         * 展开会被拉长到看不见。这里兜住上限，保证「参数再错也不会让功能消失」。
         * 400ms 是 170ms 基时长的两倍多，正常的慢速设置也够用。
         */
        const val MAX_REVEAL_MS = 400f

        /** 每项自转的基时长（`FanOverlayView.java:715` 的 150ms）。 */
        const val BASE_ROTATION_MS = 150f

        /** 窗口淡入基时长（`FanOverlayController` 的 110ms）。 */
        const val BASE_FADE_MS = 110f

        /** 错峰基时长（`stagger = max(8ms, 1200/speed)`，`FanOverlayView.java:723-725`）。 */
        const val STAGGER_BASE_MS = 1200f

        const val TICK_MIN_INTERVAL_MS = 35L

        /** 停留兜底时长。见 [staleTick]。 */
        const val STALE_TIMEOUT_MS = 10_000L

        /** 变暗强度。Flyme 的 dim 数值未实测，取一个「明显压暗但不至于看不清」的值。 */
        const val DIM_AMOUNT = 0.45f

        /** 跨窗口模糊半径。Flyme 的模糊半径未实测，取系统常用量级。 */
        const val BLUR_DP = 22f

        /**
         * 图标按 **cover** 放大到「图标直径 × 它」再裁圆（`FanOverlayView.java:642`）。
         *
         * 1.16 不是拍脑袋的：等价于「只保留图标原始尺寸的中央 1/1.16 ≈ 86%」，
         * 也就是**故意让图标出血**、由圆去裁。上一版这里是 0.76（缩到安全区内），
         * 方向正好相反 —— 见 [circularIcon] 的证据段。
         */
        // 值定义在 FanIconPreview：设置界面的预览要用**同一个**系数，
        // 否则同一张图标在预览与真机上大小不一样。这里只做转发，**不许再写一个 1.16**
        const val ICON_COVER_SCALE = FanIconPreview.COVER_SCALE

        /**
         * ⚠️ **这两个常量已经不在了**（`ICON_EDGE_STROKE_DP = 1f` / `ICON_EDGE_DP = 0.5f`）：
         * 线宽现在由 `icon_edge_width`（**dp**，缺省 0.5）给，换算在几何那边一次做完
         * （[FanSpec.iconEdgeWidthPx]），轮廓缩进取它的一半。
         *
         * 上一版的两处毛病（用户实测反馈）：
         *  - 线宽 `1dp` 不可调；
         *  - 缩进 `0.5f` 被**当 px 用**（不是 dp），于是同一份配置在 3.25 密度的机器上
         *    是 0.5px、在低密度机器上却是 2dp —— 同一个参数两种粗细。
         * 这里不再留常量，就是为了让「谁算这个数」只有一个答案。
         */

        /**
         * 裁切轮廓相对位图边缘的缩进（**px**）。
         *
         * ⚠️ 它是 px 不是 dp，这是**有意保留**的历史值：上一版这里写的是
         * `iconClip.addCircle(center, center, center - 0.5f)` —— 那个 0.5 是像素。
         * 改成 `0.5dp`（本机 1.625px）看着更「对」，但会把所有图标的裁切半径改小 1.6px，
         * 也就是**顺手改了渲染**。这次改动只加形状，不动缺省形状的样子。
         */
        const val ICON_CLIP_INSET_PX = 0.5f

        /**
         * 细白边颜色 `0x88FFFFFF` —— 53% 白（`FanOverlayView.java:119` 的 `-1996488705`）。
         *
         * 为什么是半透明白而不是纯白：它压在**应用图标自己**的边缘上（不像 HyperGesture
         * 那样底下有个独立底座），纯白会在亮色图标上留下一条突兀的亮环。
         *
         * ⚠️ 这个 alpha（0x88）与 `icon_alpha` 是**两件事，而且不再相乘**（2026-09-19 拍板）：
         * 这里管「边本身多白」（**恒定值**），`icon_alpha` 管「**图标本体**多透明」。
         * 上一版两者相乘（边烙在图标位图里），后果是 `icon_alpha` 拉满时图标依然看着发灰 ——
         * 因为边最高只有 53%，而它又是图标边界唯一的视觉来源。完整论证见 [iconEdgePaint]。
         *
         * 53% 不是「被调淡的残值」而是**设计值**：参照实现初始化时设的就是它，全程不再改
         * （`FanOverlayView.java:117-119`，那边没有图标不透明度这个概念）。
         */
        const val ICON_EDGE_COLOR = 0x88FFFFFF.toInt()

        /**
         * 裁切边缘的**抗锯齿底**：`0xF0FFFFFF`（94% 白）。
         *
         * ## 它解决什么（用户报的「拉到 100% 还是有点透明」）
         *
         * `clipPath(iconClip)` 带抗锯齿 → 圆周那圈像素只有部分覆盖 → alpha 是中间值
         * （0.3–0.6）。而 `drawSlot` 的 `drawBitmap` 几乎**总在缩放**这张位图
         * （`diameter = iconDiameter × (0.78 + 0.22 × reveal) × 选中放大 × 脉冲`），
         * 缩放采样又把这几个半透明像素混在一起 → 边界整圈发虚。
         *
         * 这圈混色**不是 `icon_alpha` 造成的，所以也修不掉**：那个参数走
         * `Paint.setAlpha`，是**乘性**的（`结果 = 位图alpha × paint alpha`），
         * 100% 时乘 1，边缘该多少还是多少。圆内部本来就是实心的 ——
         * 用户看到的「透明」全在边界那一圈上。完整链路见 [circularIcon]。
         *
         * ## 为什么是这个值（两个约束把它夹出来的）
         *
         *  - **alpha 要够高**：底是画在 drawable **下面**的，最终像素 = `底 × (1−a) + 图 × a`，
         *    其中 `a` 是被覆盖的比例。想把 0.4 的覆盖抬到接近 1，底色 alpha 得 0.9 以上 ——
         *    取 94% 时最差的 0.3 覆盖也能抬到 `0.94 + 0.06×0.3 ≈ 0.96`。
         *  - **色相要够淡**：它只在**圆内**可见（clip 之外被裁掉），而圆内像素马上会被
         *    drawable 自己盖住 —— drawable 是 **cover 放大 1.16 倍**画的，必然铺满整个圆。
         *    所以底色实际上**只在抗锯齿那圈露出来**，也就是它唯一的作用范围。
         *
         * ## 为什么不写纯白（`0xFFFFFFFF`）
         *
         * 那样就没给自己留余地了：`drawable` 如果**自带透明边**（部分图标是这样，
         * 也是我们刻意保留的），纯白底会在那些位置透出一圈白。留 6% 的余量让它淡一点，
         * 既够抬 alpha、又不至于把「图标自己的透明」变成白。
         *
         * ## 与 `icon_edge` 的关系
         *
         * **两者独立，互不替代**：这条底解决「边缘发虚」（抗锯齿混色），
         * 细白边解决「深色图标压在深色壁纸上糊成一片」（对比度）。
         * 底是**无条件的**（只要裁切就有混色，与用户开不开描边无关）；
         * 描边由用户决定。这就是「两个都做、但底更淡」的组合。
         */
        const val ICON_AA_BACKING_COLOR = 0xF0FFFFFF.toInt()
    }
}

/**
 * 扇形本体。自绘，不持有任何子 View。
 *
 * ## 位置来自哪里
 *
 * 每一项的中心坐标由 [FanSpec.itemX] / [FanSpec.itemY] 给出 —— 它们 = **弧心** + 顶点
 * （顶点是 [FanMetrics.place] 算好的「相对弧心的偏移」），贴右时镜像 `x`。
 * 与命中测试**共用同一份顶点数组**（在 `FanConfig.spec` 里一次算好）。
 * 绘制和命中一旦各算各的，就会出现「看到的图标」和「碰到的图标」对不上。
 *
 * ⚠️ 弧心**不在屏幕角上**：[FanEdgeMargin] 把它往屏内推了一段（用户反馈「贴边的项被裁」）。
 * 展开动画也是从弧心起飞的（`cornerX` / `cornerY`），所以留距一动，起飞点与每个顶点
 * 一起动、相对关系不变 —— 这里不需要为它加任何分支。
 *
 * ## 展开动画（照 HyperGesture）
 *
 * ```
 * 位置  center = 圆心 + (中心 - 圆心) × revealFactor
 *       revealFactor 从 (1 - reveal_amount%) 插值到 1      FanOverlayView.java:380,391-393
 * 大小  diameter × (0.78 + 0.22 × reveal)                 同文件 :400
 * 倾角  rotationDegrees × 方向 × (1 - reveal)，按项错峰   同文件 :710-721
 * ```
 *
 * 也就是「图标从圆心附近长出来、带过冲、各自歪着转正」。注意**只有倾角是错峰的**：
 * 所有项同时飞出，各自的初始倾角按 `stagger × index` 延迟归零。
 *
 * ## 扇上不显示名字
 *
 * 实测「扇上**不显示应用名**（只有图标）」，用户也明确要求过不要标题。
 * HyperGesture 有 `show_selected_app_name`，我们不做。
 * 这里留一条注释而不是留一个被注释掉的函数 —— 免得以后有人「觉得缺个提示」又加回来。
 *
 * ## 页与槽位
 *
 * 所有页共用同一组槽（[FanSpec.count]），空槽不画东西。这样换页时几何不跳动，手指停在
 * 同一个位置始终是同一个槽 —— 换页只换图标，不换几何。
 */
private class FanView(
    context: Context,
    private val spec: FanSpec,
    private val openToRight: Boolean,
    private val moreSlot: Int,
    /**
     * 细白边的绘制材料（**由 [FanOverlay] 在 `prepareShape` 里备好**）。
     *
     * 为什么要传进来而不是让 [FanView] 自己建：轮廓与线宽**每屏只该算一次**
     * （形状只与形状/尺寸/线宽有关，每一项都一样），而 `prepareShape` 已经在主线程上
     * 做完了这件事 —— 那边同时给 [circularIcon] 用同一批轮廓去裁图标。
     * 两边各建一份会让「边落在哪」与「图标裁到哪」有漂移的空间。
     *
     * `null` = 这一屏不画边（`icon_edge` 关着或线宽 0）—— 见 [FanEdgeDrawing]。
     */
    private val edge: FanEdgeDrawing?,
) : View(context) {

    /** 当前选中槽位，-1 = 未选中。写入时启动 110ms 的选中脉冲。 */
    var selection = -1
        set(value) {
            if (value >= 0 && value != field) {
                pulseSelection = value
                pulseStartedAt = SystemClock.uptimeMillis()
            }
            field = value
            invalidate()
        }

    /** 展开动画已经播了多少毫秒；由 [FanOverlay] 的动画器写。 */
    var animMs = 0f
    var revealMs = 0f
    var rotationMs = 0f
    var staggerMs = 0f
    var fadeMs = 0f

    private var items: List<FanTarget> = emptyList()
    private var icons: List<Bitmap?> = emptyList()
    private var selectedIcons: List<Bitmap?> = emptyList()

    /** 正在播脉冲的那一项 / 起始时刻。见 [selectionPop]。 */
    private var pulseSelection = -1
    private var pulseStartedAt = 0L

    /**
     * 图标位图的画笔。`alpha` 来自 `icon_alpha`（见 [FanIconAlpha]）：
     * 这里是**唯一**应用它的地方 —— 选中描环与「⋯」按钮都不是「图标」，
     * 它们必须保持不透明（选中的那一项要最清楚）。
     *
     * 在这里设（而不是在 onDraw 里）：画笔随 [FanView] 每次展开新建，所以设置一次就够，
     * `onDraw` 里一个多余的计算都不做。
     */
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (spec.iconAlpha * 255f).roundToInt().coerceIn(0, 255)
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // ⚠️ **颜色必须是纯白不透明**（`0xFFFFFFFF`），不透明度只由 `alpha` 给。
        //
        // 这里原来写的是 `0x66FFFFFF`（40% 白）+ `alpha = icon_alpha × 255`，
        // 而 `Paint.setAlpha` 与 `color` 里的 alpha 是**相乘**的：
        // `0.40 × 1.00 = 0.40` —— 用户把 `icon_alpha` 拉到 100%，
        // 占位圆也只有 **40%**，天花板被写死在颜色里，滑杆拉满也救不回来。
        // 当时那条「顺序不能反：color 会把 alpha 一起写掉」的注释已经意识到两者会打架，
        // 但没意识到是**相乘**而不是覆盖。
        color = 0xFFFFFFFF.toInt()
        // 占位圆（图标位图取不到时画的那个圈）算**图标的一部分** → 跟随 `icon_alpha`。
        // 现在它是唯一一处「乘以 icon_alpha」的地方，语义干净：颜色给色相，alpha 给透明度。
        alpha = (spec.iconAlpha * 255f).roundToInt().coerceIn(0, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 线宽取自几何常量（[FanArcs.RING_STROKE_DP]）：几何那边算「整圆在屏内」时要拿
        // 它的一半当圆的真正外沿，所以这个数只能有一处定义。
        strokeWidth = FanArcs.RING_STROKE_DP * spec.density
        color = RING_COLOR
    }
    private val morePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MORE_COLOR }
    private val moreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * 画位图用的目标矩形，**复用同一个实例**。
     *
     * 动画期间 `onDraw` 一秒跑 60 次，每次都 `RectF(...)` 就是每秒 60 次分配 ——
     * 那正是动画掉帧的典型来源（规范 §7.4 对动画帧的要求）。
     */
    private val dst = RectF()

    /** 换页：只换内容，不碰几何。 */
    fun setPage(page: FanPage) {
        items = page.items
        icons = page.icons
        selectedIcons = page.selectedIcons
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val reveal = reveal()
        var pulseRunning = false
        for (i in 0 until spec.count) {
            if (drawSlot(canvas, i, reveal, now)) pulseRunning = true
        }
        // 选中脉冲自带时间轴（不由 ValueAnimator 驱动），要自己续帧
        if (pulseRunning) postInvalidateOnAnimation()
    }

    /**
     * 画一个槽。
     *
     * @return 这一项的选中脉冲是否还在播（还需要下一帧）
     */
    private fun drawSlot(canvas: Canvas, index: Int, reveal: Float, now: Long): Boolean {
        // 空槽（最后一页装不满时）什么都不画；「⋯」槽永远要画。
        // 命中判定仍然会返回空槽的下标，那种情况在 commit 里取不到应用 → 什么都不启动。
        val isMore = index == moreSlot
        if (!isMore && items.getOrNull(index) == null) return false

        val selected = index == selection
        val pop = selectionPop(index, now)
        val diameter = spec.iconDiameter * (0.78f + 0.22f * reveal) *
            (if (selected) spec.selectionScale else 1f) * pop
        var r = diameter / 2f

        // 圆心 → 终点：从 revealFactor 处插值到终点（FanOverlayView.java:391-393）
        val ox = spec.cornerX(openToRight)
        val oy = spec.cornerY
        val f = spec.revealFactor(reveal)
        var cx = ox + (spec.itemX(index, openToRight) - ox) * f
        var cy = oy + (spec.itemY(index) - oy) * f

        val rotation = revealRotation(index)
        // 静止（展开动画走完、没在脉冲、没旋转）时位图**不缩放、落在整像素上**：
        // 原来每帧都按浮点直径缩放、落在半像素位置，开着 FILTER_BITMAP 等于整张图被插值糊半个像素；
        // 选中时还要把静止尺寸的位图放大到 selectionScale。这是「拉到 100% 还是发虚」剩下的那部分
        // （上一次只修了边缘那圈半透明，2bb8025）。
        val steady = kotlin.math.abs(reveal - 1f) < STEADY_EPSILON && pop == 1f && rotation == 0f
        val bmpForSlot = (if (selected) selectedIcons.getOrNull(index) else null) ?: icons.getOrNull(index)
        if (steady && bmpForSlot != null && !isMore) {
            r = bmpForSlot.width / 2f
            cx = kotlin.math.round(cx - r) + r
            cy = kotlin.math.round(cy - r) + r
        }

        val save = canvas.save()
        // 展开期的自转绕**图标自身中心**（FanOverlayView.java:403 的 rotate(angle, x2, y2)）
        canvas.rotate(rotation, cx, cy)

        if (isMore) {
            // 末尾的「⋯」：蓝色圆 + 三个白点。
            // 它是**我们的**翻页槽：HyperGesture 没有分页（它最多 24 个用户自选项），
            // 而我们的清单是整机应用池，一屏装不下就必须有「更多」。
            canvas.drawCircle(cx, cy, r, morePaint)
            val dot = r * 0.13f
            for (k in -1..1) canvas.drawCircle(cx + k * r * 0.42f, cy, dot, moreDotPaint)
        } else {
            val bmp = bmpForSlot
            if (bmp != null) {
                if (steady && bmp.width.toFloat() == 2f * r) {
                    canvas.drawBitmap(bmp, cx - r, cy - r, iconPaint)
                } else {
                    dst.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawBitmap(bmp, null, dst, iconPaint)
                }
            } else {
                canvas.drawCircle(cx, cy, r, placeholderPaint)
            }
            // 细白边：**独立一笔**，不跟 `icon_alpha`（见 [FanOverlay] 的 `iconEdgePaint`）。
            //
            // 位置与上一版（画在位图里）完全相同 —— **没有重算任何几何**：
            //  - 正圆：半径 = [FanEdgeDrawing.circleRadius]（= 裁切半径 − 半个线宽，
            //    `prepareShape` 存下来的那个值）再按当前帧缩放；
            //  - 其它形状：[FanEdgeDrawing.path] 的坐标原点在它自己的包围盒左上角，
            //    所以平移到 (cx − r, cy − r) 画。
            //
            // ⚠️ 缩放用 `r / (spec.iconDiameter / 2)` 而不是直接用 `circleRadius`：
            // 上一版烙在位图里时，位图本身会被拉伸到当前这一帧的尺寸（展开动画与选中放大
            // 都作用在 `r` 上），边因此跟着图标一起长/缩。独立一笔要复现这个关系就必须
            // 乘同一个比例，否则会出现「图标放大了、白圈还是原来那么大」。
            edge?.let { e ->
                if (e.circleRadius > 0f) {
                    val base = spec.iconDiameter / 2f
                    val scale = if (base > 0f) r / base else 1f
                    canvas.drawCircle(cx, cy, e.circleRadius * scale, e.paint)
                } else {
                    canvas.save()
                    canvas.translate(cx - r, cy - r)
                    canvas.drawPath(e.path, e.paint)
                    canvas.restore()
                }
            }
        }

        if (selected && spec.selectionRing) {
            // 描环半径 = 图标半径 + 6dp、线宽 3dp：逐条对应 HyperGesture 的
            // `iconRadius + dp(6)` / `dp(3)`（`FanOverlayView.java:122,431-433`）。
            // 两个常量都从几何那边取（[FanArcs.HIT_TOLERANCE_DP] / [FanArcs.RING_STROKE_DP]）：
            // 几何算「整圆在屏内」的 keepOut 用的就是这两个数，写死在这里会让两边漂移。
            // 颜色保留 Flyme 实测的近黑：HyperGesture 是蓝环，但**颜色不是
            // 几何/手感**，而近黑是设备上量过的值，换掉要重做一遍对比度论证。
            // ⚠️ 这个画笔**不受** `icon_alpha` 影响（见 [FanOverlay.show] 的说明）。
            canvas.drawCircle(cx, cy, r + FanArcs.HIT_TOLERANCE_DP * spec.density, ringPaint)
        }
        canvas.restoreToCount(save)
        return pulseStillRunning(index, now)
    }

    /** 位移/缩放的展开进度（不分项）：`easeOutBack` 到 1 为止。 */
    private fun reveal(): Float {
        if (revealMs <= 0f) return 1f
        return easeOutBack((animMs / revealMs).coerceIn(0f, 1f))
    }

    /**
     * 第 [index] 项当前的展开倾角。按项错峰（`revealStartedAt + stagger × index`），
     * 方向由贴左/贴右取反（`FanOverlayView.java:719-720`）。
     */
    private fun revealRotation(index: Int): Float {
        if (rotationMs <= 0f || spec.rotationDegrees == 0f) return 0f
        val ring = spec.rings.getOrElse(index) { 0 }
        val t = ((animMs - staggerMs * ring) / rotationMs).coerceIn(0f, 1f)
        val p = easeOutBack(t)
        if (p >= 1f) return 0f
        val direction = if (openToRight) -1f else 1f
        return spec.rotationDegrees * direction * (1f - p)
    }

    /**
     * 选中脉冲：切换选中项时那一项脉一次 `+10%`，`sin(πp)` 的 110ms 正弦
     * （`FanOverlayView.java:681-685` 的 `selectionPop`，基时长 110ms）。
     *
     * 抬手确认的那次脉冲（+18% / 150ms，同文件 :677-680）**不做**：我们抬手就立刻收窗
     * 去启动小窗，压 150ms 只会让「抬手 → 应用起来」的主观延迟变长（见 [FanOverlay.commit]）。
     */
    private fun selectionPop(index: Int, now: Long): Float {
        if (index != pulseSelection) return 1f
        val p = (now - pulseStartedAt).toFloat() / pulseMs
        if (p >= 1f) return 1f
        return 1f + 0.1f * sin(PI.toFloat() * p)
    }

    private fun pulseStillRunning(index: Int, now: Long): Boolean =
        index == pulseSelection && (now - pulseStartedAt) < pulseMs

    private val pulseMs: Float get() = max(45f, 110f * 100f / spec.animationSpeed)

    /**
     * 缓出过冲曲线，逐字来自 `FanOverlayView.easeOutBack`（`:743-746`）：
     * `2.70158·(v-1)³ + 1 + 1.70158·(v-1)²`。
     */
    private fun easeOutBack(v: Float): Float {
        val s = v - 1f
        return 2.70158f * s * s * s + 1f + 1.70158f * s * s
    }
}
