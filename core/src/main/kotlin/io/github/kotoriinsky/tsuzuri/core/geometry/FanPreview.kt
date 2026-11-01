package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.max

/**
 * 契约层自有的矩形值类型（px）。
 *
 * **为什么不用 `android.graphics.Rect`**：`core` 不允许出现 `android.*`
 * （`core/build.gradle.kts` 里那条规矩），否则设置界面的预览、hook、单测就没法共用同一份几何。
 * `Rect` / `RectF` 都是 `left/top/right/bottom` 语义，这里照搬这个约定，
 * 但用 `Float` 一门到底：几何里所有中间量都是浮点，`Rect` 的 int 会在预览里
 * 造成「每一项差 1px」的锯齿，而那种差在真机上看不出来、在预览里很显眼。
 */
class ScreenRect(
    /** 左边缘（px），含。 */
    val left: Float,
    /** 上边缘（px），含。 */
    val top: Float,
    /** 右边缘（px），不含（与 `Rect` 同语义）。 */
    val right: Float,
    /** 下边缘（px），不含。 */
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 点在不在矩形里。预览的「这一角是热区」提示用得上。 */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** 夹取过的矩形：**保证整块在 `[0,0,screenW,screenH]` 内**（小窗摆放要用）。 */
    fun clampedToScreen(screenW: Float, screenH: Float): ScreenRect {
        // 先夹尺寸再夹位置：反过来的话「窗口比屏幕还大」会算出负的 left
        val w = width.coerceIn(0f, max(0f, screenW))
        val h = height.coerceIn(0f, max(0f, screenH))
        val l = left.coerceIn(0f, max(0f, screenW - w))
        val t = top.coerceIn(0f, max(0f, screenH - h))
        return ScreenRect(l, t, l + w, t + h)
    }

    override fun toString(): String = "ScreenRect($left,$top,$right,$bottom)"

    override fun equals(other: Any?): Boolean =
        other is ScreenRect && other.left == left && other.top == top &&
            other.right == right && other.bottom == bottom

    override fun hashCode(): Int {
        var r = left.hashCode()
        r = 31 * r + top.hashCode()
        r = 31 * r + right.hashCode()
        r = 31 * r + bottom.hashCode()
        return r
    }
}

/**
 * 预览的**全部输入**：屏幕尺寸与**读好的参数值**。
 *
 * ## 为什么不传 `Prefs`
 *
 * 这条是硬要求：`ui` 与 `hook` 都只依赖 `core` 的**值类型**，读配置的方式两边不同
 * （ui 读 RemotePreferences、hook 读框架给的实例），而且预览必须能在
 * Compose Preview（没有任何配置存储）里跑起来。所以由**调用方**把值读好传进来。
 *
 * 每个字段的取值就是 `Catalog` 里同名 pref 的当前值 —— 参数名与键名一一对应，
 * 唯一一处例外是 [staggerSlots]（键 `fan_row_stagger` 是字符串 `aligned`/`half`，
 * 折成格数是**派生**，见 `FanRowStagger`；预览侧用 `IconShape` 那套 `of()` 的写法折算即可）。
 *
 * 默认值**只在这一个类里给 `null`**（一个都没有），因为「忘了传某个参数」必须编译不过 ——
 * 给默认值就会静默用默认值，而预览用默认值、真机用用户值，是「预览和真机不一样」的最坏来源。
 */
