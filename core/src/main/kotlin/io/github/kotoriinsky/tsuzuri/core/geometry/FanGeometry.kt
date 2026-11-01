package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 底角扇形菜单的**纯几何**：分层、角度、半径修正。
 *
 * ## 为什么这一半在契约层（core），另一半在 hook
 *
 * 这个文件是从 `hook/.../feature/smallwindow/FanGeometry.kt` 里**原样搬出来**的：
 * 那 1780 行里，凡是算数的部分都不依赖 `android.*`（搬之前核实过：
 * 该文件里 `android` 出现 0 次），所以它可以、也应该待在契约层。搬的收益有两条：
 *
 *  1. **设置界面的预览**（`ui` 不许依赖 `hook`）要能算出「每一项画在屏幕的哪里」——
 *     预览和真机必须是**同一份公式**，否则两边会给出两个答案；
 *  2. 纯函数可以脱离设备做单测（`core/src/test/.../geometry/`），
 *     而「半径被谁顶了、角度按哪个半径算的」这类问题一旦只能靠看日志，就查不动。
 *
 * **留在 hook 的是「把配置读出来」的四件套**（`FanEdgeMargin` / `FanPref` /
 * `FanIconAlpha` / `FanRowStagger`）—— 它们要 `HookRegistry` / `Prefs`，契约层不许有。
 * 几何本身一个字节都没改：类名、函数签名、常量、注释（那些注释记录了「为什么是这个数」，
 * 是这份代码最值钱的部分）全部照原样搬过来，`hook` 侧只多了几条 `import`。
 *
 * ## 模型来自 HyperGesture（不再是 Flyme）
 *
 * 素材（反编译产物，行号均为该目录下的相对路径与行号）：
 * `research/refapps/com.oxohang.fanfreeform/sources/com/oxohang/fanfreeform/`
 *
 * | 抄什么 | 出处 |
 * | --- | --- |
 * | 1–3 层同心弧、基准层张 70°（12°–82°） | `xposed/GestureGeometry.java:118-146`（`iconCenter`） |
 * | **内层**半径 0.68 × R | 同上 `:142`（`radius * (radialIndex * 0.2f + 0.68f)` 里 `radialIndex = 0` 那一项） |
 * | 层怎么分（1–3 层、每层几项） | 同上 `:216-247`（`fanRowCount` / `fanRowCounts`） |
 * | 命中 = **逐个图标的圆**，取第一个命中 | 同上 `:55-66`（`FanLayout.selection`） |
 * | 半径/图标直径互相让位（防重叠） | 同上 `:549-601`（`effectiveRadius` / `effectiveIconDiameter`） |
 * | 半径初值 = 短边 × `selection_radius_percent%` | `xposed/FanRuntime.java:2058-2065` |
 * | 触发区 = 底部两角矩形、左角优先 | 同上 `:76-87`（`cornerAt`） |
 * | 方向锥 0.25 / 0.08 两级系数 | `xposed/GestureArbitrator.java:5-6,18-29` |
 *
 * ## 角度基准（以代码为准）
 *
 * `GestureGeometry.iconCenter` 是
 *
 * ```java
 * horizontal = rowRadius * cos(radians);   // 沿底边、朝屏内
 * vertical   = rowRadius * sin(radians);   // 朝上
 * return (corner == LEFT ? horizontal : width - horizontal, height - vertical);
 * ```
 *
 * 所以 `0°` = **沿底边朝屏内**、`90°` = **沿侧边朝上**，扇形张 12°–82°（对称于 45° 对角线）。
 *
 * ## 弧心（**偏离参照的一处**：弧心不在屏幕角上）
 *
 * 参照实现与魅族实测都把弧心钉在屏幕角：贴左 `(0, H)`、贴右 `(W, H)`。
 * **我们额外往屏内推了一段**（`fan_edge_margin`）—— 原因是用户反馈「贴边的项被裁」。
 * 弧心 = 贴左 `(edge, H − edge)`、贴右 `(W − edge, H − edge)`，
 * 顶点存的是**相对弧心的偏移**（[FanMetrics.place]），屏幕坐标由 hook 侧
 * `FanSpec.itemX` / `FanSpec.itemY` 一处加上去 —— 绘制与命中都走那两个函数，
 * 所以留距改了不会错位。
 */

/**
 * 扇形的一层（一条同心弧）。
 *
 * @param count 这一层放几项
 * @param radialIndex 半径阶梯下标：0 = 内层、1 = 中层、2 = 外层。
 *   HyperGesture 的 `radialIndex` 就是「第几排」，半径与错位都由它推出来
 *   （`GestureGeometry.iconCenter`）。
 */
class FanRow(val count: Int, val radialIndex: Int)

/**
 * 一次排版的结果：排 + 用的是哪种模式。
 *
 * 为什么要带上 `custom`：HyperGesture 的半径系数是
 * `(custom || rows.length > 1) ? 0.68 + radialIndex*0.2 : 1.0`
 * （`GestureGeometry.iconCenter:142`、`effectiveRadius:562`）——
 * **自定义模式下即使只有一排也要乘 0.68**，而智能模式单排用整半径。
 * 少了这个标志，两种模式在「只有一排」时会画出不同的东西。
 */
class FanPlan(val rows: List<FanRow>, val custom: Boolean)

