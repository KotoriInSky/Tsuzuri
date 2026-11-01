package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.geometry.FanArcs
import io.github.kotoriinsky.tsuzuri.core.geometry.FanCap
import io.github.kotoriinsky.tsuzuri.core.geometry.FanHotZone
import io.github.kotoriinsky.tsuzuri.core.geometry.FanMetrics
import io.github.kotoriinsky.tsuzuri.core.geometry.FanPlan
import io.github.kotoriinsky.tsuzuri.core.geometry.FanRow
import io.github.kotoriinsky.tsuzuri.core.geometry.FanRowAllocation
import io.github.kotoriinsky.tsuzuri.core.geometry.FanSolve
import io.github.kotoriinsky.tsuzuri.core.geometry.FanSolveInput
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShape
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapeParams
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapes
import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import kotlin.math.max
import kotlin.math.min

/**
 * 底角扇形菜单的**参数读取与表面**：一次手势期间不变的快照。
 *
 * ## ⚠️ 纯几何已经搬到契约层
 *
 * 分层 / 角度 / 半径修正那半（`FanRow` / `FanPlan` / `FanRowAllocation` / `FanArcs` /
 * `FanCap` / `FanMetrics`）现在在 `core/.../geometry/FanGeometry.kt`，
 * 半径与图标直径的不动点求解在 `core/.../geometry/FanSolve.kt`。
 * 搬出去的理由是**设置界面的预览要和真机共用同一份公式**（`ui` 不许依赖 `hook`），
 * 顺带让那些公式能脱离设备跑单测。这里留下的是**必须读配置**的那部分：
 * [FanEdgeMargin] / [FanPref] / [FanIconAlpha] / [FanRowStagger] / [FanIconShape]，
 * 以及冻结成 [FanSpec] 的那一层表面（命中、日志、屏幕坐标）。
 * 搬的时候几何**一个字节都没改**，只有 `internal` 变成了 `public`（跨模块可见）。
 *
 * 下面是原文件里那份**模型出处**的说明（对 [FanArcs] / [FanMetrics] 同样适用，故保留在此）。
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
 * ## **不再抄的四条**（都是用户实测后要求偏离参照的，逐条都有实测依据）
 *
 * | 参照行为 | 我们的做法 | 为什么（用户原话 + 实测） |
 * | --- | --- | --- |
 * | 层半径阶梯 `0.68 / 0.88 / 1.08`（= 0.2R 一档） | 内层仍 `0.68R`，**层距改成「图标直径 × 1.15」** | 0.2R 在本机是 268px，而图标直径只有 139px —— 行距是直径的 **1.93 倍**，三行看起来是三条互不相干的弧（用户：「行与行之间更靠近一些」）。见 [FanArcs.ROW_STEP_FACTOR] |
 * | 中层角度硬编码内缩 `3.5°` | 错开量 = **本层步进的 0 / 0.5 倍**（`fan_row_stagger`） | 8 项、70° 时半格 = 5°，而 3.5° 既不是对齐也不是半格 —— 用户实测说「看不出意图」（「对齐或者错开半格」）。见 [FanRowStagger] |
 * | 各层都张 `12°–82°`（端点落在同一条**射线**上） | 各层止角按「端点落在同一条**竖直线**」反解 | 用户原话「几行的垂直边缘对齐」。等角度时三层的端点在屏幕上 x 相差 75px（1061/1023/986），不是对齐。见 [FanMetrics] 的规则 ② |
 * | 半径只有「不重叠」的**下限**（[FanArcs.effectiveRadius]） | 再加一道「整圆在屏内」的**上限**（[FanArcs.effectiveRadiusCap]） | `fan_max_targets = 24` 时半径被撑到 1338px，外层 `1.08 × 1338` 在 12° 上 x = **−243px** —— 外层 3 项整项在屏外（用户：「保留每个图标都可见」；看不见的项 = 等于用户选的应用丢了） |
 *
 * 上一版这里是 **Flyme** 的模型（单层 90° 扇 + 极角环带命中，几何委托给
 * `:core` 的 `FanLayout`）。Flyme 那套在真机上手感不对（用户实测结论），已整体作废；
 * `:core/.../gesture/FanLayout.kt` **留着不动**（它有自己的单测），但本目录不再引用它。
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
 * 不是「0° = 从角落正上」：那样两条轴相反，会把扇子转到屏幕外。
 *
 * ## 弧心（**偏离参照的一处**：弧心不在屏幕角上）
 *
 * 参照实现（HyperGesture）与魅族实测都把弧心钉在屏幕角：贴左 `(0, H)`、贴右 `(W, H)`
 * （`FanOverlayView.java:366-367`，`originX = corner == LEFT ? 0 : getWidth()`、
 * `drawOriginY = getHeight()`）。**我们额外往屏内推了一段** [FanEdgeMargin]
 * —— 原因是用户反馈「贴边的项被裁」：弧心在角上时最靠侧边的那一列（82°）有一半在屏外。
 * 这是**有意的偏离**，不是抄错；参数与推导见 [FanEdgeMargin]。
 *
 * 于是弧心 = 贴左 `(edge, H − edge)`、贴右 `(W − edge, H − edge)`，
 * 顶点存的是**相对弧心的偏移**（[FanMetrics.place]），屏幕坐标由 [FanSpec.itemX] /
 * [FanSpec.itemY] 一处加上去 —— 绘制与命中都走这两个函数，所以留距改了不会错位。
 *
 * ## 线程纪律
 *
 * [FanSpec] 由主线程构造一次，之后**只被输入分发线程读**（[FanSpec.hitTest]），所以：
 * 字段全 `val`、顶点数组预先算好（输入线程上不允许分配）、命中只做乘加比较。
 */
internal enum class FanCorner { LEFT, RIGHT }

/**
 * `corner_small_window.fan_edge_margin`：**弧心离屏幕边缘的留距**（dp）。
 *
 * ## 这是本工程自己加的，不是抄参照
 *
 * HyperGesture 的 `ConfigContract` 里**没有**这一项：它只有 `honeycomb_edge_scale`、
 * `side_task_finger_offset_dp`、`side_top_safe_margin_percent` 三个边距类参数，
 * 没有一个属于底角扇。它的弧心钉死在屏幕角上 ——
 * `FanOverlayView.java:366-367` 的 `originX = corner == LEFT ? 0 : getWidth()`、
 * `drawOriginY = getHeight()`；魅族真机的实测也没有这个量。
 *
 * 加它的原因只有一条：**用户反馈「贴边的项被裁」**。弧心在角上时，最靠近屏幕侧边的
 * 那一列（角度 82° 的内层项）有一半身子在屏外。所以这是我们**主动偏离**参照的一处，
 * 不是对齐项。
 *
 * ⚠️ **加进 [FanArcs.effectiveRadiusCap] 之后，这个参数的角色变了**（2026-09 补注）：
 * 「贴边的项不被裁」现在是**上限那条约束保证的**（数学上保证整圆在屏内，跟图标多大、
 * 项数多少都无关）。所以这个留距不再是唯一防线，而是
 *
 *  1. **视觉留白**：弧心不贴屏幕角，扇子整体离边缘有一段距离，看起来不那么"糊在角上"；
 *  2. **兜住贴底边那一侧**：上限只管「离侧边/顶边太近」，而离**底边**太近是个方向相反的
 *     下限约束（推导见 [FanArcs.effectiveRadiusCap] 的第二条 ⚠️），那条没接进代码，
 *     这个留距是它唯一的人为余量。
 *
 * ## 默认值 10dp 是怎么推出来的（不是拍的）
 *
 * 离屏幕边缘最近的永远是**内层、角度最极端**的那一项：偏移是 `ρ·cosθ` / `ρ·sinθ`，
 * 而 ρ 在内层最小（0.68R）、`cosθ` 在 θ = 82° 最小、`sinθ` 在 θ = 12° 最小 ——
 * 两个极端角同时是内层的最紧处。要求**连命中圆（也是选中描环）都留在屏内**
 * （描环中心在 `d/2 + 6dp`、线宽 3dp，外缘还要再出 1.5dp；下面按 6dp 算，
 * 那 1.5dp 由 10dp 的余量吸收）：
 *
 * ```
 * margin ≥ (d/2 + 6dp) − 0.68R·cos(82°)     // 贴左/贴右的那一侧，更紧
 * margin ≥ (d/2 + 6dp) − 0.68R·sin(12°)     // 贴下的那一侧（sin12° > cos82°，更松）
 * ```
 *
 * 代入本机默认值（d = 34dp = 110px、6dp = 20px；17 项 → 内层 5 项 → 步进 17.5°，
 * R 被 [FanArcs.effectiveRadius] 撑到 628px）：
 * `110/2 + 20 − 0.68 × 628 × cos82° = 75 − 59.4 = 15.6px ≈ 4.8dp`。
 * 这个下限随图标变大而变大：到 `icon_size_dp` 的合法上限 96dp 时是 **24px ≈ 7.4dp**。
 * 所以默认取 **10dp** —— 盖住整个合法参数区间的「不裁」下限，再留约 2.6dp 的视觉余量。
 *
 * ⚠️ 它**不等于**「图标半径」（≈17dp）：图标半径是「整个圆盘都在屏内」的充分条件，
 * 但最靠边的那一项不在 0°/90° 上（而是 12°/82°），`cos82° = 0.139` 让它天然离侧边更远；
 * 硬按图标半径留距，会把扇子无谓地往屏内缩掉一大截、还挤小有效显示区。
 *
 * ## 读到就用，读不到退默认值
 *
 * 键名用 [Keys.pref] 拼（不手写字面量），**声明还在 `core/Catalog.kt` 那边排队**
 * （另一个协作者在改那个文件，这里不动它）。没声明 ≠ 不能跑：没声明就用 [DEFAULT_DP]，
 * 并把这件事写进几何日志（`(未声明,用默认值)`）—— 静默用默认值是最难查的一类偏差。
 *
 * 这个「缺项就用默认值 + 把缺项报出来」的规矩抄的是 `SideIndex` 里 `LookReader` 的做法
 * （`:800-840`），只是它那边是私有实例方法、拿不到，所以这里走 [HookRegistry.prefs]
 * 那个**进程级只读入口**（它的注释里写明了同一个理由：调用点签名被协作者固定住了）。
 *
 * ⚠️ **Catalog 的 default 就是最终默认值**：如果那边声明成 `default = 0f`，
 * 用户没动过滑杆时读到的就是 0（= 与参照一样钉在角上），本文件的 [DEFAULT_DP] 只在
 * **整个键没声明**时生效。声明时请写 `default = 10f, min = 0f, max = 80f, unit = "dp"`。
 */