class FanPreviewParams(
    /** 屏幕宽（px）。用整块显示器的 bounds（与 hook 的 `displayMetricsOf` 同一口径）。 */
    val screenW: Float,
    /** 屏幕高（px）。 */
    val screenH: Float,
    /** 密度（px/dp）。 */
    val density: Float,
    /**
     * `hot_width_percent`（%）：热区宽 = **竖屏屏宽** × 它（短边基准，横竖屏同一块热区，
     * 求值在 [FanHotZone] —— 2026-09-20 起不再是「当下屏宽」，那会让横屏热区变形）。
     */
    val hotWidthPercent: Float,
    /** `hot_height_percent`（%）：热区高 = **竖屏屏高** × 它（长边基准，与宽不同，见 [FanHotZone]）。 */
    val hotHeightPercent: Float,
    /** `corners`：`both_bottom` / `bottom_left` / `bottom_right`。决定哪一角画成可用态。 */
    val corners: String,
    /** `fan_layout_mode`：`smart` / `custom`。 */
    val layoutMode: String,
    /** `fan_custom_inner_count`：自定义模式内排容量（外排是派生量）。 */
    val customInner: Int,
    /** `fan_custom_middle_count`：自定义模式中排容量。 */
    val customMiddle: Int,
    /** `selection_radius_percent`（%）：半径初值 = 短边 × 它。**最终半径**见 [FanPreview.radius]。 */
    val radiusPercent: Float,
    /** `icon_size_dp`（dp）：图标基准边长，**初值**（可能被压小）。 */
    val iconDp: Float,
    /** `fan_edge_margin`（dp）：弧心离屏幕边缘的留距。 */
    val edgeMarginDp: Float,
    /** `fan_row_stagger` 折成的格数：0（对齐）/ 0.5（错开半格）。 */
    val staggerSlots: Float,
    /** `selection_scale`（%）：选中项放大倍率（>= 100）。影响「整圆在屏内」的留距。 */
    val selectionScalePercent: Float,
    /** `icon_shape`：形状。缺省口径见 [IconShape.DEFAULT]（= 圆形 = 加这个参数前的行为）。 */
    val iconShape: IconShape,
    /**
     * 图标不透明度（%）。**预览必须能画出来**：真机上它只作用于位图、不作用于描边，
     * 所以「图标淡了、白圈还实心」这种耦合必须能在预览里一眼看到。
     */
    val iconAlphaPercent: Float = 100f,
    /** 展开行程（%）：图标的**起点**离圆心多远（`startFactor = 1 - amount/100`）。动效页的预览要用。 */
    val revealAmountPercent: Float = 65f,
    /** 图标白描边开不开。关掉就是完全无描边。 */
    val edgeEnabled: Boolean = true,
    /** 描边宽度（dp）。真机上的默认值是 0.5 **px**（不是 dp），这里统一按 dp 表达。 */
    val edgeWidthDp: Float = 0.5f,
    /**
     * `selection_ring`：选中项的描环开不开。
     *
     * 预览必须知道它（2026-09-19 补）：真机上关掉它就是不画那一圈，而预览此前**无条件**
     * 画环 —— 于是用户在「选中反馈」那一页把描环关掉，预览里那圈还在，
     * 他只能得出「这个开关没用」的结论（而真机其实是好的）。
     */
    val selectionRing: Boolean = true,
    /**
     * 形状的圆角比例（%，相对边长）。`0` = 用这个形状自己的默认值 ——
     * 这样老配置（没配过这两个滑块）仍然画出与参数上线之前**逐像素一致**的结果。
     */
    val shapeCornerPercent: Float = 0f,
    /** 形状的柔度（超椭圆指数）。`0` = 用形状自己的默认值。越大越接近方形。 */
    val shapeExponent: Float = 0f,
    /**
     * `window_preset` 的当前取值（`system` / `flyme` / `custom`）。
     *
     * 与 `window_*_percent` 四个不同：那四个是**滑杆读数**，这个键是**档位**。
     * 三档的判别统一交给 [WindowBounds.resolvePreset]（`hook` 侧读的是同一个函数）——
     * 上一版预览自己判一个 `windowCustom: Boolean`，而 hook 也判一次，
     * 语义一变就要同时改两处（漏一处 = 预览画 Flyme 那块、真机摆系统那块）。
     */
    val windowPreset: String,
    /** `window_width_percent`（%）：小窗宽 = 屏幕宽 × 它。**只有 `custom` 档会用**。 */
    val windowWidthPercent: Float,
    /** `window_height_percent`（%）：小窗高 = 屏幕高 × 它（基准是高）。**只有 `custom` 档会用**。 */
    val windowHeightPercent: Float,
    /** `window_x_percent`（%）：窗口**中心**在可移动区间里的比例。**只有 `custom` 档会用**。 */
    val windowXPercent: Float,
    /** `window_y_percent`（%）：窗口**中心**在可移动区间里的比例。**只有 `custom` 档会用**。 */
    val windowYPercent: Float,
    /** 槽位数（每页画得下几项，含末尾「⋯」槽）。预览要显示几个就传几个。 */
    val count: Int,
) {
    companion object {
        /** [revealAmountPercent] / [edgeWidthDp] 的出厂默认值（键没声明 / 值非法时用）。 */
        const val DEFAULT_REVEAL_PERCENT = 65f
        const val DEFAULT_EDGE_WIDTH_DP = 0.5f
    }
}