/**
 * 内/中/外三排容量的**联动夹取**。
 *
 * 逐字对应 `config/FanRowAllocation.java:8-58`：
 *
 * ```
 * resolvedInner  = clamp(inner,  max(0, total-24), min(total, 8))
 * remaining      = total - resolvedInner
 * resolvedMiddle = clamp(middle, max(0, remaining-14), min(remaining, 10))
 * outer          = remaining - resolvedMiddle          // ← 外排永远是派生量
 * ```
 *
 * 三条不变量，这就是用户拖「内排」时**其它两个自动让位**的全部机制：
 *  1. `inner ≤ 8`、`middle ≤ 10`、`outer ≤ 14`（24 = 10 + 14 是内排的下限来源）；
 *  2. **三排之和恒等于 total**（外排不参与夹取，它是减出来的）；
 *  3. 用户配的值只被当**上限**看，`total` 一变（我们的项数是动态的）它们就被重新夹一次。
 *
 * ⚠️ 与上游的一处差别：它在**配置加载时**用 `targets.size()` 调一次
 * （`xposed/GestureConfig.java:335`），因为它的项数是用户手挑的、相对稳定；
 * 我们的项数随「最近使用」变化，所以在 [FanArcs.plan] 里按**当前槽位数**每次重算 ——
 * 语义相同（之和恒等于项数），只是时机跟着我们的动态项数走。
 */
object FanRowAllocation {

    const val MAX_INNER = 8
    const val MAX_MIDDLE = 10
    const val MAX_OUTER = 14

    /** @return `[inner, middle, outer]`，三者之和恒等于 `total`。 */
    fun normalize(total: Int, inner: Int, middle: Int): IntArray {
        val safeTotal = max(0, total)
        val resolvedInner = clamp(inner, minimumInner(safeTotal), maximumInner(safeTotal))
        val remaining = safeTotal - resolvedInner
        val resolvedMiddle = clamp(middle, minimumMiddle(remaining), maximumMiddle(remaining))
        return intArrayOf(resolvedInner, resolvedMiddle, remaining - resolvedMiddle)
    }

    fun minimumInner(total: Int): Int = max(0, max(0, total) - MAX_MIDDLE - MAX_OUTER)

    fun maximumInner(total: Int): Int = min(max(0, total), MAX_INNER)

    fun minimumMiddle(remaining: Int): Int = max(0, max(0, remaining) - MAX_OUTER)

    fun maximumMiddle(remaining: Int): Int = min(max(0, remaining), MAX_MIDDLE)

    private fun clamp(value: Int, minimum: Int, maximum: Int): Int =
        max(minimum, min(maximum, value))
}

/**
 * 纯几何：分层、角度、半径修正。**全是纯函数**，不碰线程、不碰配置。
 *
 * 逐条对应 `GestureGeometry`（见 [FanSpec] 的类注释里的表）。
 */
object FanArcs {

    /** `fan_layout_mode` 的 `custom` 取值（HyperGesture 的 `FAN_LAYOUT_CUSTOM = 2`）。 */
    const val LAYOUT_CUSTOM = "custom"

    /** **基准层**（内层）固定张角 70°（`GestureGeometry.iconCenter:137,139`）。 */
    const val SPAN_DEG = 70.0

    /** 基准层起始角 12°。0° = 沿底边朝屏内，90° = 沿侧边朝上（见 [FanSpec] 类注释）。 */
    const val START_DEG = 12.0

    /**
     * 基准层止角 82°（= 12 + 70）。
     *
     * 它是**所有层**止角的来源：其余层的止角由「端点落在同一条竖直线上」反解出来
     * （[FanMetrics] 的规则 ②）。所以它不再是「每层都张到这个角」，而是
     * 「**基准层**张到这里，别的层按对齐规则推」。
     */
    const val END_DEG = START_DEG + SPAN_DEG

    /** 单层只有一项时，把它放在 70° 扇的正中：12 + 35 = 47°（`:139` 的 `47.0d`）。 */
    const val SINGLE_SLOT_DEG = START_DEG + SPAN_DEG / 2.0

    /** 最多 3 层（`fanRowCount` 的 `min(3, ...)`，`:217`）。 */
    const val MAX_ROWS = 3

    /**
     * **基准层**（内层）的半径系数 0.68（`:142` 的 `radialIndex = 0` 那一项）。
     *
     * ⚠️ 参照实现这里是一整组阶梯 `0.68 / 0.88 / 1.08`（`radialIndex * 0.2f + 0.68f`），
     * 我们**只保留内层这一档**：层与层的间距不再按半径的比例走，而是按**图标直径**走
     * （[ROW_STEP_FACTOR]）。也就是说「内层半径 = 0.68 × 用户配的半径」这条抄着，
     * 往外第三层半径不抄参照的 1.08R。
     */
    const val INNER_FACTOR = 0.68f

    /**
     * 层与层的径向间距 = **配置的图标直径 × 它**（不是半径的固定比例）。
     *
     * ## 为什么行距必须跟图标走（这是**刻意偏离参照**的美学调整）
     *
     * 参照的层距是 `0.2R` —— 在本机默认参数下（`R = 1338`）等于 **268px**，
     * 而图标直径只有 **139px**：行距 / 直径 = **1.93**。三行因此看起来是三条
     * 互不相干的弧，而不是一朵花的三圈。用户实测原话：「行与行之间更靠近一些」。
     *
     * 行距与半径脱钩的第二层理由：**半径本身是会让位的**（[effectiveRadius] 会把它往上顶、
     * [effectiveRadiusCap] 会把它往下压）。行距要是跟着半径走，图标变小的时候行距还在，
     * 三行的疏密就跟着半径一起漂 —— 而「行距」这个量的正确定义本来就是「两行图标不叠」，
     * 那是**长度**量纲，不是比例量纲。
     *
     * ## 1.15 的取值理由（未实测，取值理由）
     *
     *  - **下限 1.00**：行距 = 直径时两行的图标圆**刚好外切**，低于它一定会叠在一起。
     *  - **1.05**（= 直径 + 5%）：在 110px 的图标上只留 5.5px ≈ 1.7dp间隙，520dpi 下
     *    基本看不出来，等于外切。
     *  - **1.15**（采用）：留 17px ≈ **5dp** 的可见间隙 —— 既明显比原来的 1.93 倍紧，
     *    又不会让两行的图标粘成一团。这个值是**看图定的**：先在真机上按 1.15 出图，
     *    三行能被眼睛分开（见 `research/compare/` 的截图），再没有往两边调的理由。
     *
     * ⚠️ 行距用的是**配置的图标直径**（`icon_size_dp`），不是修正后的实际直径：
     * 图标直径是**算出来的**（[effectiveIconDiameter] 会往下压），用它会让行距和半径
     * 互相依赖成环。用配置值则是「用户说要多大的图标，行距就按那个给」——
     * 而实际图标只会 ≤ 配置值，所以实际间隙只会比 5dp **更大**，方向是安全的。
     */
    const val ROW_STEP_FACTOR = 1.15f