internal object FanEdgeMargin {

    /** Catalog 里的参数名（特性前缀由 [Keys.pref] 补）。 */
    const val NAME = "fan_edge_margin"

    /** 键没声明时的默认值，推导见类注释。 */
    const val DEFAULT_DP = 10f

    /** 一次解析的结果。`declared = false` 表示 Catalog 里还没这个键（日志要标出来）。 */
    class Resolved(val dp: Float, val declared: Boolean)

    /**
     * 读一次。**主线程**（[FanConfig.spec] 的调用点）。
     *
     * 每次发布几何都重读：用户拖滑杆时 `CornerSmallWindow.watchPrefs` 会对
     * `corner_small_window.` 前缀下的任何键触发「重读配置 + 重发几何」，
     * 所以这个值天然是**立刻生效**的，不需要额外的监听。
     */
    fun resolve(): Resolved {
        val v = FanPref.slider(NAME, DEFAULT_DP)
        return Resolved(v.value, v.declared)
    }
}

/**
 * 读一个 **Catalog 里声明的**参数（Slider / Choice），读不到/没声明就把「没声明」带回去。
 *
 * 三处需要同一件事（[FanEdgeMargin] / [FanRowStagger] / [FanIconAlpha]），所以收在这里。
 * 规矩抄 `SideIndex` 里 `LookReader` 的做法（`:800-840`）：
 *
 *  - 键**没声明** ≠ 不能跑：用调用方给的默认值，并把 `declared = false` 带回去，
 *    由几何日志打上 `(未声明,用默认值)` —— 静默用默认值是最难查的一类偏差；
 *  - 声明了就按 Catalog 的 min/max 夹取（范围只有一个事实来源，不另抄一份常量）；
 *  - **任何异常都退默认值**：几何算不出来 = 整个手势不工作，代价远大于少一个参数。
 *
 * 走 [HookRegistry.prefs] 那个**进程级只读入口**，而不是让调用点把 Prefs 传进来：
 * `FanConfig` 的构造点在 `CornerSmallWindow`，那个文件的签名由另一个协作者固定住了
 * （与 `HookRegistry.prefs` 自己的注释同一个理由）。
 */
internal object FanPref {

    /** 一次读取的结果。`declared = false` = Catalog 里还没这个键（日志必须标出来）。 */
    class SliderValue(val value: Float, val declared: Boolean)

    /** 同上。[choice] 的返回类型。 */
    class ChoiceValue(val value: String, val declared: Boolean)

    /** 同上。[toggle] 的返回类型。 */
    class ToggleValue(val value: Boolean, val declared: Boolean)

    fun slider(name: String, default: Float): SliderValue {
        val key = Keys.pref(Catalog.CORNER_SMALL_WINDOW, name)
        return try {
            val spec = Catalog.find(Catalog.CORNER_SMALL_WINDOW)
                ?.prefs
                ?.filterIsInstance<PrefSpec.Slider>()
                ?.firstOrNull { it.key == key }
                ?: return SliderValue(default, declared = false)
            SliderValue(HookRegistry.prefs().value(spec).coerceIn(spec.min, spec.max), declared = true)
        } catch (t: Throwable) {
            SliderValue(default, declared = false)
        }
    }

    /**
     * 读一个 Toggle。**布尔没有「非法值」**（存储里只可能是 true/false），
     * 所以这里没有 choice 那样的归一化：读不到键就用调用方给的默认值。
     */
    fun toggle(name: String, default: Boolean): ToggleValue {
        val key = Keys.pref(Catalog.CORNER_SMALL_WINDOW, name)
        return try {
            val spec = Catalog.find(Catalog.CORNER_SMALL_WINDOW)
                ?.prefs
                ?.filterIsInstance<PrefSpec.Toggle>()
                ?.firstOrNull { it.key == key }
                ?: return ToggleValue(default, declared = false)
            ToggleValue(HookRegistry.prefs().value(spec), declared = true)
        } catch (t: Throwable) {
            ToggleValue(default, declared = false)
        }
    }

    /**
     * 读一个 Choice，并**只接受 [allowed] 里的取值**：没见过的字符串不猜，回 [default]。
     *
     * 为什么要归一化：pref 是跨进程存下来的，旧版本写过别的值、或者用户手改过存储，
     * 都可能让这里读到第三种字符串 —— 那种值没有任何语义，回默认值才是安全的。
     */
    fun choice(name: String, default: String, allowed: Set<String>): ChoiceValue {
        val key = Keys.pref(Catalog.CORNER_SMALL_WINDOW, name)
        return try {
            val spec = Catalog.find(Catalog.CORNER_SMALL_WINDOW)
                ?.prefs
                ?.filterIsInstance<PrefSpec.Choice>()
                ?.firstOrNull { it.key == key }
                ?: return ChoiceValue(default, declared = false)
            val raw = HookRegistry.prefs().value(spec)
            ChoiceValue(if (raw in allowed) raw else default, declared = true)
        } catch (t: Throwable) {
            ChoiceValue(default, declared = false)
        }
    }
}

/**
 * `corner_small_window.icon_alpha`：**图标本体的不透明度百分比**。
 *
 * ## 作用范围（2026-09-19 收窄过一次，别改回去）
 *
 * 乘的是**画图标位图**那把画笔（[FanOverlay] 的 `iconPaint`）与占位圆的 `placeholderPaint`，
 * **不碰**：
 *  - **选中描环**（`ringPaint`）：环跟着变透明的话，选中的那一项反而最看不清 —— 正好弄反了。
 *    选中反馈的意图就是「一眼看出选的是哪个」，它必须保持不透明；
 *  - **「⋯」翻页按钮**（`morePaint`）：它是控件不是应用图标；
 *  - **细白边**（`iconEdgePaint`）：**这条是 2026-09-19 才改成不跟的**。
 *
 * 上一版细边烙在图标位图里，于是天然跟着这个 alpha 一起淡，注释当时把这写成「正确的耦合」。
 * 后果是一条用户实测现象：**`icon_alpha` 拉到 100% 时图标依然有点透明** ——
 * 因为 `ICON_EDGE_COLOR` 自带 53% 的 alpha，而它是图标边界唯一的视觉来源。
 * 也就是说这个参数**唯一能调的那一层（位图）在 100% 时本就没有可调空间**，
 * 而用户看着发灰的那一层它管不到。现在两者解耦，`icon_alpha` 终于名副其实。
 * 完整论证与参照实现证据见 [FanOverlay] 的 `iconEdgePaint`。
 *
 * ## 下限 20% 而不是 0
 *
 * Catalog 声明成 `min = 20`：0 等于把图标藏起来，那不是「调节」而是「关掉功能」，
 * 想关掉应该关特性开关（手感类参数都要「改完立刻生效」，
 * 但「生效成一个看不见的东西」不是这条规矩想要的）。
 */
internal object FanIconAlpha {

    /** Catalog 里的参数名（特性前缀由 [Keys.pref] 补）。 */
    const val NAME = "icon_alpha"

    /** 键没声明时的默认值：100%（不透明，与加这个参数之前的渲染完全一致）。 */
    const val DEFAULT_PCT = 100f

    /** 一次解析的结果：`alpha ∈ [0.2, 1]`。 */
    class Resolved(val alpha: Float, val percent: Float, val declared: Boolean)

    fun resolve(): Resolved {
        val v = FanPref.slider(NAME, DEFAULT_PCT)
        // 百分比 → 倍率。再夹一次 [0,1]：Catalog 声明坏了（比如 max = 200）也不会让画笔崩
        val pct = v.value.coerceIn(0f, 100f)
        return Resolved((pct / 100f).coerceIn(0f, 1f), pct, v.declared)
    }
}