/** 预览里的一项：画在哪、多大、什么角度。 */
class FanPreviewItem(
    /** 项下标（0 起，与「用户清单的顺序」一一对应：第 1 项在第 1 个槽位）。 */
    val index: Int,
    /** 在哪一层（[FanPlan.rows] 的下标）。 */
    val rowIndex: Int,
    /** 这一层里的第几个（0 起）。 */
    val slotInRow: Int,
    /** 该项的极角（度）：0° = 沿底边朝屏内，90° = 沿侧边朝上。 */
    val angleDeg: Double,
    /** 贴**左**角时的圆心 x（px）。 */
    val centerLeftX: Float,
    /** 贴**右**角时的圆心 x（px）。 */
    val centerRightX: Float,
    /** 圆心 y（px）—— 左右两角共用（y 不镜像）。 */
    val centerY: Float,
    /** 图标直径（px）。全局一个值（不是每层一个），与真机一致。 */
    val diameter: Float,
    /** 命中圆半径（px）= 图标半径 + 6dp。画选中态那个圈的半径是它 + 描环线宽/2。 */
    val hitRadius: Float,
    /**
     * 展开动画**起点**的圆心（px，贴左时）：`reveal = 0` 时这一项画在这里。
     *
     * 真机上的插值是 `圆心 + (终点 − 圆心) × revealFactor`（见 `FanSpec.revealFactor`），
     * `revealFactor` 从 [FanPreview.revealStartFactor] 走到 1 —— 所以起点 = 终点相对**弧心**
     * 缩放 [FanPreview.revealStartFactor] 倍。预览画箭头/起点虚影用这三个数，
     * **不要自己再推一遍**（那就会出现「预览的起点在真机里面一格」这种偏差）。
     */
    val startCenterLeftX: Float,
    /** 同上，贴右时（x 轴镜像）。 */
    val startCenterRightX: Float,
    /** 起点的圆心 y（左右共用，y 不镜像）。 */
    val startCenterY: Float,
    /**
     * 起点离**弧心**的距离（px）= 终点到弧心的距离 × `|revealStartFactor|`。
     *
     * 单独的标量是为了「画一条从弧心指向起点的箭头」时不用再算 `hypot`。
     */
    val startRadiusPx: Float,
) {
    /** 贴左还是贴右时的圆心 x。 */
    fun centerX(openToRight: Boolean): Float = if (openToRight) centerRightX else centerLeftX

    /** 贴左还是贴右时的**起点**圆心 x（动效预览用）。 */
    fun startCenterX(openToRight: Boolean): Float =
        if (openToRight) startCenterRightX else startCenterLeftX

    /**
     * 调试用（日志/预览的标签）。角度用 [FanArcs.fmt]（固定 `Locale.US`）—— 与几何日志
     * 同一个规矩：日志里的小数点必须是 `.`，否则拿计算器复算的人要猜本地化格式。
     */
    override fun toString(): String =
        "item$index(row=$rowIndex,slot=$slotInRow,angle=${FanArcs.fmt(angleDeg)},d=$diameter.toInt())"
}

/**
 * 预览的产物：**每一项画在屏幕的哪里**。
 *
 * 所有值都来自同一个 [FanSolution]（= 真机同一份求解），所以预览不会和真机给出两个答案。
 * 单位除注明外都是 px。
 */