    /**
     * 命中圆的**容差** 6dp。
     *
     * HyperGesture 里 `density * 6.0f` 这一个常量同时充当**四**个角色，不是四个参数：
     *
     * | 角色 | 出处 |
     * | --- | --- |
     * | `effectiveRadius` 的间距下限（弦长 ≥ 直径 + 它） | `FanRuntime.java:2060` |
     * | `effectiveIconDiameter` 的收缩量（弦长 − 它） | `FanRuntime.java:2064` |
     * | 命中圆半径 = 图标半径 + 它 | `FanRuntime.java:1830,1848,1866` |
     * | 选中描环半径 = 图标半径 + 它 | `FanOverlayView.java:432` |
     *
     * 所以「同一个 6dp 既当间距又当容差」**不是我们混用**，是上游就这么设计的：
     * 弦长被保证 ≥ `d + 6dp`，而命中圆半径是 `d/2 + 6dp` —— 相邻两个圆于是恒有
     * `d + 12dp − (d + 6dp) = 6dp` 的重叠带，图标之间不会出现「碰不到」的缝
     * （见 [FanArcs.effectiveRadius]）。
     *
     * ⚠️ 别把它和 [MIN_ICON_DP]（28dp）搞混：那个是**图标直径的下限**，
     * 只喂给 `effectiveIconDiameter`，在这里没有角色。
     */
    const val HIT_TOLERANCE_DP = 6f

    /**
     * 图标直径的**下限** 28dp。
     *
     * 对应 `FanRuntime.java:2064` 传给 `effectiveIconDiameter` 的 `density * 28.0f`
     * —— 它的第 4 个形参（上游叫 `minimumDiameter`）。**只在这个函数里有意义**：
     * 屏幕特别窄 / 项数特别多时宁可挤一点，也不让图标小到看不见。
     *
     * 注意 `effectiveRadius` 的第 3 个形参也叫 `minimumDiameter`（`GestureGeometry.java:541`），
     * 但上游的调用点 `FanRuntime.java:2060` 传进去的是 **`iconSizeDp * density`**（配置的
     * 图标直径，出厂 34dp），**不是这个 28dp** —— 形参名和实参语义不一致，读代码时容易
     * 在这里把两个量接错（我们的 `FanConfig.spec` 现在用 `configuredDiameter` 命名，
     * 就是为了不再踩这个坑）。
     */
    const val MIN_ICON_DP = 28f

    /**
     * 选中描环的**线宽** 3dp（`FanOverlayView.java:122,431-433` 的 `dp(3)`）。
     *
     * 它同时是**几何常量**，不只属于绘制：描环画在 `图标半径 + 6dp` 上，所以圆的真正外沿
     * 是 `图标半径 + 6dp + 线宽/2` —— [FanConfig.spec] 算「整圆在屏内」那个 [keepOut] 时
     * 要用到它。因此这个数**只能有一处定义**：绘制（[FanOverlay] 的 ringPaint）与几何共用
     * 这个常量，谁改了线宽几何就自动跟着改，不会出现「描环加粗之后就贴边出屏」。
     */
    const val RING_STROKE_DP = 3f

    /**
     * 行/弧的分配。对应 `GestureGeometry.fanRows`（`:160-206`）的两个分支。
     *
     * ## 智能模式（`fan_layout_mode = smart`，上游的 0）
     *
     * 逐字对应 `fanRowCount`（`:217`）+ `fanRowCounts`（`:239-246`）：
     *
     * ```
     * 层数 = max(1, min(3, (n + 7) / 8))     →  1–8 项 1 层，9–16 项 2 层，17–24 项 3 层
     * 每层数量尽量均衡：base = n / 层数，余数从最后一层往前各 +1
     * ```
     *
     * ## 自定义模式（`fan_layout_mode = custom`，上游的 2）
     *
     * 对应 `fanRows` 的 `layoutMode == 2` 分支（`:176-205`）：容量按
     * **内 → 中 → 外**依次填满，装不下的余量**全部塞进外排**；
     * 容量为 0 的排直接不出现（不画空弧）。
     * `inner/middle` 先过 [FanRowAllocation.normalize] 夹取，`outer` 是它算出来的。
     *
     * ⚠️ **偏离**：上游的出厂默认就是自定义模式（`DEFAULT_FAN_LAYOUT_MODE = 2`、
     * 内 7 / 中 8 / 外 0，`xposed/GestureConfig.java:74-77`），我们的默认是智能模式。
     * 理由：自定义模式要多配两个数量才有意义，而项数少时两种模式的层数与半径阶梯
     * **完全相同**（差别只在每排几个），智能模式少一步配置。
     * 用户要复刻它的出厂形态，把模式切到「自定义」（默认值就是 7/8）即可。
     */
    fun plan(count: Int, layoutMode: String, inner: Int, middle: Int): FanPlan {
        val n = max(1, count)
        if (layoutMode != LAYOUT_CUSTOM) {
            val rows = max(1, min(MAX_ROWS, (n + 7) / 8))
            val base = n / rows
            val remainder = n % rows
            return FanPlan(
                (0 until rows).map { i ->
                    FanRow((if (i >= rows - remainder) 1 else 0) + base, i)
                },
                custom = false,
            )
        }

        val allocation = FanRowAllocation.normalize(n, inner, middle)
        val actual = IntArray(allocation.size)
        var remaining = n
        for (r in allocation.indices) {
            if (remaining <= 0) break
            actual[r] = min(allocation[r], remaining)
            remaining -= actual[r]
        }
        // 理论上到不了这里（normalize 保证 over 的份额恒等于 remaining），兜一层：
        // 真出现就把剩下的全给外排，宁可挤也不要少画一项。
        if (remaining > 0) actual[actual.size - 1] += remaining

        val rows = ArrayList<FanRow>(3)
        for (r in actual.indices) {
            if (actual[r] > 0) rows.add(FanRow(actual[r], r))
        }
        return FanPlan(rows, custom = true)
    }