/**
 * `corner_small_window.icon_shape`：**图标的形状**（圆形 / 圆角方形 / 超椭圆 / 水滴 / 不裁）。
 *
 * ## 这一层「套在哪」
 *
 * 图标从「取到」到「画出来」总共只有两层，形状是**外面那一层**：
 *
 * ```
 * ① 取谁的图标（回退链，一个字都没改）
 *      单项覆盖 → 单项图标包 → 整体图标包（含 iconback/mask/upon 合成）→ 自带图标
 * ② 裁成什么形状（这里）
 *      [FanOverlay.circularIcon] 把 ① 的结果按 [IconShape] 的轮廓裁一遍
 * ```
 *
 * ## 为什么裁剪落在**绘制层**而不是取图标那条链上
 *
 * 取图标那条链（`FanTargets.Pool.appIcon` / `shortcutIcon`）产出的是**原始 drawable**，
 * 它可以被图标包的 `iconback/mask/upon` 再合成一次；而屏幕上真正决定「圆 / 圆角方」的
 * 一直是 `FanOverlay.circularIcon` 里那个硬编码的 `addCircle` —— 也就是说
 * **今天所有扇形图标都是被它裁成圆的**。形状只要不落在这里，就会被那个圆再裁一次，
 * 圆角方形的四个角会当场消失（「改了没反应」）。
 *
 * 好处顺带有两条：
 *
 *  1. **回退链谁赢完全不受影响**（那条链一行都没动）—— 形状只作用在**最终结果**上；
 *  2. 一次性做成位图（在 `show()` 里、展开之前），`onDraw` 里除了 `drawBitmap` 什么都不做
 *     （规范 §7.4 对动画帧的要求）。
 *
 * ## 取不到就退回**现状**（圆形），绝不画空
 *
 * 键没声明（Catalog 还没接线）/ 读到第三种字符串 / 解析或裁剪抛异常 → 一律回 [DEFAULT]
 * = 圆形 = **加这个参数之前的渲染**。所以这个参数在接线之前是**完全惰性**的：
 * 打开模块与不打开模块看到的像素一致。
 *
 * ## 声明方式（本文件不声明：`core/Catalog.kt` 由另一个协作者在改）
 *
 * ```
 * PrefSpec.Choice(
 *     key = p(CORNER_SMALL_WINDOW, "icon_shape"),
 *     labelKey = "pref.corner.icon_shape",
 *     default = "circle",                       // = 现状，接线时别改成别的
 *     options = listOf(
 *         PrefSpec.Choice.Option("circle", "pref.corner.icon_shape.circle"),
 *         PrefSpec.Choice.Option("rounded_square", "pref.corner.icon_shape.rounded_square"),
 *         PrefSpec.Choice.Option("squircle", "pref.corner.icon_shape.squircle"),
 *         PrefSpec.Choice.Option("teardrop", "pref.corner.icon_shape.teardrop"),
 *         PrefSpec.Choice.Option("original", "pref.corner.icon_shape.original"),
 *     ),
 * )
 * ```
 *
 * ⚠️ **Catalog 的 `default` 才是「用户没动过」时的值**：那边写 `circle` 就是现状；
 * 本文件的 [DEFAULT] 只在**整个键没声明**时生效。
 */
internal object FanIconShape {

    /** Catalog 里的参数名（特性前缀由 [Keys.pref] 补）。 */
    const val NAME = "icon_shape"

    /** 形状的**可调数值**：圆角占比（%）与柔度（超椭圆指数）。`0` = 用形状自己的默认值。 */
    const val CORNER_NAME = "icon_shape_corner"
    const val EXPONENT_NAME = "icon_shape_exponent"

    /** 键没声明时的默认值 = **加这个参数之前的行为**（[IconShape.CIRCLE]）。 */
    val DEFAULT: IconShape = IconShape.CIRCLE

    /**
     * 一次解析的结果。
     *
     * @param shape 形状枚举（跨进程存的字符串已经归一化过）
     * @param declared `icon_shape` 在 Catalog 里声明了吗
     * @param params **解析后的形状参数**（`0` 已经解析成形状自己的默认值）。
     *   裁剪链只认它 —— 这样 hook 与预览（[FanPreview.iconShapeParams]）拿到的是
     *   同一份解析结果，两边不会各解析一遍。
     * @param cornerDeclared / exponentDeclared 两个数值键声明了吗（日志要标出来）
     * @param clampNote 非空 = 用户数值被夹取 / 对当前形状无效（见 [IconShapeParams.clampNote]）；
     *   由几何日志打**一次**（[FanSpec.describe]）
     */
    class Resolved(
        val shape: IconShape,
        val declared: Boolean,
        val params: IconShapeParams = IconShapes.paramsOf(shape),
        val cornerDeclared: Boolean = false,
        val exponentDeclared: Boolean = false,
        val clampNote: String = "",
    )

    /**
     * 读一次。**主线程**（[FanConfig.spec] 的调用点）。
     *
     * 取值归一化走 [FanPref.choice]：不认识的字符串**不猜**，回 [DEFAULT] ——
     * 猜错方向的代价是「用户选了方角，屏幕上是圆」（或反过来），而回默认值的失败方向
     * 是「与没加这个参数时一模一样」，是安全的那个方向。
     *
     * 两个**数值**参数（圆角 / 柔度）的解析（`0` = 形状默认值、越界夹取）**不在 hook 里**，
     * 而是调契约层的 [IconShapes.resolveParams] —— 预览那边调的是同一个函数，
     * 「0 → 逐像素一致」这条回归防线才有单一实现。
     */
    fun resolve(): Resolved {
        val v = FanPref.choice(NAME, DEFAULT.value, IconShape.values().map { it.value }.toSet())
        val shape = IconShape.of(v.value)
        // 读不到 / 没声明 → 传 `0`（= 用形状自己的默认值），所以老配置的行为不变
        val corner = FanPref.slider(CORNER_NAME, IconShapes.UNSET)
        val exponent = FanPref.slider(EXPONENT_NAME, IconShapes.UNSET)
        val params = IconShapes.resolveParams(
            shape = shape,
            cornerPercent = corner.value,
            exponent = exponent.value,
        )
        return Resolved(
            shape = shape,
            declared = v.declared,
            params = params,
            cornerDeclared = corner.declared,
            exponentDeclared = exponent.declared,
            clampNote = params.clampNote,
        )
    }
}

/**
 * `corner_small_window.icon_edge` / `icon_edge_width`：**图标那一圈细白描边**。
 *
 * ## 为什么要有这两个键（用户实测反馈）
 *
 * 描边原来是**硬编码**的：`ICON_EDGE_STROKE_DP = 1f`（线宽）且**被当成 px 用**
 * （`ICON_EDGE_DP = 0.5f`）。于是：
 *
 *  - 宽细不可调 —— 想细一点只能改代码；
 *  - **单位错了**：`0.5f` 当 px 意味着同一份配置在 3.25 密度的机器上是 0.5px、
 *    在低密度机器上却是 2dp 粗 —— 「同一个参数、两种粗细」；
 *  - 不能关。深色图标压在深色壁纸上时它是唯一的边界，但用户也可能就是不要它。
 *
 * 现在：`icon_edge`（开着/关掉）+ `icon_edge_width`（**dp**，缺省 0.5）——
 * 线宽 = `dp × density`（换算在 [FanSpec.iconEdgeWidthPx] 一处做，
 * 绘制链与预览 [FanPreview.iconEdgeWidthPx] 用同一个数）。
 *
 * ## ⚠️ 这条边**不跟** `icon_alpha`（2026-09-19 解耦，别改回去）
 *
 * 上一版细边**烙在图标位图里面**：`FanOverlay.circularIcon` 把 drawable 与描边画进
 * 同一张 `ARGB_8888`，之后 `FanView.drawSlot` 用 `iconPaint`（alpha = `icon_alpha`）
 * 画这张位图 —— 描边与图标共用同一个 alpha。当时的注释把这写成「耦合的正确实现」，
 * 还专门警告「不要为了让它跟着淡再给 `iconEdgePaint` 设一次 alpha，那会变成 alpha²」。
 *
 * **那个前提已经作废**：细边现在**不再烙进位图**，改由 `drawSlot` 用
 * `iconEdgePaint` 单独描一笔，因此**恒为 [ICON_EDGE_COLOR] 的 53%**，
 * 与 `icon_alpha` 完全无关。改这条的理由（用户实测「`icon_alpha` 拉到 100%
 * 依然有点透明」+ 53% 是参照实现的设计值）写在 `FanOverlay.iconEdgePaint` 上。
 *
 * 所以那条 `alpha²` 的警告也一起失效了 —— 现在这里就算设 alpha 也不会是「平方」，
 * 但那依然不是我们要的：想让边跟着淡是**另一个参数**，不是把耦合加回来。
 */
internal object FanIconEdge {

    /** Catalog 里的参数名（特性前缀由 [Keys.pref] 补）。 */
    const val NAME = "icon_edge"
    const val WIDTH_NAME = "icon_edge_width"

    /** 键没声明时的默认值：**不画**，与 Catalog 一致（2026-10-29 用户要求去掉白边）。 */
    const val DEFAULT_ENABLED = false

    /**
     * 键没声明时的默认线宽（dp）= 0.5。
     *
     * ⚠️ **与上一版有意的差别**：上一版写死的是 `1dp` 线宽（`ICON_EDGE_STROKE_DP = 1f`），
     * 而 `Catalog` 把 `icon_edge_width` 的 default 声明成 `0.5`。这次按声明走
     * （用户实测反馈「边框太扎眼」，细一半更接近他想要的），
     * 代价是**没配过的人看到的边比上一版细一半** —— 这是一次有意的外观调整，不是 bug。
     */
    const val DEFAULT_WIDTH_DP = 0.5f

    /** 一次解析的结果。 */
    class Resolved(
        val enabled: Boolean,
        /** 线宽（dp，已按 Catalog 的 min/max 夹取）。 */
        val widthDp: Float,
        val enabledDeclared: Boolean,
        val widthDeclared: Boolean,
    )

    fun resolve(): Resolved {
        val on = FanPref.toggle(NAME, DEFAULT_ENABLED)
        val w = FanPref.slider(WIDTH_NAME, DEFAULT_WIDTH_DP)
        return Resolved(
            // 开关关着就是「完全不画」；线宽为 0 也等于不画，但两者**分开报**
            // （日志里 `edge=off` 与 `edge=0.0px` 是不同的原因，不该混成一个状态）
            enabled = on.value,
            widthDp = if (w.value.isFinite()) w.value.coerceAtLeast(0f) else DEFAULT_WIDTH_DP,
            enabledDeclared = on.declared,
            widthDeclared = w.declared,
        )
    }
}