class FanPreview(
    val screenW: Float,
    val screenH: Float,
    /** 屏幕密度（px/dp），预览按它画选中描环之类的线宽。 */
    val density: Float,
    /** 左下热区矩形（`hot_width/height_percent` 算出来的）。 */
    val hotLeft: ScreenRect,
    /** 右下热区矩形。 */
    val hotRight: ScreenRect,
    /** `corners` 声明里左下角可用吗。 */
    val leftEnabled: Boolean,
    /** 右下角可用吗。 */
    val rightEnabled: Boolean,
    /** 贴左时的**弧心** x（px）= 留距；它不是屏幕角，见 `FanEdgeMargin`。 */
    val cornerLeftX: Float,
    /** 贴右时的弧心 x（px）= 屏幕宽 − 留距。 */
    val cornerRightX: Float,
    /** 弧心 y（px）= 屏幕高 − 留距（左右相同）。 */
    val cornerY: Float,
    /** 弧心留距（px）。 */
    val edgeMarginPx: Float,
    /** 半径初值（px）= 短边 × `selection_radius_percent%`。 */
    val configuredRadius: Float,
    /** **最终半径**（px）—— 四条约束解完之后真正画出来的那个。 */
    val radius: Float,
    /** 「不重叠」要求的半径下限（px）。 */
    val requiredRadius: Float,
    /** 「屏内·靠近弧心那一侧」要求的半径下限（px）。 */
    val floorRadius: Float,
    /** 「屏内·远离弧心那一侧」允许的半径上限（px）。 */
    val capRadius: Float,
    /** 上限是**哪一项**定的（`1185·cos12.0°` 这种可复算的写法）。 */
    val capReason: String,
    /** 降级链走到哪一级（日志同款文案）。 */
    val fitDesc: String,
    /** 配置的图标直径（px）= `icon_size_dp × density`。 */
    val configuredIconDiameter: Float,
    /** **实际**图标直径（px）（可能被压小，下限 28dp）。 */
    val iconDiameter: Float,
    /** 命中圆半径（px）= 图标半径 + 6dp。 */
    val hitRadius: Float,
    /** 选中放大倍率（≥ 1）。预览画选中态时按它放大直径。 */
    val selectionScale: Float,
    /** 选中描环线宽（px）= 3dp。 */
    val ringStrokePx: Float,
    /** `selection_ring` 关着时为 `false`：预览不画选中描环（与真机一致）。 */
    val selectionRing: Boolean,
    /** 每层的容量与半径阶梯（日志同款对象）。 */
    val plan: FanPlan,
    /** 层与层的径向间距（px）= 配置图标直径 × 1.15。 */
    val rowStepPx: Float,
    /** 相邻层的错开量（格）：0 / 0.5。 */
    val staggerSlots: Float,
    /** 层的形状摘要（`8@709:12.0-82.0|… step=127 stg=0.50 vline=98`）。 */
    val arcs: String,
    /** 排版摘要（`plan=smart n=17`）。 */
    val planDesc: String,
    /** 每一项。数量 = 传入的 [FanPreviewParams.count]（**顺序由调用方给的清单顺序决定**）。 */
    val items: List<FanPreviewItem>,
    /** 图标形状（预览据此画轮廓，与真机同一份 [IconShapes.outline]）。 */
    val iconShape: IconShape,
    /**
     * **解析后的**形状参数：`icon_shape_corner` / `icon_shape_exponent` 里的 `0`
     * 已经解析成该形状自己的默认值（见 [IconShapes.resolveParams]）。
     *
     * 预览画轮廓时用**这个**（`IconShapes.outline(iconShapeParams, size)`），
     * 而不是 [iconShape] 那条「形状默认参数」的入口 —— 否则用户拖了圆角滑块，
     * 预览不动、真机在动（这正是 [FanPreviewParams.shapeCornerPercent] 存在的理由）。
     */
    val iconShapeParams: IconShapeParams,
    /** 图标白描边开不开（`icon_edge`）。关掉就是完全不画描边。 */
    val iconEdgeEnabled: Boolean,
    /**
     * 描边的**最终线宽（px）** = `icon_edge_width(dp) × density`（已按 dp→px 换算）。
     *
     * 上一版这个值是硬编码的 `0.5f` 且被**当 px 用**，所以同一份配置在不同密度的机器上
     * 粗细完全不成比例。这里给的永远是 px；
     * [iconEdgeEnabled] 为 `false` 时它也照常给出（预览可以据此画成灰色/虚线示意）。
     */
    val iconEdgeWidthPx: Float,
    /**
     * **图标本体**的不透明度（%，**原样带出**，已夹到 `0–100`）。
     *
     * 为什么预览必须拿到它：真机上 `icon_alpha` 乘在画图标位图的那把画笔上
     * （以及图标取不到时那个占位圆），预览照这个数画，就能看出「这一项有多淡」。
     *
     * ⚠️ **细白描边不跟这个值**（2026-09-19 解耦）。上一版细边烙在图标位图里、与图标
     * 共用同一个 alpha，当时的注释把这写成「预览照这个数画就不会有说不清的对照」；
     * 现在细边在真机上是**独立一笔、恒为 53% 白**（`FanOverlay.ICON_EDGE_COLOR`），
     * 所以预览侧也必须**用固定的描边 alpha**，否则会重新制造出那条「预览与真机对不上」。
     * 这条改动同时修掉了「`icon_alpha` 拉满、图标依然看着发灰」的用户实测问题。
     */
    val iconAlphaPercent: Float,
    /**
     * 展开动画的**起点系数** = `1 − reveal_amount% / 100`（终点恒为 1）。
     *
     * `65%` → `0.35`：图标从「离弧心 35% 处」长出来。数值可能为负
     * （`reveal_amount > 100`，起点越过圆心，Catalog 允许到 130），预览不要夹到 `[0,1]`。
     */
    val revealStartFactor: Float,
    /**
     * 小窗矩形（px）；**`null` = `window_preset = system`，交给系统摆放**
     * （预览这时不该画窗口框）。
     *
     * ⚠️ 与上一版的差别：上一版只有 `custom` 一个「有矩形」的档，另外只有「交给系统」。
     * 现在 `flyme` 与 `custom` **都会**给出矩形 —— 它们画出来其实是同一块
     * （`flyme` 档比的就是那四个默认值拼出来的矩形），但来源不同：
     * `flyme` 走 `WindowBounds.defaults()`、`custom` 走 `resolve()` 读四个滑杆。
     * 预览拿到的已经算好的矩形，和真机那一份是**同一个函数**算的（见类注释）。
     */
    val windowRect: ScreenRect?,
) {
    /** 项数（= [items] 的大小）。 */
    val count: Int get() = items.size

    /** 图标不透明度**倍率**（`0–1`）：预览画位图/占位圆直接用这个数。 */
    val iconAlpha: Float get() = (iconAlphaPercent / 100f).coerceIn(0f, 1f)

    /** 贴左/贴右时的弧心 x。 */
    fun cornerX(openToRight: Boolean): Float = if (openToRight) cornerRightX else cornerLeftX

    /**
     * 这一项在预览里「有没有超出屏幕」的越界像素（0 = 全在屏内）。
     *
     * 与真机的独立核验同源（[FanArcs.screenOverflow]）：判据是**整圆**在屏内，
     * 含选中放大与描环半宽。预览把它显示出来，就等于把「屏内」这条硬约束摆在用户眼前。
     */
    val screenOverflowPx: Float get() = FanArcs.screenOverflow(
        metricsForOverflow,
        screenW,
        screenH,
        edgeMarginPx,
        keepOutPx,
    )

    /** 圆心到屏幕边缘的最小距离（px）：选中放大 + 6dp 容差 + 描环半宽。 */
    val keepOutPx: Float get() = iconDiameter / 2f * selectionScale + FanArcs.HIT_TOLERANCE_DP * density +
        FanArcs.RING_STROKE_DP / 2f * density

    /** 只给越界核验用的一份 metrics（组装的三个量都取自本次求解，不是另推一遍）。 */
    private val metricsForOverflow: FanMetrics get() = FanMetrics(plan, radius, rowStepPx, staggerSlots)

    override fun toString(): String =
        "FanPreview(${screenW.toInt()}x${screenH.toInt()} r=${configuredRadius.toInt()}->${radius.toInt()} " +
            "icon=${iconDiameter.toInt()} n=$count $planDesc)"
}