    /** 该层用不用**错开**：照参照的奇偶规则（多层 + `radialIndex` 为奇数 = 中层，`:134`）。 */
    fun staggered(plan: FanPlan, row: FanRow): Boolean =
        plan.rows.size > 1 && (row.radialIndex and 1) == 1

    /**
     * 该层的**半径比例项** `a`：`该层半径 = a × R + 层序号 × 行距`。
     *
     * 单排**且非自定义模式**时不打折（`:142` / `:562` 的 `custom || rows.length > 1`）——
     * 「只有一排」时半径就是用户配的那个 R，不乘 0.68。这条与参照一致。
     *
     * ⚠️ 与参照的差别在**没有常数项**这件事上：参照的层半径是纯比例（`0.68/0.88/1.08` × R），
     * 我们的层半径有一个跟图标走的常数项（[rowStep] × 层序号）。见 [ROW_STEP_FACTOR]。
     */
    fun rowScale(plan: FanPlan, row: FanRow): Float =
        if (!plan.custom && plan.rows.size <= 1) 1f else INNER_FACTOR

    /** 层与层的径向间距（px）= 配置图标直径 × [ROW_STEP_FACTOR]。推导见那个常量。 */
    fun rowStep(configuredDiameter: Float): Float = configuredDiameter * ROW_STEP_FACTOR

    /** 固定 `Locale.US`：日志里的小数点必须是 `.`，否则复算的人要猜本地化格式。 */
    fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    /**
     * 给定半径 R，把**每层的半径 / 起止角 / 步进**一次算好（见 [FanMetrics]）。
     *
     * 为什么要包成一步：加了「端点对齐」之后，层的止角依赖**层半径之比**，
     * 而层半径又依赖 R —— 角度和半径成了互相依赖的两个量。散着写一定会出现
     * 「日志里的角度」和「画出来的角度」不是同一次算的，那是最难看的一类偏差。
     */
    fun metrics(plan: FanPlan, radius: Float, rowStep: Float, stagger: Float): FanMetrics =
        FanMetrics(plan, radius, rowStep, stagger)

    /**
     * 「不重叠」：半径 R 至少要多大，才能让**每一层**的相邻项中心距 ≥ 图标直径 + 间隙。
     *
     * 闭式解，一趟算完（没有迭代、也没有第二个未知量）：
     *
     * ```
     * 相邻项中心距 = 该层半径 × 2·sin(步进/2)                      // 弦长公式 l = 2ρ·sin(θ/2)
     * 该层半径     = a·R + b                                       // a 见 rowScale，b = 层序号 × 行距
     * 要求         a·R + b ≥ (d + gap) / (2·sin(步进/2))
     * 于是         R ≥ ( (d+gap)/(2·sin(步进/2)) − b ) / a           // 每层一个候选，取最大的
     * ```
     *
     * `(d+gap)/(2·sin(步进/2))` 就是**弦长公式反解半径**：给定「相邻两项中心距必须
     * ≥ 图标直径 + 间隙」，问该层半径至少多大。
     *
     * ⚠️ 与参照的一处**结构性**差别：参照的 `factor`（0.68/0.88/1.08）在分母上，
     * 因为它把层半径写成**纯比例**；我们的层半径有**常数项**（层序号 × 图标行距），
     * 所以解出来是个仿射式而不是纯比例。语义相同：都是「最挤的那层替所有层定半径」。
     *
     * ⚠️ 它**只会把半径往上顶**，本身不含用户的初值（`configured`）：调用方取
     * `max(初值, 它)` 才等价于参照的语义（[FanConfig.spec] 就是这么接的）。
     * 往下压它的是 [effectiveRadiusCap]、往上顶的还有 [effectiveRadiusFloor] ——
     * 三条方向不同的约束，优先级与降级顺序见 [FanConfig.spec]。
     *
     * ⚠️ 形参名照搬上游：`diameter` 在**这里**收到的是**配置的图标直径**
     * （`iconSizeDp × density`，出厂 34dp），不是 [MIN_ICON_DP] 那个 28dp。
     * 名字在上游叫 `minimumDiameter` 是因为它同时被 `effectiveIconDiameter` 当作
     * 「不能再小」的那条线，两个函数对它的用法不同，别互换。
     *
     * ## 一处有意的偏离：span
     *
     * 上游在**智能模式**下用 `span = 58°`（`:560`），而同一层在 `iconCenter` 里是按 `70°`
     * 排的（`:137,139`）—— 上游这两处对不上。
     * 我们**两处都用同一份角度**（[FanMetrics] 里算的那一份）：只要修正用的步进和实际
     * 排布的步进一致，「相邻项间距 ≥ 图标直径 + 6dp」就是精确成立的；用 58° 会算出偏大的
     * 半径（更松，但扇子会无谓变大、还可能顶出屏幕）。
     *
     * 这条约束是「命中退化成逐个图标的圆」能用的前提：图标圆的半径是 `d/2 + 6dp`，
     * 两个相邻圆相交只需要中心距 < `d + 12dp`；这里保证中心距 ≥ `d + 6dp`，
     * 于是相邻圆恒有 6dp 的重叠带 —— 图标之间不会出现「选中不了」的死区。
     * （上游自己的注释没写这一层，但从它的调用点 `FanRuntime.java:2060` 与容差 6dp 能反推。）
     */
    fun effectiveRadius(m: FanMetrics, diameter: Float, gap: Float): Float {
        var required = 0f
        for (i in 0 until m.count) {
            val step = m.stepRadians(i)
            if (step <= 0.0 || m.scale[i] <= 0f) continue
            val chordFactor = 2.0 * sin(step / 2.0)
            if (chordFactor <= 0.0) continue
            val need = ((diameter + gap) / chordFactor).toFloat()
            required = max(required, (need - m.offset[i]) / m.scale[i])
        }
        return required
    }

