package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 扇形几何的**不动点求解**：把「半径 / 图标直径」在一次求解里定完。
 *
 * 这一段是从 `hook/.../feature/smallwindow/FanGeometry.kt` 的 `FanConfig.spec` 里
 * **原样搬出来**的（只把「读配置」的部分留在了 hook）。搬它的理由是硬要求：
 *
 * > 派生量的计算必须在 core，否则预览和 hook 会算出两个答案。
 *
 * 设置界面的预览（`ui` 不许依赖 `hook`）要显示「半径多大、图标多大、每一项在哪」，
 * 而这些**全都是派生量**（由四条约束在同一个不动点上解出来）。预览要是自己再推一遍，
 * 就会出现「预览里好好的、真机上贴边被裁」这种最难查的偏差 —— 所以只留一份实现，
 * 两边都调它。参数值由调用方读好传进来（[FanSolveInput] 里全是值，没有 `Prefs`）。
 *
 * ## 求解的是四条约束
 *
 * ① 不重叠（下限，[FanArcs.effectiveRadius]）
 * ② 屏内·远离弧心那一侧（上限，[FanArcs.effectiveRadiusCap]）
 * ③ 屏内·靠近弧心那一侧（下限，[FanArcs.effectiveRadiusFloor]）
 * ④ 图标直径（[FanArcs.effectiveIconDiameter]，按 ①②③ 定完的半径反算，下限 28dp）
 *
 * **降级顺序写死为：屏内 > 不重叠 > 图标大小**。理由（照录原注释）：
 * 用户看不到的项 = 等于他选的应用丢了，所以「屏内」是硬约束；
 * 两者把半径夹住之后如果「不重叠」放不下了，就让**图标接力缩小**；
 * 连 28dp 的下限都压到就允许相邻项挤一点。
 *
 * ## 一个不动点，四处共用
 *
 * 角度依赖半径（止角的对齐比例 = 层半径之比），半径依赖角度（弦长、上限、下限都用角度），
 * 而 ② 的 keepOut 又依赖 ④ 算出来的图标直径 —— 所以只能迭代着解。
 * 绘制 / 命中 / 日志 / 预览都从 [FanSolution] 取值，**不允许任何调用点自己再推一遍**。
 */
class FanSolveInput(
    /** 屏幕宽（px）。**用整块显示器的 bounds**，不是扣掉系统栏的 displayMetrics。 */
    val screenW: Float,
    /** 屏幕高（px）。 */
    val screenH: Float,
    /** 密度（px/dp）。所有 dp 参数乘它换成 px。 */
    val density: Float,
    /** `selection_radius_percent`：半径初值 = 短边 × 它%。 */
    val radiusPercent: Float,
    /** `icon_size_dp`：图标基准边长（dp）。 */
    val iconDp: Float,
    /** `fan_layout_mode`：`smart` / `custom`。 */
    val layoutMode: String,
    /** 自定义模式下内排容量（外排是派生量，见 [FanRowAllocation.normalize]）。 */
    val customInner: Int,
    /** 自定义模式下中排容量。 */
    val customMiddle: Int,
    /** 弧心离屏幕边缘的留距（dp）。 */
    val edgeMarginDp: Float,
    /** `fan_row_stagger` 折成的格数：0（对齐）/ 0.5（错开半格）。 */
    val staggerSlots: Float,
    /** `selection_scale`：选中项放大倍率（百分比，>= 100）。 */
    val selectionScalePercent: Float,
    /** 槽位数（= 一页里画得下的项数，含「⋯」槽）。 */
    val count: Int,
)

/**
 * 一次求解的产物。**每个数都来自同一个不动点**。
 */