/**
 * **给预览用的纯函数入口**：输入屏幕尺寸 + 各参数值，输出「每一项画在屏幕的哪里」。
 *
 * ## 为什么是一个纯函数 + 两个值类型
 *
 * `ui` 不许依赖 `hook`（`settings.gradle.kts` 里的模块边界），而预览必须与真机
 * **逐像素一致**：它不能自己推一遍半径、也不能自己写一套形状公式。所以：
 *
 * ```
 * ui  ──读配置──►  FanPreviewParams ──►  fanPreview()  ──►  FanPreview   （画）
 *                                          │
 * hook ──读配置──► FanSolveInput ──► FanSolve.solve() ──► FanSpec      （画 + 命中）
 * ```
 *
 * 两条路都走 [FanSolve] 与 [IconShapes]，**派生量只有一份实现** —— 预览说「半径 1097、
 * 图标 110、外层 3 项」，真机就是这三个数。
 *
 * 除了「每一项在哪」，这里还把**渲染参数**一并解析好带出去（预览不用自己重算几何）：
 * [FanPreview.iconShapeParams]（`0` 已解析成形状默认值）、[FanPreview.iconEdgeEnabled] /
 * [FanPreview.iconEdgeWidthPx]（dp→px 之后的描边）、[FanPreview.iconAlphaPercent]、
 * [FanPreview.revealStartFactor] 与每个 [FanPreviewItem] 的起点坐标
 * （`startCenterLeftX` / `startCenterRightX` / `startCenterY` / `startRadiusPx`）。
 *
 * ## 与真机**有意的**差别（写清楚，免得被当成 bug）
 *
 *  - **不含动画态**：真机的图标在展开期是「从弧心飞出 + 缩放 0.78→1 + 自转」，
 *    预览画的是**终态**（`reveal = 1`）；
 *  - **不含选中脉冲**（`selectionPop` 的 110ms 正弦），只有静态的选中放大倍率；
 *  - **不含"⋯"翻页槽的语义**：[count] 里把它算作一个槽，但预览不知道它是「更多」，
 *    画成一个普通圆即可（真机上那个槽是蓝色 `⋯`）。
 *
 * 除此之外的每一个数（半径、弧心、留距、每项的角度与圆心、窗口矩形）都与真机同源。
 */