/**
 * `corner_small_window.fan_row_stagger`：**相邻层之间的角度错开量**（单位 = 本层槽位步进）。
 *
 * ## 为什么要有这个键（参照里没有）
 *
 * 参照实现把「中层角度内缩」硬编码成 `3.5°`（`GestureGeometry.iconCenter:134` 的 `inset`）。
 * 而 8 项摊在 70° 上时**单槽步进 = 10°** —— 3.5° 既不是「对齐」（0°）、
 * 也不是「错开半格」（5°），是一个看不出意图的数（用户实测原话：「对齐或者错开半格」）。
 *
 * 所以把它做成**比例量**（格数），项数一变步进就变、错位跟着变：
 *
 * | 取值 | 效果 | 8 项时 |
 * | --- | --- | --- |
 * | `aligned`（0 格） | 各层角度**一致**，同一列落在同一条射线上 | 0° |
 * | `half`（0.5 格） | 各层错开**半格**，砖砌式（参照想要的形态） | 5° |
 *
 * 只有 `radialIndex` 为奇数的层（三层时 = 中层）会错开，其余层对齐 —— 这条奇偶规则
 * 抄参照的 `(radialIndex & 1) != 1 ? 0 : inset`，三层时就是「对齐 / 半格 / 对齐」。
 *
 * 错开与「垂直边缘对齐」（[FanMetrics] 规则 ②）**不冲突**：端点仍然对齐在同一条
 * 竖直线上，错开只体现在**层内部**的项之间（起角 = `12° + 错开量 × 本层步进`，
 * 止角仍由对齐规则定）。也就是说「四个角对齐、中间砖砌」。
 *
 * ## 建议的 Catalog 声明（本文件不声明：`core/Catalog.kt` 由另一个协作者在改）
 *
 * ```
 * PrefSpec.Choice(
 *     key = p(CORNER_SMALL_WINDOW, "fan_row_stagger"),
 *     labelKey = "pref.corner.row_stagger",
 *     hintKey = "pref.corner.row_stagger.hint",
 *     default = "half",
 *     options = listOf(
 *         PrefSpec.Choice.Option("aligned", "pref.corner.row_stagger.aligned"),
 *         PrefSpec.Choice.Option("half", "pref.corner.row_stagger.half"),
 *     ),
 * )
 * ```
 *
 * ⚠️ **Catalog 的 `default` 才是「用户没动过」时的值**：那边写 `default = "aligned"` 就是
 * 出厂对齐；本文件的 [DEFAULT] 只在**整个键没声明**时生效。
 *
 * ## 读到就用，读不到退默认值
 *
 * 与 [FanEdgeMargin] 完全同一条规矩（理由也一样）：没声明 ≠ 不能跑，用 [DEFAULT]
 * 并把 `(未声明,用默认值)` 打进几何日志 —— 静默用默认值是最难查的一类偏差。
 * 那条规矩抄的是 `SideIndex` 里 `LookReader` 的做法（`:800-840`）。
 */
internal object FanRowStagger {

    /** Catalog 里的参数名（特性前缀由 [Keys.pref] 补）。 */
    const val NAME = "fan_row_stagger"

    /** 两个取值，与 Catalog 的 `Choice.Option` 值一一对应。 */
    const val ALIGNED = "aligned"
    const val HALF = "half"

    /** 键没声明时的默认值：错开半格（参照的**意图**就是砖砌，只是它那个数不成比例）。 */
    const val DEFAULT = HALF

    /** `half` 折成格数。 */
    const val HALF_SLOTS = 0.5f

    /** 一次解析的结果。`declared = false` 表示 Catalog 里还没这个键（日志要标出来）。 */
    class Resolved(val slots: Float, val value: String, val declared: Boolean)

    /**
     * 读一次。**主线程**（[FanConfig.spec] 的调用点）。
     *
     * 每次发布几何都重读，所以改完立刻生效 —— 与 [FanEdgeMargin] 同一条链路
     * （`CornerSmallWindow.watchPrefs` 对 `corner_small_window.` 前缀下的任何键都会
     * 触发「重读配置 + 重发几何」）。
     */
    fun resolve(): Resolved {
        val v = FanPref.choice(NAME, DEFAULT, setOf(ALIGNED, HALF))
        return Resolved(slotsOf(v.value), v.value, declared = v.declared)
    }

    private fun slotsOf(value: String): Float = if (value == ALIGNED) 0f else HALF_SLOTS
}

/**
 * 扇形几何的表面：顶点、命中、以及给日志用的一句话。
 *
 * [centers] 在构造时算好（**主线程**），输入线程只读。
 *
 * 里面的每一个数都是**四条约束**（[FanArcs.effectiveRadius] 不重叠、
 * [FanArcs.effectiveRadiusCap] 屏内上限、[FanArcs.effectiveRadiusFloor] 屏内下限、
 * [FanArcs.effectiveIconDiameter] 图标直径）在同一个不动点上解出来的结果，
 * 顺序与取舍写在 [FanConfig.spec] 里（**屏内 > 不重叠 > 图标大小**）。
 */