class FanSolution(
    val plan: FanPlan,
    /** 每层的半径与角度（**绘制、命中、日志、预览共用的唯一一份**）。 */
    val metrics: FanMetrics,
    /** 半径初值 = 短边 × `selection_radius_percent%`。日志 `r=` 的左半边。 */
    val configuredRadius: Float,
    /** 最终半径（clamp 之后）。日志 `r=` 的右半边。 */
    val radius: Float,
    /** 「不重叠」要求的半径下限（不含初值）。日志 `req=`。 */
    val requiredRadius: Float,
    /** 「屏内·靠近弧心那一侧」要求的半径下限。日志 `flr=`。 */
    val floorRadius: Float,
    /** 「屏内·远离弧心那一侧」允许的半径上限。日志 `cap=`。 */
    val capRadius: Float,
    /** 上限是**哪一项**定的（`1185·cos12.0°` 这种可复算的写法）。 */
    val capReason: String,
    /** 降级链走到哪一级。日志 `fit=`。 */
    val fitDesc: String,
    /** 排版摘要（模式 + 夹取后真正生效的三排数量）。日志 `plan=`。 */
    val planDesc: String,
    /** 「用户想要多大的半径」= max(初值, 不重叠, 屏内下限)。 */
    val wantRadius: Float,
    /** 实际图标直径（④ 修正后，>= 28dp）。 */
    val iconDiameter: Float,
    /** 命中圆半径 = 图标半径 + 6dp。 */
    val hitRadius: Float,
    /** 圆心到屏幕边缘的最小距离（px）。见 [FanSolve.keepOutOf]。 */
    val keepOut: Float,
    /** 层与层的径向间距（px）= 配置图标直径 × [FanArcs.ROW_STEP_FACTOR]。 */
    val rowStep: Float,
    /** 配置的图标直径（= `icon_size_dp × density`），它是「必须放得下」的量，不是下限。 */
    val configuredDiameter: Float,
    /** 图标直径下限（28dp × density）。 */
    val minIconDiameter: Float,
    /** 命中容差（6dp × density）。 */
    val gap: Float,
    /** 弧心留距（px）。 */
    val edgeMarginPx: Float,
    /** 错开量（格）。 */
    val staggerSlots: Float,
    /** 选中放大倍率。 */
    val selectionScale: Float,
) {
    /** 层数（= [plan] 的行数）。 */
    val count: Int get() = plan.rows.size

    /**
     * 第 [index] 项**相对弧心**的偏移（本地坐标：`x` 朝屏内为正、`y` 朝上为正），
     * 写进 [out]。屏幕坐标 = 弧心 + 偏移（贴右时 x 镜像），由调用方一处加上去。
     */
    fun place(index: Int, out: FloatArray) = metrics.place(index, out)

    /** 层的形状摘要（日志 `arcs=`）。 */
    val arcs: String get() = metrics.describe()
}

/**
 * 求解器。**全是纯函数**：不碰线程、不碰配置、不碰 Android。
 */
object FanSolve {