fun fanPreview(params: FanPreviewParams): FanPreview {
    val solution = FanSolve.solve(
        FanSolveInput(
            screenW = params.screenW,
            screenH = params.screenH,
            density = params.density,
            radiusPercent = params.radiusPercent,
            iconDp = params.iconDp,
            layoutMode = params.layoutMode,
            customInner = params.customInner,
            customMiddle = params.customMiddle,
            edgeMarginDp = params.edgeMarginDp,
            staggerSlots = params.staggerSlots,
            selectionScalePercent = params.selectionScalePercent,
            count = params.count,
        ),
    )

    // ── 派生的「渲染参数」：形状 / 描边 / 不透明度 / 展开起点 ────────────────
    //
    // 四组量的解析都收在**契约层这一处**（`core`），调用方只读结果：
    // `ui` 画预览、`hook` 画真机，两边拿到的必须是同一套数（见类注释的「派生量只有一份实现」）。

    // 形状参数：`0` → 形状自己的默认值（老配置逐像素一致的**结构保证**，见 IconShapes.UNSET）
    val shapeParams = IconShapes.resolveParams(
        shape = params.iconShape,
        cornerPercent = params.shapeCornerPercent,
        exponent = params.shapeExponent,
    )

    // 描边线宽：dp → px。非法值（NaN/负）退出厂默认值。
    // ⚠️ 这里是「修正单位」的地方：上一版把 0.5 当 px 用，所以这次统一按 dp × density。
    val edgeDp = if (params.edgeWidthDp.isFinite()) {
        params.edgeWidthDp.coerceAtLeast(0f)
    } else {
        FanPreviewParams.DEFAULT_EDGE_WIDTH_DP
    }
    val edgeWidthPx = edgeDp * (if (params.density.isFinite() && params.density > 0f) params.density else 1f)

    // 不透明度：原样带出（夹到 0–100）。非有限值退 100%（不透明 = 加这个参数之前的样子）。
    val alphaPercent = if (params.iconAlphaPercent.isFinite()) {
        params.iconAlphaPercent.coerceIn(0f, 100f)
    } else {
        100f
    }

    // 展开起点系数。**非有限值退默认行程**：NaN 会让每一个坐标都变成 NaN，
    // 而预览画出来是空白（没有任何错误提示，用户只会说「预览没了」）。
    // 不夹到 [0,1]：`reveal_amount > 100` 时起点越过圆心是**合法**的（Catalog 允许到 130）。
    val amount = if (params.revealAmountPercent.isFinite()) {
        params.revealAmountPercent
    } else {
        FanPreviewParams.DEFAULT_REVEAL_PERCENT
    }
    val startFactor = 1f - amount / 100f

    // 热区：与 FanTrigger.cornerAt 的判据对齐（底部两角）。求值唯一来源是 FanHotZone
    // （基准 = 竖屏屏幕，横竖屏同一块热区）—— 别在这里手写 screen * percent
    val (hotLeft, hotRight) = FanHotZone.cornerRects(
        params.screenW, params.screenH, params.hotWidthPercent, params.hotHeightPercent,
    )

    // 弧心：两个轴都往屏内推（见 FanEdgeMargin）。屏幕坐标 = 弧心 + 本地偏移，贴右镜像 x。
    val cornerLeftX = solution.edgeMarginPx
    val cornerRightX = params.screenW - solution.edgeMarginPx
    val cornerY = params.screenH - solution.edgeMarginPx

    val centers = FloatArray(solution.plan.rows.sumOf { it.count } * 2)
    val items = ArrayList<FanPreviewItem>(solution.plan.rows.sumOf { it.count })
    var index = 0
    for (rowIndex in solution.plan.rows.indices) {
        val row = solution.plan.rows[rowIndex]
        for (slot in 0 until row.count) {
            solution.place(index, centers)
            val lx = centers[index * 2]
            val ly = centers[index * 2 + 1]
            // 起点 = 终点相对**弧心**缩放 startFactor 倍（与真机 revealFactor 的插值同一条公式，
            // 取 reveal = 0 那一端）。y 的符号与终点一致：本地坐标里 y 朝上为正。
            val sx = lx * startFactor
            val sy = ly * startFactor
            items.add(
                FanPreviewItem(
                    index = index,
                    rowIndex = rowIndex,
                    slotInRow = slot,
                    angleDeg = if (row.count <= 1) FanArcs.SINGLE_SLOT_DEG else solution.metrics.angleDeg(rowIndex, slot),
                    centerLeftX = cornerLeftX + lx,
                    centerRightX = cornerRightX - lx,
                    centerY = cornerY - ly,
                    diameter = solution.iconDiameter,
                    hitRadius = solution.hitRadius,
                    startCenterLeftX = cornerLeftX + sx,
                    startCenterRightX = cornerRightX - sx,
                    startCenterY = cornerY - sy,
                    startRadiusPx = kotlin.math.hypot(lx, ly) * kotlin.math.abs(startFactor),
                ),
            )
            index++
        }
    }

    // 小窗矩形：**系统档就是 null**（"交给系统摆放"是有语义的，不能用一个占位矩形假装）。
    // 三档的判别走 [WindowBounds.resolvePreset] —— **预览与 hook 用的是同一个函数**，
    // 这个框和真机上 `setLaunchBounds` 拿到的是同一个矩形
    // （两份实现必然漂移）
    val windowRect = WindowBounds.resolvePreset(
        preset = params.windowPreset,
        screenW = params.screenW,
        screenH = params.screenH,
        sliderWidthPercent = params.windowWidthPercent,
        sliderHeightPercent = params.windowHeightPercent,
        sliderXPercent = params.windowXPercent,
        sliderYPercent = params.windowYPercent,
    )

    return FanPreview(
        screenW = params.screenW,
        screenH = params.screenH,
        density = params.density,
        hotLeft = hotLeft,
        hotRight = hotRight,
        leftEnabled = params.corners != "bottom_right",
        rightEnabled = params.corners != "bottom_left",
        cornerLeftX = cornerLeftX,
        cornerRightX = cornerRightX,
        cornerY = cornerY,
        edgeMarginPx = solution.edgeMarginPx,
        configuredRadius = solution.configuredRadius,
        radius = solution.radius,
        requiredRadius = solution.requiredRadius,
        floorRadius = solution.floorRadius,
        capRadius = solution.capRadius,
        capReason = solution.capReason,
        fitDesc = solution.fitDesc,
        configuredIconDiameter = solution.configuredDiameter,
        iconDiameter = solution.iconDiameter,
        hitRadius = solution.hitRadius,
        selectionScale = solution.selectionScale,
        ringStrokePx = FanArcs.RING_STROKE_DP * params.density,
        selectionRing = params.selectionRing,
        plan = solution.plan,
        rowStepPx = solution.rowStep,
        staggerSlots = solution.staggerSlots,
        arcs = solution.arcs,
        planDesc = solution.planDesc,
        items = items,
        iconShape = params.iconShape,
        iconShapeParams = shapeParams,
        iconEdgeEnabled = params.edgeEnabled,
        iconEdgeWidthPx = edgeWidthPx,
        iconAlphaPercent = alphaPercent,
        revealStartFactor = startFactor,
        windowRect = windowRect,
    )
}