    /**
     * 「屏内」：半径 R 的**上限** —— 保证**每一项的整圆**（含选中放大与描环）都在屏内。
     *
     * 这是本工程新增的**第二条**半径约束（参照只有 [effectiveRadius] 那条下限）。
     * 起因是实测：`fan_max_targets = 24` 时半径被顶到 1338px，外层
     * （`1.08 × 1338 = 1445`）在起角 12° 上的屏幕 x = **−243px** —— 外层 3 项**整项在屏外**。
     * 用户看不到的项 = 等于用户选的应用丢了，所以这条比「不重叠」还要优先。
     *
     * ## 怎么算：逐项、两个轴，取最紧的那个
     *
     * 屏幕坐标（贴右）= `(W − edge − ρ·cosθ, H − edge − ρ·sinθ)`（见 [FanSpec.itemX]），
     * 贴左的 x 是 `edge + ρ·cosθ`。两边都要求「圆心离屏边 ≥ [keepOut]」，于是
     * **两个角的约束式完全相同**（都是 `ρ·cosθ ≤ W − edge − keepOut`），这里不用分支。
     *
     * 出屏判据是「**整圆**在屏内」，所以减掉的是**半径**不是直径：`keepOut` 里含图标半径
     * （且含选中放大）、6dp 容差、描环半宽 —— 由 [FanConfig.spec] 一处算出来传进来。
     *
     * 每层只需要看**两个极值项**，因为 `cosθ` 在 `[0°,90°]` 上单调降、`sinθ` 单调升：
     *
     * | 轴 | 最紧的那一项 | 为什么 |
     * | --- | --- | --- |
     * | x（远离弧心那一侧） | **起角项**（θ 最小 → cosθ 最大 → 离侧边最近） | 贴右时它最靠左、贴左时它最靠右，两个角都是它 |
     * | y（远离弧心那一侧） | **止角项**（θ 最大 → sinθ 最大 → 离顶边最近） | 顶边与贴左/贴右无关 |
     *
     * ```
     * 每层： a·R + b ≤ limitX / cos(起角)  →  R ≤ (limitX/cos(起角) − b) / a      // x 轴
     *        a·R + b ≤ limitY / sin(止角)  →  R ≤ (limitY/sin(止角) − b) / a      // y 轴
     * 上限 = 所有层 × 两个轴里最小的那个
     * ```
     *
     * ⚠️ **只算一个轴是不够的**：本机（1220×2656）是 x 轴先顶到（屏窄），
     * 而横屏（2656×1220）是 y 轴先顶到。两个轴都必须算。
     *
     * ⚠️ 这个上限只管「远离弧心的那一侧」。弧心的另一侧还有**同样重要的一半**：
     * 弧心离屏边只有 [edge]（默认 10dp = 32.5px），而圆心至少要离屏边 [keepOut]
     * （默认近 80px）—— 也就是 `edge < keepOut` 时，**最靠近弧心的那一列项本来就会出屏**，
     * 必须把半径**撑大到**它们让开。那条是 [effectiveRadiusFloor]（下限，方向相反）。
     * 一开始我只实现了上限，mock 里 96dp 图标那档立刻抓到问题：`keepOut = 215px > edge`，
     * 止角项离屏边只有 71px，**整圆超出右边缘 143px**。两个方向都要算，缺一不可。
     *
     * @param keepOut 圆心到屏幕边缘的**最小**距离（px）。推导见 [FanConfig.spec]
     */
    fun effectiveRadiusCap(
        m: FanMetrics,
        screenW: Float,
        screenH: Float,
        edge: Float,
        keepOut: Float,
    ): FanCap {
        val limitX = screenW - edge - keepOut
        val limitY = screenH - edge - keepOut
        if (limitX <= 0f || limitY <= 0f) return FanCap(0f, -1, false, 0.0)
        var cap = Float.MAX_VALUE
        var row = -1
        var vertical = false
        var angle = 0.0
        for (i in 0 until m.count) {
            val a = m.scale[i]
            if (a <= 0f) continue
            val cosStart = cos(Math.toRadians(m.startDeg(i)))
            if (cosStart > 1e-9) {
                val candidate = ((limitX / cosStart).toFloat() - m.offset[i]) / a
                if (candidate < cap) {
                    cap = candidate
                    row = i
                    vertical = true
                    angle = m.startDeg(i)
                }
            }
            val sinEnd = sin(Math.toRadians(m.endDeg(i)))
            if (sinEnd > 1e-9) {
                val candidate = ((limitY / sinEnd).toFloat() - m.offset[i]) / a
                if (candidate < cap) {
                    cap = candidate
                    row = i
                    vertical = false
                    angle = m.endDeg(i)
                }
            }
        }
        return FanCap(cap, row, vertical, angle)
    }