    /**
     * 按当前屏幕 + 当前槽位数解一份几何。
     *
     * 迭代次数写死 3 趟 + 1 轮定格（不是 `while(true)`）：收缩比很小（层距是常数项、
     * 相对半径只有百分之几），几步之后残差在亚像素级；真正兜底的是最后的
     * **独立核验**（[FanArcs.screenOverflow]，另一套公式），它用的是绘制时那一份几何，
     * 所以结论就是屏幕上的结论。
     */
    fun solve(input: FanSolveInput): FanSolution {
        val screenW = input.screenW
        val screenH = input.screenH
        val density = input.density
        val slots = input.count.coerceAtLeast(1)
        // 层分配（含自定义模式的联动夹取）—— 只用一次，结果同时喂给几何与日志
        val plan = FanArcs.plan(slots, input.layoutMode, input.customInner, input.customMiddle)
        // 用户配的图标直径（出厂 34dp）。**它是「必须放得下」的量，不是下限** ——
        // 下限是 FanArcs.MIN_ICON_DP（28dp），只喂给 effectiveIconDiameter。
        // 两个量在上游的形参里都叫 minimumDiameter，极易接错，故这里分开命名。
        val configuredDiameter = input.iconDp * density
        val gap = FanArcs.HIT_TOLERANCE_DP * density
        val minIcon = FanArcs.MIN_ICON_DP * density
        // 半径初值 = 短边 × selection_radius_percent%（FanRuntime.java:2059）
        val configured = min(screenW, screenH) * input.radiusPercent / 100f
        val selectionScale = (input.selectionScalePercent / 100f).coerceAtLeast(1f)
        // 弧心离屏幕边缘的留距（本工程自己加的，参照里没有）
        val edgePx = input.edgeMarginDp * density
        val staggerSlots = input.staggerSlots
        // 层距 = 配置图标直径 × ROW_STEP_FACTOR（见那个常量的推导）
        val rowStep = FanArcs.rowStep(configuredDiameter)

        // ── 「整圆在屏内」要求的**圆心离屏边的最小距离** ──────────────────────
        //
        // 出屏判据是「整圆」，所以这里要凑的是**半径**，而且是屏幕上真实出现的最大半径：
        //
        //   keepOut = 图标半径 × 选中放大倍率          // 选中项比别的大 selectionScale 倍
        //           + 6dp（命中容差 = 选中描环的偏移量）
        //           + 描环线宽的一半                  // 环的外缘才是圆的真正外沿
        //
        // 四点说明：
        //  1. **选中放大一定要算进去**：任何一项都可能被选中，而选中那一下它是
        //     `iconDiameter × selectionScale`（[FanOverlay] 的 drawSlot），少算这一项就会出现
        //     「平时不裁、一选中就出屏」——那正是最难查的一类表现。
        //  2. **描环一律留**（即使 `selection_ring` 关掉）：环宽只有 1.5dp，但若按开关分两条
        //     几何，用户切一下开关整把扇子会**跳一下**（半径变了）。宁可永远留这 1.5dp。
        //  3. **展开动画那 110ms 的 +10% 脉冲不算**：它是瞬态（`selectionPop`）、只影响几个 px，
        //     为它再压半径会把静态布局改差。真出现「刚选中的一瞬间贴边」那是动画帧，不是布局。
        //  4. 用的是**实际**图标直径（不是配置值）：图标会被 ④ 压小，压小之后圆心可以更靠边
        //     —— 所以它必须进不动点的循环（[keepOutOf] 在每轮里重算），否则大图标那档会
        //     无谓地留一大圈空白（实测：96dp 图标 + 24 项，差 135px 半径）。

        // ── 半径 R 的四条约束 + 图标「接力缩小」 ───────────────────────────────
        //
        // ① 不重叠（下限，[FanArcs.effectiveRadius]）
        // ② 屏内·远离弧心那一侧（上限，[FanArcs.effectiveRadiusCap]）
        // ③ 屏内·靠近弧心那一侧（下限，[FanArcs.effectiveRadiusFloor]）
        // ④ 图标直径（[FanArcs.effectiveIconDiameter]，按 ①②③ 定完的半径反算，下限 28dp）
        //
        // **降级顺序写死在这里：屏内 > 不重叠 > 图标大小。**
        // 也就是 R = clamp(max(初值, ①, ③), 上限 ②)：屏内那两条是硬约束，先满足它们；
        // 两者把半径夹住之后如果「不重叠」放不下了，就让**图标接力缩小**（④）；
        // 连 28dp 的下限都压到就允许相邻项挤一点 —— 但任何一项都不许跑到屏幕外，
        // 因为用户看不到的项 = 等于他选的应用丢了。
        //
        // 为什么要在四者之间迭代（而不是照参照那样一路算下来）：角度依赖半径
        // （止角的对齐比例 = 层半径之比），半径依赖角度（弦长、上限、下限都用角度），
        // 而 ② 的 keepOut 又依赖 ④ 算出来的图标直径 —— 是个不动点。
        // 迭代次数写死 3 趟 + 1 轮定格（不是 while(true)）：收缩比很小（层距是常数项、
        // 相对半径只有百分之几），几步之后残差在亚像素级；真正兜底的是最后的
        // **独立核验**（另一套公式），它用的是绘制时那一份几何，所以结论就是屏幕上的结论。
        var radius = configured
        var icon = configuredDiameter
        var keepOut = keepOutOf(icon, gap, density, selectionScale)
        var metrics = FanArcs.metrics(plan, radius, rowStep, staggerSlots)
        var required = 0f
        var floor = 0f
        /** 「想要多大的半径」= 用户初值 / 不重叠 / 屏内下限 里最大的那个。决定要不要报降级。 */
        var want = configured
        var cap = FanCap(Float.MAX_VALUE, -1, false, 0.0)
        repeat(3) {
            required = FanArcs.effectiveRadius(metrics, configuredDiameter, gap)
            icon = FanArcs.effectiveIconDiameter(metrics, configuredDiameter, minIcon, gap)
            keepOut = keepOutOf(icon, gap, density, selectionScale)
            cap = FanArcs.effectiveRadiusCap(metrics, screenW, screenH, edgePx, keepOut)
            floor = FanArcs.effectiveRadiusFloor(metrics, screenW, screenH, edgePx, keepOut)
            want = max(configured, max(required, floor))
            radius = min(want, cap.radius)
            metrics = FanArcs.metrics(plan, radius, rowStep, staggerSlots)
        }
        // 定格：用**最终这份** metrics 重算三条约束并再收敛一次（最多 3 轮）。
        // 退出条件是「上限 ≥ 实际半径 ≥ 下限」，所以「屏内」这条硬保证在最终几何上成立。
        var settling = 0
        while (settling < 3) {
            val nextIcon = FanArcs.effectiveIconDiameter(metrics, configuredDiameter, minIcon, gap)
            val nextKeepOut = keepOutOf(nextIcon, gap, density, selectionScale)
            val nextCap = FanArcs.effectiveRadiusCap(metrics, screenW, screenH, edgePx, nextKeepOut)
            val nextFloor = FanArcs.effectiveRadiusFloor(metrics, screenW, screenH, edgePx, nextKeepOut)
            val nextRequired = FanArcs.effectiveRadius(metrics, configuredDiameter, gap)
            // 「想要多大」= 用户初值 / 不重叠 / 屏内下限 三者里最大的那个（③④ 都可能比它大）
            want = max(configured, max(nextRequired, nextFloor))
            val next = min(want, nextCap.radius)
            icon = nextIcon
            keepOut = nextKeepOut
            cap = nextCap
            floor = nextFloor
            // `req=` 报的是**纯不重叠要求**（不含用户初值），这样它和 `cap=` 才可比：
            // 复算的人要一眼看出「半径是被屏内压下来的（cap<req）」还是「本来就不挤」
            required = nextRequired
            if (abs(next - radius) < 0.5f) {
                radius = next
                break
            }
            radius = next
            metrics = FanArcs.metrics(plan, radius, rowStep, staggerSlots)
            settling++
        }
        // 独立核验：换一套公式（逐项算屏幕坐标、四条边）再问一次「整圆在屏内吗」，
        // 越界像素数进日志。它的作用是抓「上面那套闭式解自己写错」——两个公式同时
        // 写错在同一个地方的概率远低于一个。正常时恒为 0。
        val overflow = FanArcs.screenOverflow(metrics, screenW, screenH, edgePx, keepOut)
        // 最终图标直径（① ② ③ 都定完之后才轮到它，见 effectiveIconDiameter 的要点 2）
        icon = FanArcs.effectiveIconDiameter(metrics, configuredDiameter, minIcon, gap)
        return FanSolution(
            plan = plan,
            metrics = metrics,
            configuredRadius = configured,
            radius = radius,
            requiredRadius = required,
            floorRadius = floor,
            capRadius = cap.radius,
            capReason = capReason(metrics, cap),
            fitDesc = fitDesc(
                want = want,
                required = required,
                floor = floor,
                cap = cap.radius,
                configuredDiameter = configuredDiameter,
                icon = icon,
                minIcon = minIcon,
                overflow = overflow,
                keepOut = keepOut,
            ),
            planDesc = planDesc(plan, slots, configured, radius, icon),
            wantRadius = want,
            iconDiameter = icon,
            hitRadius = icon / 2f + gap,
            keepOut = keepOut,
            rowStep = rowStep,
            configuredDiameter = configuredDiameter,
            minIconDiameter = minIcon,
            gap = gap,
            edgeMarginPx = edgePx,
            staggerSlots = staggerSlots,
            selectionScale = selectionScale,
        )
    }