internal class FanSpec(
    val screenW: Float,
    val screenH: Float,
    val density: Float,
    /**
     * 触发区宽 = `hot_width_percent%` × **竖屏屏宽**（短边）。
     *
     * ⚠️ 基准不是当下的屏宽（2026-09-20 起）：按当下轴求值时转屏会让热区变形
     * （本机横屏变成 147×21dp，纵向只剩 21dp）。求值唯一来源是 core 的 [FanHotZone]，
     * 预览与真机共用它 —— 别在这里手写 `screen * percent`。
     */
    val hotWidth: Float,
    /** 触发区高 = `hot_height_percent%` × **竖屏屏高**（长边），横竖屏同一块热区。 */
    val hotHeight: Float,
    /**
     * 接管门槛：**总位移**（px）—— 由常量 [FanTrigger.TAKEOVER_SLOP_DP] 换算。见那里的说明。
     *
     * 为什么是常量不是参数（2026-09-15 用户拍板「不要靠加参数解决」）：这个值是我们**内部的
     * 判定时机**，参照实现（Flyme 的 `bottom_gesture_touch_slop = 2dp`）也只是个内部 dimen，
     * 从不暴露给用户。做成滑杆只会让用户去调一个他无从判断的量。
     */
    val takeoverSlopPx: Float,
    /** 朝内分量的**噪声地板**（px）—— 由 [FanTrigger.TAKEOVER_MIN_INWARD_DP] 换算。 */
    val takeoverMinInwardPx: Float,
    val allowLeft: Boolean,
    val allowRight: Boolean,
    /** 一级阈值：判方向要走的距离，`max(touchSlop, 10dp)`（`FanRuntime.java:402`）。 */
    val decisionPx: Float,
    /** 二级阈值：距离闸 = `trigger_percent%` × 屏幕短边（`FanRuntime.java:1121`）。 */
    val triggerPx: Float,
    /** 半径初值：短边 × `selection_radius_percent%`（`FanRuntime.java:2059`）。 */
    val configuredRadius: Float,
    /**
     * 实际半径：`min(不重叠下限, 屏内上限)`，见 [FanArcs.effectiveRadius] /
     * [FanArcs.effectiveRadiusCap] 与 [FanConfig.spec] 的降级顺序。
     */
    val radius: Float,
    /** 「不重叠」要求的最小半径（[FanArcs.effectiveRadius]，不含用户初值）。日志 `req=`。 */
    val requiredRadius: Float,
    /** 「屏内·靠近弧心那一侧」要求的最小半径（[FanArcs.effectiveRadiusFloor]）。日志 `flr=`。 */
    val floorRadius: Float,
    /** 「屏内·远离弧心那一侧」允许的最大半径（[FanArcs.effectiveRadiusCap]）。日志 `cap=`。 */
    val capRadius: Float,
    /**
     * 上限是**哪一项**定的，日志用（`[1185·cos12.0°]` 这种可复算的写法）。
     *
     * 必须报出来：只说「上限生效了」没法复核 —— 复算的人得知道是**哪一层的哪个轴**
     * 顶到了屏幕边。空串 = 没有任何一项约束（理论上不会出现）。
     */
    val capReason: String,
    /** 降级链走到哪一级（屏内 > 不重叠 > 图标大小）。日志 `fit=`。 */
    val fitDesc: String,
    /** [FanRowStagger] 是否在 Catalog 里声明（false = 用的默认值，日志必须标出来）。 */
    val staggerDeclared: Boolean,
    /**
     * 图标本体的不透明度倍率（`icon_alpha%` / 100）。
     * **只作用于图标位图与占位圆**；细白边与选中描环都不跟（[FanIconAlpha] 有完整清单）。
     *
     * 为什么走 [FanSpec] 而不是让 [FanOverlay] 直接读 pref：浮层是**每次手势新建**的，
     * 而几何/配置是每次「清单或配置变化」时发布一次的 —— 两者共用同一份快照才能保证
     * 「浮层画出来的」和「输入线程命中的」是同一个时刻的配置（与其它参数同一条纪律）。
     */
    val iconAlpha: Float,
    /** [FanIconAlpha] 是否在 Catalog 里声明（false = 用的默认值，日志必须标出来）。 */
    val iconAlphaDeclared: Boolean,
    /**
     * 图标的**形状**（`icon_shape`）。见 [FanIconShape] / [IconShape]。
     *
     * 它只被绘制层用一次（[FanOverlay.circularIcon] 拿它替代原来写死的圆），
     * 但**必须随几何一起发布**：[FanIconAlpha] 的注释里那条纪律同样适用 ——
     * 浮层是每次手势新建的，而配置是每次「清单或配置变化」时发布一次的，
     * 两者共用同一份快照才能保证「拖完滑杆立刻生效」且展开那一帧不读 Binder。
     */
    val iconShape: IconShape,
    /** [FanIconShape] 是否在 Catalog 里声明（false = 用的默认值，日志必须标出来）。 */
    val iconShapeDeclared: Boolean,
    /**
     * **解析后的**形状参数（`icon_shape_corner` / `icon_shape_exponent` 里的 `0`
     * 已经解析成该形状自己的默认值，见 [IconShapes.resolveParams]）。
     *
     * 裁剪链（[IconShapeMask]）只认这一份：它同时是设置界面预览用的那份
     * （[FanPreview.iconShapeParams]），所以「预览是圆角方、真机是圆」这种偏差不可能出现。
     */
    val iconShapeParams: IconShapeParams,
    /** 两个形状数值键是否在 Catalog 里声明（false = 用的默认值，日志必须标出来）。 */
    val iconShapeCornerDeclared: Boolean,
    val iconShapeExponentDeclared: Boolean,
    /**
     * 形状数值被夹取 / 对当前形状无效时的一句人话（`""` = 原样采纳）。
     * 进 [describe]，也就是**每次发布几何打一次** —— 「拖到底没反应」的唯一线索。
     */
    val iconShapeClampNote: String,
    /** `icon_edge`：图标那一圈细白描边画不画（[FanIconEdge]）。 */
    val iconEdgeEnabled: Boolean,
    /** 描边**最终线宽（px）** = `icon_edge_width(dp) × density`。见 [FanIconEdge]。 */
    val iconEdgeWidthPx: Float,
    /** 两个描边键是否在 Catalog 里声明（false = 用的默认值，日志必须标出来）。 */
    val iconEdgeDeclared: Boolean,
    val iconEdgeWidthDeclared: Boolean,
    /** 实际图标直径（[FanArcs.effectiveIconDiameter] 修正后）。 */
    val iconDiameter: Float,
    /**
     * 弧心离屏幕边缘的**留距**（px）。见 [FanEdgeMargin]。
     *
     * 弧心 = 贴左 `(edgeMargin, screenH − edgeMargin)`、贴右
     * `(screenW − edgeMargin, screenH − edgeMargin)` —— **两个轴都推**，不是只推一个。
     */
    val edgeMargin: Float,
    /** 同一个量的 dp 原值（日志用；px 除以 density 会有小数误差）。 */
    val edgeMarginDp: Float,
    /** Catalog 里是否声明了这个键（false = 用的 [FanEdgeMargin.DEFAULT_DP]，日志必须标出来）。 */
    val edgeMarginDeclared: Boolean,
    /** 命中圆半径 = 图标半径 + 6dp。 */
    val hitRadius: Float,
    /**
     * 每一项**相对弧心**的偏移：`[x0,y0,x1,y1,...]`，`x` 朝屏内为正、`y` 朝上为正。
     *
     * 屏幕坐标由 [itemX] / [itemY] 加上弧心得到（贴右时镜像）。绘制与命中共用这一份 ——
     * 一旦各算各的，就会出现「看到的图标」和「碰到的图标」对不上。
     */
    private val centers: FloatArray,
    /**
     * 层的形状摘要，日志用（主线程拼好，输入线程不碰）。
     *
     * 格式：逐层 `项数@层半径:起角-止角`，末尾跟 `step=`（层距 px）、`stg=`（错开量，格）、
     * `vline=`（所有层端点共用的那条竖直线距弧心多少 px）。见 [FanMetrics.describe]。
     */
    val arcs: String,
    /**
     * 三个百分比的**原值**（宽/高/距离闸），日志用。
     *
     * 为什么必须打原值：`hot=` 打的是像素，而 pref 里存的是小数百分比
     * （Slider 的值带小数，比如 15.135%），只打 `hot=402` 会让复算的人
     * 按「1500 的 15%」算出 398 而以为差了一个 bug。
     */
    val percents: String,
    /** 展开幅度百分比：图标从圆心走多远（`FanOverlayView.java:380`）。 */
    val revealPercent: Float,
    /** 展开期每项的自转幅度（度），方向随贴左/贴右取反。 */
    val rotationDegrees: Float,
    /** 动效速度百分比。**是除数不是毫秒**：`duration = max(45, base * 100 / speed)`。 */
    val animationSpeed: Float,
    /** 选中项放大倍率。 */
    val selectionScale: Float,
    val selectionRing: Boolean,
    /**
     * 「选中项变化」时振不振（`haptic`）。对应 HyperGesture 的全局 `haptic`
     * （`FanRuntime.java:1830-1834` 的边沿触发）。
     */
    val haptic: Boolean,
    /**
     * 「扇形刚弹出」那一下振不振（`trigger_haptic`）。
     *
     * 与 [haptic] **分开**是 HyperGesture 的设计：
     * `BottomTriggerHapticPolicy.shouldVibrate(globalHaptic, bottomTriggerHaptic) =
     * globalHaptic && bottomTriggerHaptic`（`BottomTriggerHapticPolicy.java:8-10`）——
     * 呼出提示可以单独关掉，而选择反馈仍受全局开关管。我们这边两个键各管一段。
     */
    val triggerHaptic: Boolean,
    /**
     * 当前方向是否允许响应。HyperGesture 是
     * `bottomEnabledFor(landscape) = landscape ? bottomLandscapeEnabled : bottomPortraitEnabled`
     * （`GestureConfig.java:455-457`）。
     *
     * 判定放在**探测器里**而不是 `publish` 里：屏幕转向时我们不会重算几何
     * （清单/配置没变），而按下是每次手势都发生的，读两个布尔 + 一次比较的代价可以忽略。
     * 那两处唯一的区别是「转屏后要不要立刻生效」——这里能立刻生效。
     */
    val portraitEnabled: Boolean,
    val landscapeEnabled: Boolean,
    /** 排版摘要（模式 + 实际生效的三排数量），日志用。 */
    val planDesc: String,
) {

    /** 槽位数 = 层分配用的项数。绘制、命中、几何三者共用它，绝不允许各算各的。 */
    val count: Int get() = centers.size / 2

    // 输入线程上做距离判定，用平方比较省掉一次 sqrt（上游那边用的是 hypot）
    val decisionPx2: Float get() = decisionPx * decisionPx
    val triggerPx2: Float get() = triggerPx * triggerPx

    /**
     * **弧心**的屏幕 x（贴左是 `(edgeMargin, ·)`、贴右是 `(W − edgeMargin, ·)`）。
     *
     * 注意它**不是屏幕角**：[edgeMargin] 把它沿法线往屏内推了一段（见 [FanEdgeMargin]）。
     * 它同时是展开动画的起点（[FanOverlay] 的 `f` 插值），所以绘制那边不用改 ——
     * 弧心一动，起飞点和每个顶点一起动，相对关系不变。
     *
     * 触发区（[FanTrigger.cornerAt]）**不跟着挪**：那是手指按下的热区，就在屏幕角上，
     * 与扇子画在哪无关。
     */
    fun cornerX(openToRight: Boolean): Float =
        if (openToRight) screenW - edgeMargin else edgeMargin

    /** 弧心的屏幕 y（贴左贴右都是 `H − edgeMargin`，两个轴都推）。 */
    val cornerY: Float get() = screenH - edgeMargin

    /**
     * 当前朝向是否横屏 —— **全工程只有这一处判据**。
     *
     * 三处必须读同一个值，各写一份就会静默漂移（这个项目在别处吃过这个亏）：
     *
     *  1. `onDown` 的**守卫 4**（当前方向启用了没有）；
     *  2. 守卫 5：黑名单选竖屏那份还是横屏那份；
     *  3. 小窗摆放（[WindowBounds.isLandscape]，与之同一个函数）。
     *
     * 判据就是「宽 > 高」，与 HyperGesture 的 `isLandscape()` 一致
     * （`FanRuntime.java:2919-2923`，比的是 display bounds 的长宽）。
     *
     * ⚠️ 前提是 [screenW] / [screenH] 是**当下**的尺寸 —— 转屏后必须重算几何
     * （`CornerSmallWindow.watchRotation`），否则这里读到的是上一次发布时的朝向。
     */
    fun isLandscape(): Boolean = WindowBounds.isLandscape(screenW, screenH)

    /**
     * 每一项所在的层（0 = 内层），按离弧心的距离分组：距离差不到半个图标直径的算同一层。
     * 展开动画按层错峰用（见 `FanView.revealRotation`），只在主线程画帧时读。
     */
    val rings: IntArray by lazy {
        val n = centers.size / 2
        val dist = FloatArray(n) { kotlin.math.hypot(centers[it * 2], centers[it * 2 + 1]) }
        val order = (0 until n).sortedBy { dist[it] }
        val out = IntArray(n)
        var ring = 0
        var last = Float.NaN
        for (i in order) {
            if (!last.isNaN() && dist[i] - last > iconDiameter / 2f) ring++
            out[i] = ring
            last = dist[i]
        }
        out
    }

    /** 层数（至少 1）。 */
    val ringCount: Int get() = (rings.maxOrNull() ?: 0) + 1

    /** 第 [index] 项中心的屏幕 x：弧心 + 本地偏移（贴右时镜像）。 */
    fun itemX(index: Int, openToRight: Boolean): Float {
        val local = centers[index * 2]
        return if (openToRight) screenW - edgeMargin - local else edgeMargin + local
    }

    /** 第 [index] 项中心的屏幕 y：弧心 + 本地偏移（y 不镜像，两层共用）。 */
    fun itemY(index: Int): Float = screenH - edgeMargin - centers[index * 2 + 1]

    /**
     * 展开期的插值系数：`startFactor + (1 - startFactor) × reveal`，
     * 其中 `startFactor = 1 - reveal_amount%`（`FanOverlayView.java:380,391`）。
     *
     * `reveal_amount = 65` → 起点在「离圆心 35%」处，也就是图标从圆心附近长出来。
     */
    fun revealFactor(reveal: Float): Float {
        val start = 1f - revealPercent / 100f
        return start + (1f - start) * reveal
    }

    /**
     * 手指命中了哪一项。**绘制与命中共用同一个顶点数组** —— 一旦各算各的，
     * 就会出现「看到的图标」和「碰到的图标」对不上。
     *
     * 判据逐字来自 `GestureGeometry.FanLayout.selection`（`:55-66`）：
     * 遍历所有项，**取第一个**落在 `hitRadius` 圆里的（不是最近的那个）。
     * 这正是 HyperGesture 的手感 —— 相邻圆有 6dp 重叠带（见 [FanArcs.effectiveRadius]），
     * 重叠带里由「前一个」赢，顺序由层序决定，用户感知不到抖动。
     *
     * ⚠️ 两处细节，改任何一处都要两边一起改：
     *  - 坐标一律走 [itemX] / [itemY]（**不是**直接读 [centers]）—— 弧心带着边缘留距，
     *    绕过它去读本地偏移就是「看到的和碰到的差一个偏移量」；
     *  - 这里**没有**「半径落在 [内沿, 外沿] 里」那种环带判定，命中就是逐个图标的圆。
     *    所以圆心附近是天然的「死区」（内层那一圈 `0.68R` 以内都选不中），
     *    滑过头也一样没得选 —— 与参照 `GestureGeometry.FanLayout.selection` 完全一致。
     *  - ⚠️ 层距改成「图标直径 × 1.15」之后**相邻两层的命中圆会相交**（以前 0.2R 的层距
     *    比图标直径大得多）。这是可接受的：相交带里由**层序在前**的那个赢，是确定性的；
     *    而且不只是命中圆 —— 图标圆本身也留了 5dp 的视觉间隙（见 [FanArcs.ROW_STEP_FACTOR]），
     *    所以「手指正好落在两行之间」时选中内层是符合直觉的。
     *
     * @return 项下标；-1 = 未命中（调用方按「取消」处理）
     */
    fun hitTest(x: Float, y: Float, openToRight: Boolean): Int {
        val r2 = hitRadius * hitRadius
        val limit = count
        var i = 0
        while (i < limit) {
            val dx = x - itemX(i, openToRight)
            val dy = y - itemY(i)
            if (dx * dx + dy * dy <= r2) return i
            i++
        }
        return -1
    }

    /**
     * 给日志的一句话。
     *
     * 字段刻意选成「**可以拿计算器独立复算几何**」的：`hot` 是触发区像素尺寸、
     * `pct` 是三个百分比的**原值**、`gate` 是距离闸、`r` 是初值→实际半径、
     * `icon`/`hit` 是图标直径与命中圆半径、`plan` 是排版模式与**夹取后真正生效**的三排数量。
     *
     * 半径那条链**四个数都要报**（只有两个数就没法判断是谁让的位）：
     *
     * ```
     * r=366->1097        初值 -> 实际 = clamp(max(初值, req, flr), cap)
     * req=1097           「不重叠」要求的最小半径（FanArcs.effectiveRadius）
     * flr=626            「屏内·靠近弧心那一侧」要求的最小半径（effectiveRadiusFloor）
     * cap=1274[1000·cos12.0°]  「屏内·远离弧心那一侧」允许的最大半径
     *                    + 是哪一项顶的（那一层的半径·哪个轴·多少度）
     * fit=...            降级链走到哪一级：ok / 压半径 / 缩图标 / 触底 / !核验
     * ```
     *
     * 正常（本机 24 项）那行长这样：
     *
     * ```
     * r=366->1097 req=1097 flr=626 cap=1274[1000·cos12.0°] fit=ok icon=110 ...
     * arcs=8@746:12.0-82.0|8@873:16.7-83.2|8@1000:12.0-84.0 step=127 stg=0.50 vline=104
     * ```
     *
     * 拿计算器复核三件事：`0.68 × 1097 = 746`（基准层半径）、
     * `746 × cos82° = 104 = vline`（三层端点同一条竖直线）、
     * `0.68 × 1097 + 2 × 127 = 1000`（外层半径 = 基准层 + 2 个层距）。
     *
     * `arcs` 是每层的「项数@**实际半径**:起角-止角」，末尾跟 `step=`（层距 px）、
     * `stg=`（错开量，格）、`vline=`（所有层端点共用的那条竖直线距弧心多少 px）。
     * `vline` 是「垂直边缘对齐」那条要求的**可复核证据**：拿每一层的
     * `半径 × cos(止角)` 去算，三个值必须都等于 `vline`。
     *
     * `edge` / `center` 是为了让**弧心**也能被独立复算（[FanEdgeMargin] 是后加的，
     * 不在这行里就没法判断「贴边的项为什么还是被裁」）：`edge=` 是 dp 原值 + 算出来的 px，
     * `center=` 是**贴左/贴右两个弧心**的屏幕坐标（先左后右，贴右 = `W − edge`）。
     * 键没声明时会多一个 `(未声明,用默认值)` 标记 —— 那是接线漏了的唯一可见证据
     * （`edge` 与 `stg` 各有一处）。
     *
     * 图标外观那三项也在这行里（都是「改了没反应」的高危区）：
     *
     * ```
     * alpha=100%         icon_alpha（作用于图标位图与占位圆；**细白边不跟**）
     * shape=circle       形状枚举
     * shapeVal=45.0%/4.0 **最终生效**的圆角（%）与柔度；后面跟 `!…` 表示用户数值被夹取
     *                    或对当前形状无效（`0` = 没配过 → 形状自己的默认值）
     * edgeStroke=1.6px   细描边的最终线宽（**px**，已按 density 从 dp 换算）；`off` = 关着
     * ```
     *
     * `shapeVal` 与 `edgeStroke` 之所以必须打：这两个键都有「拖了没反应」的失败模式
     * （越界被夹、对当前形状无效、单位当成 px），只有把**生效值**摆出来才能一眼分辨。
     */
    fun describe(): String =
        "hot=" + hotWidth.toInt() + "x" + hotHeight.toInt() +
            " pct=" + percents +
            " gate=" + triggerPx.toInt() +
            " edge=" + oneDecimal(edgeMarginDp) + "dp(" + oneDecimal(edgeMargin) + "px)" +
            (if (edgeMarginDeclared) "" else "(未声明,用默认值)") +
            " center=(" + cornerX(false).toInt() + "," + cornerY.toInt() +
            ")/(" + cornerX(true).toInt() + "," + cornerY.toInt() + ")" +
            " r=" + configuredRadius.toInt() + "->" + radius.toInt() +
            // 不再有 `yield=` —— 让出底部条带那条规则已整体删除（理由见 [FanTrigger.cornerAt]）。
            " takeover=" + oneDecimal(takeoverSlopPx / density) + "dp(" + takeoverSlopPx.toInt() + "px)" +
            "+in" + oneDecimal(takeoverMinInwardPx / density) + "dp" +
            " req=" + requiredRadius.toInt() +
            " flr=" + floorRadius.toInt() +
            " cap=" + capRadius.toInt() + "[" + capReason + "]" +
            " " + fitDesc +
            " icon=" + iconDiameter.toInt() +
            " hit=" + oneDecimal(hitRadius) +
            (if (staggerDeclared) "" else "(stg未声明,用默认值)") +
            " alpha=" + (iconAlpha * 100f).toInt() + "%" +
            (if (iconAlphaDeclared) "" else "(未声明,用默认值)") +
            " shape=" + iconShape.value +
            (if (iconShapeDeclared) "" else "(未声明,用默认值)") +
            // 形状的**可调数值**：只报「最终生效值」不够 —— 必须能看出「用户要的值 vs 生效值」
            // 的差别（越界被夹 / 对当前形状无效时后面跟一条 `!` 提示）
            " shapeVal=" + oneDecimal(iconShapeParams.cornerFraction * 100f) + "%/" +
            oneDecimal(iconShapeParams.exponent) +
            (if (iconShapeCornerDeclared && iconShapeExponentDeclared) "" else "(部分未声明,用默认值)") +
            (if (iconShapeClampNote.isEmpty()) "" else " !" + iconShapeClampNote) +
            // 描边：**线宽是 px**（dp→px 已在 spec 里换算好），关掉写 off ——
            // 「边框没变细」这类问题一眼能看出是「关着」还是「宽度就是这个数」。
            // ⚠️ 字段名不能叫 `edge=`：上面那个 `edge=` 是**弧心留距**（[FanEdgeMargin]），
            // 两个字面量撞在一起会让日志没法一眼读（两个完全不同的量）
            " edgeStroke=" + (if (iconEdgeEnabled) oneDecimal(iconEdgeWidthPx) + "px" else "off") +
            (if (iconEdgeDeclared && iconEdgeWidthDeclared) "" else "(部分未声明,用默认值)") +
            " rot=" + oneDecimal(rotationDegrees) +
            " " + planDesc +
            " arcs=" + arcs

    private fun oneDecimal(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    companion object {
        /** 方向判定距离的下限 10dp（`FanRuntime.java:402` 的 `density * 10`）。 */
        internal const val DECISION_MIN_DP = 10f
    }
}

/**
 * 扇形的配置输入（只跟 prefs 有关，与屏幕/应用数无关）。
 *
 * 拆出来是因为**几何跟项数有关**（层数、每层几个、半径修正都由项数推），
 * 而应用清单是异步扫出来的：清单变了要重算几何，但配置不用重读。
 * 主线程构造一次，之后每次发布清单时调 [spec]。
 */
internal class FanConfig(
    val hotWidthPercent: Float,
    val hotHeightPercent: Float,
    val triggerPercent: Float,
    val corners: String,
    /** `fan_rotation`：展开期每项的自转幅度（度），HyperGesture 的 `fan_rotation_degrees`。 */
    val rotationDeg: Float,
    val maxTargets: Int,
    val animationSpeed: Float,
    val selectionScalePercent: Float,
    val selectionRing: Boolean,
    /** `haptic`：「选中项变化」振动。 */
    val haptic: Boolean,
    /** `trigger_haptic`：「扇形刚弹出」振动。与 [haptic] 分开（`BottomTriggerHapticPolicy.java:8-10`）。 */
    val triggerHaptic: Boolean,
    /** `portrait_enabled` / `landscape_enabled`：分方向启用（`GestureConfig.java:455-457`）。 */
    val portraitEnabled: Boolean,
    val landscapeEnabled: Boolean,
    /**
     * `fan_layout_mode`：`smart`（默认）或 `custom`。见 [FanArcs.plan] 的两个分支。
     */
    val layoutMode: String,
    /**
     * `fan_custom_inner_count` / `fan_custom_middle_count`：自定义模式下内排、中排的容量。
     *
     * **只配两个**。HyperGesture 的 `fan_custom_outer_count` 在加载路径里根本没被读
     * （`GestureConfig.java:333-338` 只读 inner/middle 然后调 `FanRowAllocation.normalize`），
     * 外排恒等于「项数 − 内排 − 中排」。我们不暴露它，否则就是一个拖了没反应的滑杆。
     */
    val customInner: Int,
    val customMiddle: Int,
    /**
     * `selection_radius_percent`：半径 = 屏幕短边 × 它%。
     *
     * 出厂 30（`ConfigContract.java:124`），可调范围 1–150。**Flyme 实测是 69** ——
     * 两者差一倍多，所以它必须可调：出厂值偏紧凑，舒展程度只能靠手指判断。
     *
     * ⚠️ 它是**初值**：项数一多，[FanArcs.effectiveRadius] 会把它顶到「最挤那层不重叠」为止。
     */
    val radiusPercent: Float,
    /**
     * `icon_size_dp`：图标基准边长（dp）。出厂 34（`ConfigContract.java:78`，同时也是它的下限）。
     *
     * ⚠️ 同样是**初值**：挤不下时 [FanArcs.effectiveIconDiameter] 会往下压，下限 28dp。
     */
    val iconDp: Float,
    /** `fan_reveal_amount`：展开动画走过的路程比例。范围 15–130，出厂 65（`ConfigContract.java:40`）。 */
    val revealAmount: Float,
) {

    val allowLeft: Boolean get() = corners != "bottom_right"
    val allowRight: Boolean get() = corners != "bottom_left"

    /**
     * 按当前屏幕 + 当前槽位数算一份几何。**主线程**。
     *
     * ## 「内距离 / 外距离」在这里是怎么来的（没有环带这个概念）
     *
     * HyperGesture 的命中是**逐个图标的圆**（`GestureGeometry.FanLayout.selection:55-66`），
     * **没有**「半径落在 [内沿, 外沿] 里才算选中」这种极角环带（那是上一版 Flyme 模型的
     * 做法，留在没人引用的 `core/gesture/FanLayout.kt` 里）。所以：
     *
     *  - **内沿**是隐式的：离圆心最近的命中点 = 内层那一圈半径 `0.68R` 再往里 `d/2 + 6dp`。
     *    手指比这更靠近屏幕角时**不命中任何项**（扇形的「死区」，也是「还没滑出去」的
     *    自然表现），由 [FanSpec.hitTest] 返回 -1、调用方按「取消」处理。
     *  - **外沿**同样是隐式的：最外层圆心 + `d/2 + 6dp` 之外不命中。
     *  - 两者都不是参数，都随层数 / 项数 / 半径一起动 —— 这也是为什么不能拿一个固定
     *    的「环带」去近似它：`r` 本身会被 [FanArcs.effectiveRadius] 撑大，
     *    又被 [FanArcs.effectiveRadiusCap] / [FanArcs.effectiveRadiusFloor] 夹住。
     *
     * 上游唯一一处「显式的边」是 `fanOutermostEdgeDistance` / `beyondFanEdge`
     * （`:283-296`），只被**第二级蜂窝菜单**的触发判定用（`FanRuntime.java:1763-1764`），
     * 与扇形的选中无关。
     *
     * @param count 槽位数（= 一页里画得下的项数，含「⋯」槽，见 CornerSmallWindow.publish）。
     *   半径、图标直径、层分配全都由它推出来 —— 所以它必须与浮层实际画的一致，
     *   这个值只在一个地方算出来（`publish`）然后同时喂给几何和浮层。
     */
    fun spec(
        screenW: Float,
        screenH: Float,
        density: Float,
        touchSlop: Float,
        count: Int,
    ): FanSpec {
        val slots = count.coerceAtLeast(1)

        // 弧心离屏幕边缘的留距（见 FanEdgeMargin：本工程自己加的，参照里没有）。
        // 读在**这里**而不是 readConfig 里：FanConfig 的构造点在 CornerSmallWindow，
        // 那个文件的签名由另一个协作者固定住了（与 HookRegistry.prefs 的注释同一个理由）。
        val edge = FanEdgeMargin.resolve()
        // 相邻层的错开量（0 / 0.5 格）。同样在这里读，理由同上。
        val stagger = FanRowStagger.resolve()
        // 图标不透明度（只影响图标位图的画笔，见 [FanIconAlpha]）。同样在这里读。
        val alpha = FanIconAlpha.resolve()
        // 图标形状（`icon_shape`）+ 它的两个可调数值（圆角 / 柔度）。同样在这里读：形状是
        // **绘制那一层**的事（[FanOverlay.circularIcon] 用它替代原来的硬编码圆），
        // 所以随几何一起，而不是在浮层里每帧现读配置 —— 展开期读 Binder 会拖慢第一帧
        // （见 [FanIconShape]）。三个键的解析（形状枚举 + 0→默认值 + 越界夹取）全在
        // `FanIconShape.resolve` 一处完成，绘制链只拿最终结果。
        val shape = FanIconShape.resolve()
        // 图标白描边（`icon_edge` / `icon_edge_width`）。同样随几何发布：
        // 线宽要在**这里**换算成 px（dp → px 是渲染量，绘制链与预览各换算一遍必然漂移）。
        // ⚠️ 变量名不能叫 `edge`：上面那个是「弧心离屏幕边缘的留距」，两个完全不同的东西
        val edgeStyle = FanIconEdge.resolve()

        // 半径 / 图标直径 / 层角度是**一个不动点**上的四个量（不重叠、屏内上限、屏内下限、
        // 图标直径互相让位）。求解在契约层（`core/.../geometry/FanSolve.kt`）—— 那一份实现
        // 也是**设置界面预览**用的那一份，所以预览和真机不会算出两个答案。
        // 这里只负责把配置值读出来喂给它（参数值 → [FanSolveInput] 全是值，没有 Prefs）。
        val s = FanSolve.solve(
            FanSolveInput(
                screenW = screenW,
                screenH = screenH,
                density = density,
                radiusPercent = radiusPercent,
                iconDp = iconDp,
                layoutMode = layoutMode,
                customInner = customInner,
                customMiddle = customMiddle,
                edgeMarginDp = edge.dp,
                staggerSlots = stagger.slots,
                selectionScalePercent = selectionScalePercent,
                count = slots,
            ),
        )

        val centers = FloatArray(slots * 2)
        for (i in 0 until slots) s.place(i, centers)

        return FanSpec(
            screenW = screenW,
            screenH = screenH,
            density = density,
            hotWidth = FanHotZone.widthPx(screenW, screenH, hotWidthPercent),
            hotHeight = FanHotZone.heightPx(screenW, screenH, hotHeightPercent),
            takeoverSlopPx = (FanTrigger.TAKEOVER_SLOP_DP * density).coerceAtLeast(0f),
            takeoverMinInwardPx = (FanTrigger.TAKEOVER_MIN_INWARD_DP * density).coerceAtLeast(0f),
            allowLeft = allowLeft,
            allowRight = allowRight,
            decisionPx = max(touchSlop, FanSpec.DECISION_MIN_DP * density),
            triggerPx = min(screenW, screenH) * triggerPercent / 100f,
            configuredRadius = s.configuredRadius,
            radius = s.radius,
            requiredRadius = s.requiredRadius,
            floorRadius = s.floorRadius,
            capRadius = s.capRadius,
            capReason = s.capReason,
            fitDesc = s.fitDesc,
            staggerDeclared = stagger.declared,
            iconDiameter = s.iconDiameter,
            iconShape = shape.shape,
            iconShapeDeclared = shape.declared,
            iconShapeParams = shape.params,
            iconShapeCornerDeclared = shape.cornerDeclared,
            iconShapeExponentDeclared = shape.exponentDeclared,
            iconShapeClampNote = shape.clampNote,
            iconEdgeEnabled = edgeStyle.enabled,
            iconEdgeWidthPx = edgeStyle.widthDp * density,
            iconEdgeDeclared = edgeStyle.enabledDeclared,
            iconEdgeWidthDeclared = edgeStyle.widthDeclared,
            iconAlpha = alpha.alpha,
            iconAlphaDeclared = alpha.declared,
            edgeMargin = s.edgeMarginPx,
            edgeMarginDp = edge.dp,
            edgeMarginDeclared = edge.declared,
            hitRadius = s.hitRadius,
            centers = centers,
            arcs = s.arcs,
            percents = pct(hotWidthPercent) + "x" + pct(hotHeightPercent) + "/" + pct(triggerPercent),
            revealPercent = revealAmount,
            rotationDegrees = rotationDeg.coerceIn(0f, 90f),
            animationSpeed = animationSpeed.coerceAtLeast(1f),
            selectionScale = s.selectionScale,
            selectionRing = selectionRing,
            haptic = haptic,
            triggerHaptic = triggerHaptic,
            portraitEnabled = portraitEnabled,
            landscapeEnabled = landscapeEnabled,
            planDesc = s.planDesc,
        )
    }


    companion object {
        /** 百分比打印成 1 位小数，固定 `Locale.US`。 */
        private fun pct(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
    }

}

/**
 * 触发区的判定与方向锥。全部是纯函数，跑在输入分发线程上：无 IO、无日志、无分配。
 */
internal object FanTrigger {

    /**
     * **接管门槛：总位移 ≥ 2dp** —— 逐字照 Flyme 的量法。
     *
     * Flyme 的判据是 `squaredHypot(rawX − downX, rawY − downY) > mSquaredSlop`
     * （`WindowModeGestureListener.java:123`），而 `mSquaredSlop` 来自
     * `bottom_gesture_touch_slop = **2dp**`（实测 dimen）。**它量的是总位移，不是分量。**
     *
     * ⚠️ 2026-09-15 我在这里犯过一次错：先写成"**朝内分量** ≥ 2dp"。
     * 那样起手越接近竖直、朝内攒得越慢 → 接管被推迟（实测有一次到 **45dp** 才接管），
     * 又变回"系统先到 → 回桌面"。**"照 Flyme"和"我以为照 Flyme"的差别就在这个量上。**
     */
    const val TAKEOVER_SLOP_DP = 2f

    /**
     * 朝内分量的**噪声地板**（dp）：总位移够了、但朝内还只是抖动时，先不判。
     *
     * 这是**我们自己加的**（Flyme 没有）：2dp 处判角度时，纯竖直上滑的横向抖动可能刚好压到
     * 锥内，于是把用户"想回桌面"的手势吃掉。留 1dp 的地板能把这种抖动挡掉，
     * 而 1dp 朝内在最陡的起手（83°）上也只需要总位移 ~8dp 就能满足，几乎不推迟接管。
     */
    const val TAKEOVER_MIN_INWARD_DP = 1f

    /**
     * 方向锥的两个系数 —— **Flyme 本体的仰角规则**（2026-09-15 换掉 HyperGesture 那组）。
     *
     * Flyme 判的是**仰角**（`WindowModeGestureListener.isValidAssistantGestureAngle:171-178`）：
     * ```
     * angle = atan2(upward, inward)                    // 与水平线的夹角
     * angle <= 20° || angle >= 90° → 不是扇形           // ANGLE_THRESHOLD = 20（:11）
     * ```
     * 两个端点都排他，含义正是我们要的：
     * - **≥ 90°（纯竖直）不是扇形** → 「从底角笔直往上」原样归系统（回桌面）。**这就是 Flyme
     *   把"其实想回桌面"的用户放回去的方式** —— 靠角度，而不是靠让出底部条带；
     * - **≤ 20°（近水平）也不是** → 底角近水平内滑不认领。
     *
     * 两个系数就是那两个端点的 tan：
     * - [MIN_INWARD_TO_UPWARD] = tan(90° − 83°) = **0.123** → 仰角**上界 83°**。
     *   Flyme 的上界是 90°（不可达）；留 7° 余量是为了手指抖动不会跨过 90° 被判成"纯竖直"；
     * - [MIN_UPWARD_TO_INWARD] = tan(20°) = **0.364** → 仰角**下界 20°**。
     *
     * ⚠️ 为什么换掉旧的 `0.25 / 0.08`（= HyperGesture 的 `GestureArbitrator.java:5-6`）：
     * 那一组等价于仰角 **4.6°–76°** —— 下界太宽（近水平斜滑也认领，误触方向），
     * 上界太窄（**76°–90° 的自然拇指斜滑被判死**，漏触方向）。用户 2026-09-15 报的
     * 「很难触发」正落在上界那一段。注意 Flyme 实测的那条起手轨迹
     * `(25,2620) → (270,2330)` 仰角约 **49.8°**，两组都通过 —— 所以**那条轨迹区分不出新旧**，
     * 换的依据是上面那段源代码判据，不是这条轨迹。
     *
     * 我们比 Flyme 多保留 `inward > 0 && upward > 0`（见 [directionOk]）：Flyme 的 `angle2`
     * 只比大小、不区分朝内还是朝外，那是它的私有通道带 veto 才敢这么宽。
     *
     * 副作用（与 Flyme 同形）：扇形自身最早那条弧从 12° 起（见 [FanArcs.START_DEG]），
     * 低于 20° 的部分**起手打不到**，只能先按合法角度起手、再滑过去。
     */
    const val MIN_INWARD_TO_UPWARD = 0.123f
    const val MIN_UPWARD_TO_INWARD = 0.364f

    /**
     * 按下点落在哪个角上（`GestureGeometry.cornerAt`，`:76-87`）。
     *
     * 触发区是**屏幕底部两角的矩形**：宽 = `hot_width_percent% × 竖屏屏宽`、
     * 高 = `hot_height_percent% × 竖屏屏高`（基准 2026-09-20 改为竖屏屏幕 ——
     * 横竖屏同一块热区，见 core 的 `FanHotZone`；上游是按当下屏宽/屏高）。逐字对齐上游的三行：
     * ```
     * if (y < height - hotHeight) return null;                 // 不在底部条带里
     * if (x >= leftInset && x <= leftInset + hotWidth) LEFT;   // leftInset 恒为 0
     * if (x > width - rightInset) return null;                 // 
     * if (x < width - rightInset - hotWidth) return null;      // 
     * return RIGHT;
     * ```
     * 注意**左角优先**：两角同时命中时取左角。我们的 `hot_width_percent` 上限是 100%
     * （`core/Catalog.kt:43`），真调到 100% 时整条底边都是热区，此时「左优先」就是唯一
     * 有定义的取法（上一版用「屏幕中点分左右」会在这种配置下判出与矩形不一致的角）。
     *
     * 关掉的那一角直接返回 null：`corners` 这个 pref 是「哪一角生效」。
     *
     * ## 这里**故意不让**屏幕最底部那一条（2026-09-15 更正后定稿）
     *
     * 上游那三行里的 `if (y < height - hotHeight) return null` 只保证「在底部条带里」，
     * 而系统「上滑回桌面」的起手区正是**屏幕最底部那一条**（本机实读
     * `mSwipeStartThreshold` 底边 = 78px @520dpi = **24dp**）。两者重叠的直接后果是：
     * **在应用里想划扇形，却回了桌面**（在桌面上看不出来，因为那时没有"桌面可回"）。
     *
     * 我一度在这里加过「让出底部 24dp」（照 `SlideAppsTouchController` 那条
     * `y > 屏高 − insets.bottom → 不拦截`）。那条路是错的，两个理由：
     * ① **我引的规则在手势导航下不生效** —— Flyme 那边 `insets.bottom == 0`
     *    （无导航栏时它取到的就是 0），也就是说那条判据在 Flyme 上**从不触发**，
     *    我拿一条死规则当了依據；
     * ② 实测代价过大：用户反馈「很难触发」，热区被挖掉一截。
     *
     * Flyme 本体**确实没有**这条让距：它的热区是底角 44dp 方块、**含最底边**
     * （`WindowModeGestureListener.canTriggerWindowModeAction:185`），它靠**方向锥**
     * 把"其实想回桌面"的用户放回去（仰角 ≥90° = 纯竖直 → 不是扇形），不靠让出条带。
     *
     * 让距这条参数已整体删除。与系统手势的仲裁改由两件事承担：起手后**尽早接管**
     * （[TAKEOVER_INWARD_DP]）+ 拥住这次手势时**拦下系统的回桌面转移**
     * （`CornerSmallWindow.installHomeBlock`）。
     */
    fun cornerAt(x: Float, y: Float, spec: FanSpec): FanCorner? {
        if (y < spec.screenH - spec.hotHeight) return null
        if (x <= spec.hotWidth) return if (spec.allowLeft) FanCorner.LEFT else null
        if (x < spec.screenW - spec.hotWidth) return null
        return if (spec.allowRight) FanCorner.RIGHT else null
    }

    /**
     * 方向锥（`GestureArbitrator.update`，`:18-29`）。
     *
     * @param inward 相对按下点、朝**屏内**的位移；贴左是 `x - downX`，贴右是 `downX - x`
     * @param upward 相对按下点、朝**上**的位移；屏幕 y 轴朝下，所以是 `downY - y`
     */
    fun directionOk(inward: Float, upward: Float): Boolean =
        inward > 0f && upward > 0f &&
            inward >= MIN_INWARD_TO_UPWARD * upward &&
            upward >= MIN_UPWARD_TO_INWARD * inward
}