    /**
     * 逐项核验「整圆在屏内」，返回**最坏的越界像素数**（0 = 全部在内）。
     *
     * 判据逐项、四条边，用的就是 [FanSpec.itemX] / [FanSpec.itemY] 那套屏幕坐标 ——
     * 所以它给出的结论就是屏幕上真实的结论，跟 [effectiveRadiusCap] /
     * [effectiveRadiusFloor] 那两套闭式解**是另一套算法**（两条独立的推导同时写错在
     * 同一个地方的概率远低于一条写错）。
     *
     * 为什么要它：上限/下限都是拿「某个半径下的角度」算的，而角度又依赖半径
     * （止角 ∝ 层半径之比）—— 那是个不动点。`spec()` 只迭代有限趟，这里补一道核验，
     * 有偏差就报进几何日志的 `fit=` 里（正常时恒为 0）。
     */
    fun screenOverflow(
        m: FanMetrics,
        screenW: Float,
        screenH: Float,
        edge: Float,
        keepOut: Float,
    ): Float {
        var worst = 0f
        for (i in 0 until m.count) {
            val row = m.plan.rows[i]
            for (slot in 0 until max(1, row.count)) {
                val radians = Math.toRadians(
                    if (row.count <= 1) FanArcs.SINGLE_SLOT_DEG else m.angleDeg(i, slot)
                )
                val lx = (m.rowRadius[i] * cos(radians)).toFloat()
                val ly = (m.rowRadius[i] * sin(radians)).toFloat()
                // 贴右角：x = W − edge − lx、贴左角：x = edge + lx（两个角都要满足，取更坏的那个）
                worst = max(worst, keepOut - (edge + lx))
                worst = max(worst, (edge + lx) - (screenW - keepOut))
                worst = max(worst, keepOut - (screenH - edge - ly))
                worst = max(worst, (screenH - edge - ly) - (screenH - keepOut))
            }
        }
        return worst
    }

    /**
     * 「整圆在屏内」的**下限**：半径 R 至少多大，才能让**最靠近弧心那一列项**也让开屏幕边。
     *
     * 与 [effectiveRadiusCap] 是同一个判据的另一个方向。为什么必须有它：
     * 弧心离屏边只有 [edge]（出厂 10dp ≈ 32.5px），而圆心至少要离屏边 [keepOut]
     * （出厂 ≈ 80px，图标越大越大）——`edge < keepOut` 时，**止角项本来就在屏幕外**，
     * 只有把半径撑到它让开为止。本机默认参数下 `keepOut − edge = 47px`，
     * 而 24 项的半径是 1097px，止角项的横向偏移 = `ρ_0·cos82° = 104px > 47` ✓ 天然满足；
     * 但 `icon_size_dp = 96` 时 `keepOut = 215px`，同一项只剩 39px —— 整圆**超出右边缘 143px**。
     * （这是 mock 复算抓出来的，不是理论担心。）
     *
     * ```
     * 每层： lx 最小的是**止角项**（cosθ 单调降）→ ρ·cos(止角) ≥ keepOut − edge
     *        ly 最小的是**起角项**（sinθ 单调升）→ ρ·sin(起角) ≥ keepOut − edge
     * 下限 = 所有层 × 两个轴里最大的那个（取 max，因为这是「至少要多大」）
     * ```
     *
     * `keepOut ≤ edge` 时（留距足够大）直接返回 0：没有任何一项会被这一侧裁到。
     */
    fun effectiveRadiusFloor(
        m: FanMetrics,
        screenW: Float,
        screenH: Float,
        edge: Float,
        keepOut: Float,
    ): Float {
        val need = keepOut - edge
        if (need <= 0f) return 0f
        var floor = 0f
        for (i in 0 until m.count) {
            val a = m.scale[i]
            if (a <= 0f) continue
            val cosEnd = cos(Math.toRadians(m.endDeg(i)))
            if (cosEnd > 1e-9) {
                floor = max(floor, ((need / cosEnd).toFloat() - m.offset[i]) / a)
            }
            val sinStart = sin(Math.toRadians(m.startDeg(i)))
            if (sinStart > 1e-9) {
                floor = max(floor, ((need / sinStart).toFloat() - m.offset[i]) / a)
            }
        }
        return floor
    }

    /**
     * 「半径」与「图标直径」的**第二半**：半径既定之后，问图标还能多大。
     * 逐字对应 `GestureGeometry.effectiveIconDiameter`（`:580-601`），单趟、无迭代：
     *
     * ```
     * available = configuredDiameter                                  // 起点是配置值
     * 对每一层：
     *     centerDistance = 该层半径 × 2·sin(步进/2)                    // 该层相邻项的实际中心距
     *     available = min(available, centerDistance − gap)             // 放得下多大就多大
     * return max(minimumDiameter, available)                          // 下限 28dp
     * ```
     *
     * ## 四个要点
     *
     *  1. **图标直径是全局一个值，不是每层一个**。上游只算一次
     *     （`FanRuntime.java:1126-1127`、`FanOverlayView.configure(...)` 只有一个
     *     `this.iconDiameter` 字段，`onDraw` 里所有层共用它，`:400,416`）。
     *     我们的实现同样是「遍历所有层取 min」，也就是**最挤的那层替所有层做决定**。
     *     （这是有意的：层层不同大小会让扇子看起来像失焦。）
     *  2. **传进来的 [m] 必须是最终那一份 metrics**：半径已经被上限/下限两个方向定完、
     *     角度也按同一个半径算完。它只读半径，不会反过来再撑一次半径
     *     —— 顺序反了会得出「图标放不下」的假结论。
     *  3. **下限 28dp 现在真的会触发**（旧版本触发不了）。旧版里半径被
     *     [effectiveRadius] 顶到「放得下配置图标 + 间隙」为止，于是
     *     `centerDistance − gap ≥ configuredDiameter` 恒成立，函数永远返回配置值。
     *     有了 [effectiveRadiusCap] 之后半径可能被**压到「放不下配置图标」**，
     *     这时图标**接力缩小** —— 这正是降级链的第二级：半径让位给屏内、图标让位给半径。
     *  4. **[MIN_ICON_DP]（28dp）是最后一道护栏**，不是目标值：它拦的是「图标小到看不见」。
     *     真触到底（项数太多 + 屏太小）时**允许相邻项挤/轻微重叠** ——
     *     「看得见但挤」远好过「看不见」，因为看不见的项对用户等于「我选的应用丢了」。
     */
    fun effectiveIconDiameter(
        m: FanMetrics,
        configuredDiameter: Float,
        minDiameter: Float,
        gap: Float,
    ): Float {
        var available = configuredDiameter
        for (i in 0 until m.count) {
            val step = m.stepRadians(i)
            if (step <= 0.0) continue
            val centerDistance = (m.rowRadius[i] * 2.0 * sin(step / 2.0)).toFloat()
            available = min(available, centerDistance - gap)
        }
        return max(minDiameter, available)
    }
}