    /**
     * 「整圆在屏内」判据里的 `keepOut`：圆心到屏幕边缘的**最小**距离（px）。
     *
     * 公式与理由写在 [spec] 的注释块里（选中放大 + 6dp 容差 + 描环半宽），这里只做计算 ——
     * 抽成函数是因为它必须**随实际图标直径**在不动点里重算。
     */
    private fun keepOutOf(iconDiameter: Float, gap: Float, density: Float, selectionScale: Float): Float =
        iconDiameter / 2f * selectionScale + gap + FanArcs.RING_STROKE_DP / 2f * density

    /**
     * 上限是**哪一项**定的，日志用。
     *
     * 报的是「**那一层的实际半径 · 哪个轴 · 多少度**」，例如 `1185·cos12.0°`
     * （= 外层的起角项离弧心的横向偏移顶到了屏幕侧边）或 `730·sin82.0°`（顶边）。
     * 这样复算的人拿计算器就能验证，而不是只知道「上限生效了」。
     */
    private fun capReason(m: FanMetrics, cap: FanCap): String {
        val i = cap.rowIndex
        if (i < 0 || i >= m.count) return "无"
        val fn = if (cap.vertical) "cos" else "sin"
        return m.rowRadius[i].toInt().toString() + "·" + fn + FanArcs.fmt(cap.angleDeg) + "°"
    }

    /**
     * 降级链走到哪一级（屏内 > 不重叠 > 图标大小）。**日志里那一句 `fit=`**。
     *
     * 每一级的判据都是**比较**，不是猜：
     *  - `fit=ok`：屏内那两条都没生效（上限 ≥ 期望值 ≥ 下限），半径就是「用户初值和不重叠
     *    要求里较大的那个」，图标保持配置值；
     *  - `fit=压半径(图标未缩)`：上限把半径压到了「用户要的那部分」以下，但**不重叠仍然成立**
     *    （图标还放得下），所以只体现为扇子比用户要的小；
     *  - `fit=缩图标110->103`：上限压得太狠、不重叠放不下了 → 图标按新的中心距**接力缩小**；
     *  - `fit=触底(28dp,邻项可挤)`：连 28dp 的下限都压到，说明「屏内 + 不重叠 + 图标不小于
     *    28dp」三者物理上不能同时满足 —— 此时**保留屏内**，允许相邻项挤/轻微重叠。
     *    这是写死的优先级：宁可挤也不能有项跑到屏幕外（用户看不到的项 = 他选的应用丢了）。
     *  - `fit=!核验出屏…px`：最后的独立核验发现有项越界（公式问题，正常不该出现）。
     */
    private fun fitDesc(
        want: Float,
        required: Float,
        floor: Float,
        cap: Float,
        configuredDiameter: Float,
        icon: Float,
        minIcon: Float,
        overflow: Float,
        keepOut: Float,
    ): String {
        // ① 独立核验失败优先报 —— 那说明闭式解本身有问题，比任何降级都严重
        if (overflow > 0.5f) {
            return "fit=!核验出屏" + overflow.toInt() + "px(公式有问题)"
        }
        // ② 上限没生效：半径拿到了想拿的那个值
        if (cap >= want - 0.5f) return "fit=ok"
        // ③ 上限 + 下限把区间夹没了：屏内那两条自己打架（现实中到不了，
        //    阈值推导见 effectiveRadiusFloor：需要屏幕短于 ~2×keepOut 才可能）
        if (floor > cap + 0.5f) {
            return "fit=!无解(下限" + floor.toInt() + ">上限" + cap.toInt() + ",保上限)"
        }
        if (icon <= minIcon + 0.01f) {
            return "fit=触底(" + FanArcs.MIN_ICON_DP.toInt() + "dp,邻项可挤)"
        }
        if (icon < configuredDiameter - 0.5f) {
            return "fit=缩图标" + configuredDiameter.toInt() + "->" + icon.toInt()
        }
        // 半径被压小，但图标还是原尺寸：上限只压掉了「用户要的那部分」或「弦长留的富余」，
        // 不重叠仍然成立（见 effectiveIconDiameter 的要点 3）
        return "fit=压半径(图标未缩,keepOut" + keepOut.toInt() + ")"
    }

    /**
     * 日志里的排版摘要。
     *
     * 自定义模式下必须打**夹取之后**的三排数量：用户配的是「上限」，真正画出来的是
     * `FanRowAllocation.normalize` 的结果，两者在项数变化时并不相等 ——
     * 这正是「参数之间联动」的唯一可复核证据（外排是派生量，日志里必须看得到它）。
     */
    private fun planDesc(plan: FanPlan, slots: Int, configured: Float, radius: Float, icon: Float): String {
        if (!plan.custom) return "plan=smart n=" + slots
        // 还原成「内/中/外」三元组（含 0 的排不上屏，但日志里要有）
        val triple = IntArray(3)
        for (row in plan.rows) triple[row.radialIndex] = row.count
        return "plan=custom " + triple[0] + "/" + triple[1] + "/" + triple[2] +
            " n=" + slots +
            " cfg=" + configured.toInt() + " r=" + radius.toInt() + " icon=" + icon.toInt()
    }
}