/**
 * 屏内上限的一次求值结果。
 *
 * 必须报出「是**哪一项**把半径压下来的」，否则「上限生效了」这句话没法复核。
 *
 * @param radius 上限（px）；[Float.MAX_VALUE] = 没有任何一项约束它（理论上不会出现）
 * @param rowIndex 最紧的那一层在 [FanPlan.rows] 里的下标；-1 = 没有
 * @param vertical true = 约束来自 **x 轴**（贴侧边的**起角**项），
 *   false = 来自 **y 轴**（贴顶边的**止角**项）
 * @param angleDeg 那一项的极角（度）—— 与 [FanMetrics] 里的角度同源，可独立复算
 */
class FanCap(
    val radius: Float,
    val rowIndex: Int,
    val vertical: Boolean,
    val angleDeg: Double,
)

/**
 * 一次排版里**每层的半径与角度**：绘制、命中（间接）、日志三处共用的唯一一份几何。
 *
 * ## 为什么要有这个类（而不是把公式散在几个函数里）
 *
 * 本工程有一条纪律：**绘制与命中共用同一份几何**（顶点数组在 [FanConfig.spec] 里一次算好）。
 * 加进「屏内上限」「行距跟图标走」「端点对齐」之后，半径和角度成了**互相依赖**的两个量
 * （层半径改 → 弦长/上限变；而止角的对齐比例 = 层半径之比，也随半径变）。
 * 再把公式散着写，就一定出现「日志里的角度」和「画出来的角度」不是同一次算的 ——
 * 所以这里把「给定半径 → 每层的半径/起止角/步进」收敛成一个对象：
 * 谁要角度都从这里拿（[angleDeg]），谁要半径都从这里拿（[rowRadius]）。
 *
 * 线程：**主线程构造**（[FanConfig.spec]），构造完只读；[place] 也是主线程调的。
 * 命中测试读的是 [place] 写进的那个顶点数组（[FanSpec] 持有），不碰这个对象。
 *
 * ## 三条规则
 *
 * **① 层半径**：`ρ_i = a·R + 层序号 × 行距`
 * （[FanArcs.INNER_FACTOR] / [FanArcs.ROW_STEP_FACTOR]）。
 * 内层仍是参照的 `0.68R`，但**层距不再是半径的比例**，而是「配置图标直径 × 1.15」——
 * 参照的 `0.2R` 在本机是 268px，而图标只有 139px（行距是直径的 1.93 倍），三行看起来是
 * 三条互不相干的弧。这是**刻意偏离参照**的美学调整（用户：「行与行之间更靠近一些」）。
 *
 * **② 止角对齐（用户说的「垂直边缘对齐」）**：所有层的**侧边端点**落在同一条**竖直线**上，
 * 即 `ρ_i·cos(end_i) = ρ_0·cos(82°)`，反解 `cos(end_i) = (ρ_0/ρ_i)·cos(82°)`。
 *
 * 为什么不是「各层都张 12°–82°」：等角度时三层的端点落在同一条**射线**上，在屏幕坐标系里
 * 是一条**斜线**（本机实测三层端点 x 差 75px：1061 / 1023 / 986），而用户要的是
 * 「竖直边缘对齐」。要让 x 相同，层半径大的那层必须**跨更大的角**
 * （`ρ·cosθ` 里 ρ 大，要保持同一个 x 就得让 cosθ 更小）——
 * 所以**外层的角跨度比内层大**，不是小。
 *
 * 取舍（这是转述来的用户要求，按最字面的读数实现，两条都写下来备查）：
 *  - 选「竖直线」而不是「水平线」：原话是「几行的**垂直**边缘对齐」，取字面义；
 *  - 端点对齐之后，各层**内部**的项不再沿射线对齐（角度被拉伸了），
 *    「对齐还是错开半格」这件事改由 [FanRowStagger] 单独决定，两件事不再纠缠。
 *
 * **③ 起角**：`start_i = 12° + 错开量 × 本层步进`，且 `step_i = (end_i − 12°)/(项数 − 1 + 错开量)`
 * （联立：最后一项必须正好落在 `end_i` 上）。错开量 0 = 各层角度一致；0.5 = 错开半格。
 * 参照硬编码 3.5°（8 项时 ≈ 0.35 格）—— 既不是对齐也不是半格，用户实测说「看不出意图」。
 */
class FanMetrics(
    val plan: FanPlan,
    /** 用户口径的半径 R（px）：`selection_radius_percent` 撑完/压完之后的那个值。 */
    val radius: Float,
    /** 层与层的径向间距（px）= 配置图标直径 × [FanArcs.ROW_STEP_FACTOR]。 */
    val rowStep: Float,
    /** 错开量（单位 = **本层**的槽位步进）：0 / 0.5。见 [FanRowStagger]。 */
    val stagger: Float,
) {

    /** 层数（= [FanPlan.rows] 的长度）。 */
    val count = plan.rows.size

    /** 每层的 `a`（`ρ = a·R + b`）。见 [FanArcs.rowScale]。 */
    val scale = FloatArray(count)

    /**
     * 每层的 `b`（= **层序号** × 行距）。
     *
     * 用 `radialIndex` 而不是列表下标：自定义模式下把内排配成 0 时，后面几层的半径不该
     * 整体前移 —— 那会让用户拖「内排数量」时其它层跟着跳（参照也是这个语义：它按
     * `radialIndex` 取系数）。
     */
    val offset = FloatArray(count)

    /** 每层的实际半径（px）。**绘制、命中、日志共用这一份。** */
    val rowRadius = FloatArray(count)

    private val startDegArr = DoubleArray(count)
    private val endDegArr = DoubleArray(count)
    private val stepRad = DoubleArray(count)

    init {
        for (i in 0 until count) {
            val row = plan.rows[i]
            scale[i] = FanArcs.rowScale(plan, row)
            offset[i] = row.radialIndex * rowStep
            rowRadius[i] = scale[i] * radius + offset[i]
        }
        // 止角的对齐比例取**基准层**（= 内层，plan.rows[0]）的半径：
        // 所有多槽层的端点 x 都等于它（见类注释 ②），所以这里只需要一个基准值。
        val base = rowRadius[0]
        for (i in 0 until count) {
            val row = plan.rows[i]
            if (row.count <= 1) {
                // 单槽层不参与对齐：一项不成弧，「端点」就是它自己 → 放扇子正中 47°（参照 `:139`）
                startDegArr[i] = FanArcs.SINGLE_SLOT_DEG
                endDegArr[i] = FanArcs.SINGLE_SLOT_DEG
                stepRad[i] = 0.0
                continue
            }
            val ratio = if (rowRadius[i] > 0f) (base / rowRadius[i]).toDouble() else 1.0
            // 反解出来的角度恒在 (0°, 90°) 内：ratio ∈ (0, 1]，而 cos82° = 0.139、sin12° = 0.208
            // 都严格在 (0,1) 里，所以 acos 的自变量不会越界（coerceIn 只是防浮点噪声）。
            endDegArr[i] = Math.toDegrees(
                acos((ratio * cos(Math.toRadians(FanArcs.END_DEG))).coerceIn(-1.0, 1.0))
            )
            val slots = if (FanArcs.staggered(plan, row)) stagger.toDouble() else 0.0
            val step = (endDegArr[i] - FanArcs.START_DEG) / (row.count - 1 + slots)
            startDegArr[i] = FanArcs.START_DEG + slots * step
            stepRad[i] = Math.toRadians(step)
        }
    }

    /** 第 [i] 层的起角（度）。 */
    fun startDeg(i: Int): Double = startDegArr[i]

    /** 第 [i] 层的止角（度）。 */
    fun endDeg(i: Int): Double = endDegArr[i]

    /** 第 [i] 层相邻两项的角步进（弧度）。单槽层 = 0（没有相邻项，不构成间距约束）。 */
    fun stepRadians(i: Int): Double = stepRad[i]

    /** 第 [i] 层第 [slot] 项的角度（度）。 */
    fun angleDeg(i: Int, slot: Int): Double {
        val n = plan.rows[i].count
        if (n <= 1) return FanArcs.SINGLE_SLOT_DEG
        val s = slot.coerceIn(0, n - 1)
        return startDegArr[i] + (endDegArr[i] - startDegArr[i]) * s / (n - 1)
    }

    /**
     * 第 [index] 项**相对弧心**的偏移（本地坐标：`x` 朝屏内为正、`y` 朝上为正），写进 [out]。
     *
     * ```
     * local.x = rowRadius · cos(radians)      local.y = rowRadius · sin(radians)
     * ```
     *
     * 之所以存**偏移**而不是屏幕坐标：弧心不在屏幕角上（[FanEdgeMargin] 把它往屏内推了
     * 一段），屏幕坐标 = 弧心 + 偏移，由 [FanSpec.itemX] / [FanSpec.itemY] **一处**加上去。
     * 这样「留距」只需要改弧心一个地方，形状/命中/绘制的相对关系一个字节都不动。
     *
     * 贴右角时 [FanSpec.itemX] 把本地 `x` 镜像过去（`W − margin − x`）。
     * 结果直接写进 [out]（`out[index*2]` / `out[index*2+1]`）—— 调用点在主线程，
     * 但同一条路径也被命中测试**读**，所以这里保持「不分配」的形状（只做乘加比较）。
     */
    fun place(index: Int, out: FloatArray) {
        var i = 0
        var start = 0
        // 找 index 落在哪一层：逐层累加，层数 ≤ 3，代价恒定
        while (i < count - 1 && index >= start + plan.rows[i].count) {
            start += plan.rows[i].count
            i++
        }
        val radians = Math.toRadians(angleDeg(i, max(0, index - start)))
        out[index * 2] = (rowRadius[i] * cos(radians)).toFloat()
        out[index * 2 + 1] = (rowRadius[i] * sin(radians)).toFloat()
    }

    /**
     * 所有层的侧边端点共用的那条**竖直线**距弧心的横向偏移（px）—— 规则 ② 的 `ρ_0·cos(82°)`。
     *
     * 屏幕 x = `W − edge − endLine`（贴右）/ `edge + endLine`（贴左），见 [FanSpec.itemX]。
     * 日志里报它，读的人可以拿每一层的 `半径 × cos(止角)` 去核：所有多槽层的值必须相等。
     */
    val endLine: Float get() = (rowRadius[0] * cos(Math.toRadians(FanArcs.END_DEG))).toFloat()

    /**
     * 日志：逐层 `项数@半径:起角-止角`，末尾跟三个全局量。
     *
     * 例：`8@709:12.0-82.0|8@836:16.4-84.1|8@962:12.0-85.2 step=127 stg=0.50 vline=98`
     *
     *  - 报的是**层的实际半径**（不是参照的系数）：现在的层半径是 `0.68R + 层序号 × 行距`，
     *    系数那套已经没有意义 —— 报系数会让复算的人按 0.68 算出一个跟日志对不上的半径
     *    （这个坑上一版踩过一次）；
     *  - `step` = 层距（px）、`stg` = 错开量（格）、`vline` = 端点竖直线（距弧心 px）。
     */
    fun describe(): String {
        val sb = StringBuilder(96)
        for (i in 0 until count) {
            if (i > 0) sb.append('|')
            sb.append(plan.rows[i].count)
                .append('@').append(rowRadius[i].toInt())
                .append(':').append(FanArcs.fmt(startDegArr[i]))
                .append('-').append(FanArcs.fmt(endDegArr[i]))
        }
        sb.append(" step=").append(rowStep.toInt())
        sb.append(" stg=").append(FanArcs.fmt(stagger.toDouble()))
        sb.append(" vline=").append(endLine.toInt())
        return sb.toString()
    }
}
