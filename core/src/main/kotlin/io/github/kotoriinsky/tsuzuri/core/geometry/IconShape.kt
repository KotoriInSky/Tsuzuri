package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 图标的**形状**：扇形里每一项被裁成什么轮廓。
 *
 * ## 为什么形状在契约层、裁剪却在 hook
 *
 * 契约层不许有 `android.*`（`core/build.gradle.kts` 里写着这条），而「把 drawable 裁成
 * 某个形状」必须有 `android.graphics.Canvas` / `Path`。所以：
 *
 *  - **这里**放形状的枚举与几何参数，以及一个**纯数学的轮廓采样函数**（[IconShapes.outline]）；
 *  - **hook 侧**（`FanOverlay.circularIcon`）拿采样出来的多边形去建 `Path`、裁位图。
 *
 * 把采样函数放在这里而不是让 hook 自己算，理由与扇形几何同一条：
 * **设置界面的预览要和真机画出一模一样的形状**（预览只能依赖 core），
 * 两边各写一份贝塞尔/超椭圆公式，迟早会出现「预览是圆角方、真机是圆」这种偏差。
 * 采样成多边形还顺手消掉了「同一个形状两份实现」的可能：hook 与预览都只做一件事 ——
 * 把点的坐标连起来。
 *
 * ## 采样精度
 *
 * 图标直径是 34–96dp（本机 110–300px 量级），一个 96 点的多边形在圆角/圆上肉眼已经
 * 看不出棱角（弦高 < 0.05px）。这不是「够用就行」的取舍：采样点一多，
 * `Path` 的构建成本就上去了，而它在 `show()` 里每个图标跑一次。
 */
enum class IconShape(
    /** 存进配置的字符串。**发布后不可改**（键名是跨进程契约，见 [io.github.kotoriinsky.tsuzuri.core.Keys]）。 */
    val value: String,
) {
    /** 正圆。**这是「加形状参数之前」的行为**（见 [DEFAULT]），所以它也是缺省的回退值。 */
    CIRCLE("circle"),

    /**
     * 圆角方形：四角用**圆弧**（不是连续圆角），圆角半径 = 半边长 × [IconShapes.ROUNDED_SQUARE_CORNER]。
     *
     * 与 [SQUIRCLE] 的差别是可见的：圆角方形的直边是**真直边**（与圆角的接缝处曲率突变），
     * 超椭圆的直边在中段还有极缓的弯曲。
     */
    ROUNDED_SQUARE("rounded_square"),

    /**
     * 超椭圆（squircle）：`|x/h|^n + |y/h|^n = 1`，指数 [IconShapes.SQUIRCLE_EXPONENT]。
     *
     * iOS 那种「方中带圆」的连续圆角就是这一族（数学上是 Lamé 曲线，指数约 4–5；
     * 真正的 iOS 掩码还要再过一遍贝塞尔，这里用超椭圆近似到肉眼一致）。
     */
    SQUIRCLE("squircle"),

    /**
     * 水滴：三个角是圆的（半径 = 半边长），**右下角是尖的**。
     *
     * 「尖角在右下」是唯一有定义的取法 —— 水滴必须有方向，而形状本身与扇形贴左贴右无关，
     * 所以取一个固定方向并写进注释，而不是随手势变（随手势变会让同一个图标在左右两角
     * 长得不一样，看起来像 bug）。
     */
    TEARDROP("teardrop"),

    /**
     * **不裁**：保留原图的方形边界，只把中心按 1.16 放大裁到画布内（现状的放大策略不变）。
     *
     * 它是给「我就要方图标」的用户一个显式选项，**不是缺省值**：缺省是 [DEFAULT]（正圆），
     * 因为那才是这个参数出现之前所有人看到的样子。
     */
    ORIGINAL("original"),
    ;

    companion object {

        /**
         * 读不到 / 认不出时的回退值 = [CIRCLE]。
         *
         * 方向是安全的：回到正圆就是**回到没有这个参数的行为**，
         * 绝不会把用户扔进一个「形状变成随机值」的状态。
         */
        val DEFAULT: IconShape = CIRCLE

        /**
         * 把配置里的字符串翻译成形状。`null` / 空串 / 不认识的取值 → [DEFAULT]。
         *
         * 大小写与空白都容忍（配置文件可能被手改过），但**不做模糊匹配**：
         * 认不出就回默认值，绝不猜。
         */
        fun of(raw: String?): IconShape {
            val v = raw?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.value == v } ?: DEFAULT
        }
    }
}

/**
 * 一个形状的几何参数。**全是比例量**（相对半边长），所以它与图标大小无关 ——
 * 预览用 200px 的画布、真机用 300px 的位图，算出来是同一个形状。
 */
class IconShapeParams(
    val shape: IconShape,
    /**
     * 圆角半径 ÷ 半边长。`1.0` = 正圆、`0` = 直角。
     * 被 [IconShape.ROUNDED_SQUARE] / [IconShape.TEARDROP]（三个圆角）/ [IconShape.ORIGINAL]
     * 用；[IconShape.CIRCLE] 与 [IconShape.SQUIRCLE] 的轮廓由别的参数决定，**用不到它**。
     */
    val cornerFraction: Float,
    /**
     * 超椭圆指数 `n`（`|x/h|^n + |y/h|^n = 1`）。
     * `2` = 椭圆、`4~5` = 「方中带圆」。只被 [IconShape.SQUIRCLE] 用。
     */
    val exponent: Float,
    /** 水滴的尖角是不是在**右下**角（只有 [IconShape.TEARDROP] 有意义）。 */
    val teardropPointsBottomRight: Boolean,
    /**
     * 用户数值**越界被夹取**、或者**对当前形状没有意义**时的一句人话说明；
     * `""` = 原样采纳（这是老配置/没配过时的唯一取值）。
     *
     * 为什么要把它做成一个字段（值类型里带日志串，看着不纯）：形状只在**发布几何时**
     * 解析一次，而「拖到底没反应」这类问题只有把「用户要的值」和「真正生效的值」
     * 摆在同一行才查得出来 —— 与 [io.github.kotoriinsky.tsuzuri.core.geometry.FanSolve]
     * 的 `capReason` / `fitDesc` 是同一条纪律（那些也是「日志用的串」）。
     */
    val clampNote: String = "",
)

/**
 * 形状的几何：参数表 + 轮廓采样。**纯数学**，不碰 Android、不碰配置。
 */
object IconShapes {

    /**
     * 圆角方形的圆角半径 ÷ 半边长 = **0.45**。
     *
     * 出处是 iOS 图标的连续圆角：`0.2223 × 边长` ⇒ `0.4446 × 半边长`。
     * 我们用圆弧而不是连续圆角去逼近它，取 0.45 是为了让**视觉圆度**对得上 ——
     * 圆弧在接缝处的曲率比连续圆角大，同半径下看着更「圆」一点，所以不再往上加。
     *
     * 反例（为什么不能随手取 0.3）：Android 自适应图标的常规圆角是 `0.25 × 边长`
     * （≈ 0.5 × 半边长 ≈ 我们的 0.5），那已经接近 [TEARDROP] 的圆度了，
     * 与 [SQUIRCLE] 摆在一起会分不出哪个是哪个。
     */
    const val ROUNDED_SQUARE_CORNER = 0.45f

    /**
     * 超椭圆的指数 = **4.0**（Lamé 曲线里最接近 iOS 掩码的那一档）。
     *
     * 手感上的锚点：`n = 2` 是正椭圆（一眼是圆），`n = 10` 几乎就是方形（圆角只剩一点点）。
     * 4.0 是「方得有形、圆得顺眼」的那一档，也是文献里 iOS 掩码的常用近似值。
     */
    const val SQUIRCLE_EXPONENT = 4f

    /** 轮廓采样的默认点数（见类注释的精度说明）。 */
    const val DEFAULT_SAMPLES = 96

    // ── 用户可调数值的「没配过」哨兵与合法区间 ──────────────────────────────

    /**
     * 「用户没配过这个数值」的哨兵值 = `0`。
     *
     * 为什么用 `0` 而不是 `-1`：`PrefSpec.Slider` 的默认值就是 `0`
     * （`core/Catalog.kt` 的 `icon_shape_corner` / `icon_shape_exponent`），
     * 老配置里**根本没有这两个键**、读出来也是 0 —— 于是「没配过」和「配成 0」自动合流，
     * 而两者想要的都是同一件事：**用形状自己的默认值**。
     *
     * 这条是**回归防线**：老配置（没见过这两个滑块）画出来的结果必须与参数上线之前
     * **逐像素一致** —— 靠的就是「0 → [paramsOf] 的默认参数」这条短路。
     */
    const val UNSET = 0f

    /** 圆角比例（相对半边长）的合法区间。`> 1` 会让四段圆弧互相穿插（自交的多边形）。 */
    const val CORNER_FRACTION_MIN = 0f
    const val CORNER_FRACTION_MAX = 1f

    /**
     * 超椭圆指数的合法区间。
     *
     * 下限取 **2** 而不是 0：`n < 2` 的那一族是**凹**的（`n = 1` 是四条内凹的星形边），
     * 它属于另一个形状家族、不是这个参数说的「柔度（越大越方）」。
     * 所以 `icon_shape_exponent = 1.5` 会被夹到 2 并**打一条日志** ——
     * 静默地画出个星星，比「拖到底没反应」更难解释。
     */
    const val EXPONENT_MIN = 2f

    /** 上限 10：再大肉眼看就是方形了，而超大指数会让 `pow` 的偏差吃掉圆角。 */
    const val EXPONENT_MAX = 10f

    /** 用得到 [IconShapeParams.cornerFraction] 的形状（其余两个形状的轮廓由别的参数决定）。 */
    private val USES_CORNER_FRACTION = setOf(
        IconShape.ROUNDED_SQUARE,
        IconShape.TEARDROP,
        IconShape.ORIGINAL,
    )

    /** 取一个形状的几何参数。 */
    fun paramsOf(shape: IconShape): IconShapeParams = when (shape) {
        IconShape.CIRCLE -> IconShapeParams(shape, cornerFraction = 1f, exponent = 2f, teardropPointsBottomRight = false)
        IconShape.ROUNDED_SQUARE -> IconShapeParams(shape, ROUNDED_SQUARE_CORNER, 2f, false)
        IconShape.SQUIRCLE -> IconShapeParams(shape, 1f, SQUIRCLE_EXPONENT, false)
        IconShape.TEARDROP -> IconShapeParams(shape, 1f, 2f, teardropPointsBottomRight = true)
        IconShape.ORIGINAL -> IconShapeParams(shape, 0f, 2f, false)
    }

    /**
     * **把用户给的数值解析成最终参数**：
     *
     * ```
     * cornerPercent: 0(或没配过) → 形状自己的默认圆角
     *                >0          → /100 之后按 [CORNER_FRACTION_MIN]..[CORNER_FRACTION_MAX] 夹取
     * exponent:      0(或没配过) → 形状自己的默认指数
     *                >0          → 按 [EXPONENT_MIN]..[EXPONENT_MAX] 夹取
     * ```
     *
     * 两条不变式（都有单测钉住）：
     *
     *  1. **`0` = 形状默认值**：`resolveParams(s, 0f, 0f) == paramsOf(s)` 对每个形状成立，
     *     所以「老配置画出来的像素与参数上线前一致」这条回归防线是**结构上**成立的，
     *     不是靠调用方记得传对值；
     *  2. **夹取一定留下痕迹**：越界值被夹 / 数值对当前形状无用 → [IconShapeParams.clampNote]
     *     非空，调用方把它打进日志。**绝不静默**。
     *
     * @param cornerPercent 用户给的圆角百分比（`icon_shape_corner`），`0` = 用形状默认值
     * @param exponent 用户给的柔度（`icon_shape_exponent`），`0` = 用形状默认值
     */
    fun resolveParams(shape: IconShape, cornerPercent: Float, exponent: Float): IconShapeParams {
        val base = paramsOf(shape)
        val notes = ArrayList<String>(2)

        var corner = base.cornerFraction
        if (isSet(cornerPercent)) {
            if (shape !in USES_CORNER_FRACTION) {
                notes.add("圆角 ${fmt(cornerPercent)}% 对 ${shape.value} 无效(该形状不用圆角)")
            } else {
                val asked = cornerPercent / 100f
                corner = asked.coerceIn(CORNER_FRACTION_MIN, CORNER_FRACTION_MAX)
                if (corner != asked) {
                    notes.add(
                        "圆角 ${fmt(cornerPercent)}% 越界→${fmt(corner * 100f)}%" +
                            "(范围 ${fmt(CORNER_FRACTION_MIN * 100f)}–${fmt(CORNER_FRACTION_MAX * 100f)}%)",
                    )
                }
            }
        }

        var exp = base.exponent
        if (isSet(exponent)) {
            if (shape != IconShape.SQUIRCLE) {
                notes.add("柔度 ${fmt(exponent)} 对 ${shape.value} 无效(该形状不用指数)")
            } else {
                exp = exponent.coerceIn(EXPONENT_MIN, EXPONENT_MAX)
                if (exp != exponent) {
                    notes.add("柔度 ${fmt(exponent)} 越界→${fmt(exp)}(范围 ${fmt(EXPONENT_MIN)}–${fmt(EXPONENT_MAX)})")
                }
            }
        }

        return IconShapeParams(
            shape = shape,
            cornerFraction = corner,
            exponent = exp,
            teardropPointsBottomRight = base.teardropPointsBottomRight,
            clampNote = notes.joinToString("；"),
        )
    }

    /** 用户到底配过这个数没有（`0` / 负数 / NaN / 无穷 都算「没配过」）。 */
    private fun isSet(v: Float): Boolean = v.isFinite() && v > UNSET

    /** 日志里的小数：固定 `Locale.US`（与几何日志同一个规矩，见 [FanArcs.fmt]）。 */
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    /**
     * 形状的**轮廓**，采样成一圈点。
     *
     * ## 返回值约定（调用方要按这个契约用）
     *
     *  - 坐标系是 `[0, size] × [0, size]`（左上角为原点、`y` 朝下，与屏幕坐标一致），
     *    所以**不需要任何缩放/平移**就能直接连成 `android.graphics.Path`；
     *  - 布局是 `[x0, y0, x1, y1, …]`，共 `2N` 个数；
     *  - **闭合多边形**：首点与末点是不同的点，最后一段由「末点 → 首点」隐式给出
     *    （`Path.close()` 的语义）。这样调用方不必自己去重，也不会有「末点等于首点」
     *    造成的零长度线段；
     *  - 点序是**顺时针**（在 `y` 朝下的坐标系里，视觉上是从左上 → 右上 → 右下 → 左下）。
     *
     * @param shape 形状
     * @param size 画布边长（px）。轮廓**贴满**这个正方形（除了 [IconShape.TEARDROP]
     *   的尖角，它天然比圆更靠外一点 —— 那正是水滴的形状）。
     * @param samples 期望的总点数（内部按形状分摊，最少 8）。**不是字节级契约**，
     *   调用方只应依赖「点够密、够画一条平滑的轮廓」。
     */
    fun outline(shape: IconShape, size: Float, samples: Int = DEFAULT_SAMPLES): FloatArray =
        outline(paramsOf(shape), size, samples)

    /**
     * 形状的**轮廓**，采样成一圈点 —— **按显式参数**（用户调过数值时用这个入口）。
     *
     * 与 [outline] 是同一份实现的两个入口：[outline] 传的是「形状自己的默认参数」
     * （[paramsOf]），这里传调用方解析好的参数（[resolveParams] 的产物）。
     * 分成两个函数是为了**不动**那个已经被 hook 与预览共用的签名 ——
     * 它们都在用 `outline(shape, size, samples)`，改签名等于同时改两处调用点。
     *
     * ## 返回值约定（与 [outline] 逐字相同）
     *
     *  - 坐标系 `[0, size] × [0, size]`、布局 `[x0, y0, x1, y1, …]`、
     *    **闭合多边形**（首点 ≠ 末点）、点序顺时针（`y` 朝下）。
     *
     * @param params 解析后的形状参数。**不要传半成品**：它是 `0` 已经解析成形状默认值的
     *   最终值 —— 直接把 `icon_shape_corner = 0` 塞进来会把圆角画成 0（方角），
     *   而那与「没配过」不是一件事。
     * @param size 画布边长（px）
     * @param samples 期望的总点数（内部按形状分摊，最少 8）
     */
    fun outline(params: IconShapeParams, size: Float, samples: Int = DEFAULT_SAMPLES): FloatArray {
        val shape = params.shape
        val n = samples.coerceAtLeast(8)
        val s = if (size.isFinite() && size > 0f) size else 1f
        val half = s / 2f
        return when (shape) {
            // 正圆：直接参数采样，端点精确落在四条边的中点上
            IconShape.CIRCLE -> FloatArray(n * 2).also { out ->
                for (i in 0 until n) {
                    val t = 2.0 * PI * i / n
                    out[i * 2] = (half + half * cos(t)).toFloat()
                    out[i * 2 + 1] = (half + half * sin(t)).toFloat()
                }
            }

            // 超椭圆：x = h·sgn(cos t)·|cos t|^(2/n)、y = h·sgn(sin t)·|sin t|^(2/n)
            // （2/n 就是 Lamé 曲线的标准参数化；n=2 时它退化成 `h·cos t`，与正圆一致）
            // ⚠️ 指数取的是 **params.exponent**（用户可调），不是 `paramsOf(shape)` ——
            // 后者是形状自己的默认值 4.0，只有 [outline] 那条路会走到它。
            IconShape.SQUIRCLE -> {
                val e = 2.0 / params.exponent
                FloatArray(n * 2).also { out ->
                    for (i in 0 until n) {
                        val t = 2.0 * PI * i / n
                        val c = cos(t)
                        val sn = sin(t)
                        out[i * 2] = (half + half * Math.signum(c) * abs(c).pow(e)).toFloat()
                        out[i * 2 + 1] = (half + half * Math.signum(sn) * abs(sn).pow(e)).toFloat()
                    }
                }
            }

            // 水滴：三个角用**同一个**用户可调半径（缺省 = 满半径 = 正圆的四分之三），
            // 右下角半径 0（尖）。
            // ⚠️ 三个角共用 `params.cornerFraction` 是为了让 `icon_shape_corner` 对水滴也有效
            // （用户要求「每个形状都能自己调」）；缺省值 1.0 时它与上一版写死的
            // `floatArrayOf(half, half, 0f, half)` **逐点相同**，所以没配过的老配置不变。
            // 尖角那一角落在 (size, size)，比「半径 = h 的圆弧」更靠外 —— 这就是水滴的尖。
            // 半径数组的顺序是 [左上, 右上, **右下**, 左下]（与 roundedRect 的角心顺序一致），
            // 所以「右下尖」= 第三个位置给 0，其余三个给同一个值
            IconShape.TEARDROP -> {
                val c = half * params.cornerFraction
                roundedRect(
                    size = s,
                    radii = floatArrayOf(c, c, 0f, c),
                    perCorner = (n / 4).coerceAtLeast(2),
                )
            }

            // 圆角方形与「不裁」（`original`）：四个角共用同一个用户可调半径，
            // 缺省 0.45（iOS 连续圆角那一档）/ 0（真方角）
            else -> {
                val r = half * params.cornerFraction
                roundedRect(size = s, radii = floatArrayOf(r, r, r, r), perCorner = (n / 4).coerceAtLeast(2))
            }
        }
    }

    /**
     * 四点圆角矩形的轮廓，**每个角一个半径**（顺序：左上、右上、右下、左下）。
     *
     * 为什么要「每个角一个半径」而不是一个：水滴就是「三个角取满、一个角取 0」，
     * 一个通用实现比两份特例代码更难写错（角与角的顺序只在这里出现一次）。
     *
     * 半径按 `[0, 半边长]` 夹取：`> 半边长` 的两段圆弧会互相穿插（自交的多边形在
     * `Path` 上会画出一团乱线），`< 0` 更是没有意义的输入。半径 0 的角退化成**一个点**，
     * 于是那两段直边直接连到角上 —— 正是「方角」想要的形状。
     *
     * 圆弧用 `theta ∈ [180°,270°] / [270°,360°] / [0°,90°] / [90°,180°]` 采样
     * （`y` 朝下的屏幕坐标系），四个象限依次对应四个角；每段**含两个端点**，
     * 所以相邻两段之间的直边端点精确落在切点上，直边不会因采样而缩水。
     */
    private fun roundedRect(size: Float, radii: FloatArray, perCorner: Int): FloatArray {
        require(radii.size == 4) { "radii 必须是四个角" }
        val half = size / 2f
        val r = FloatArray(4) { radii[it].coerceIn(0f, half).takeIf { v -> v.isFinite() } ?: 0f }
        // 角心（顺时针：左上、右上、右下、左下）。
        // ⚠️ 两个数组的**下标必须一一对应**：右上角的 y 心是 r[1] 而不是 size−r[1]，
        // 右下角两个分量都是 size−r[2]（写成 size−r[3] 会把圆角挪到隔壁角上，
        // 症状是水滴的尖角跑到左下、还有一段弧伸到画布外面）
        val cx = floatArrayOf(r[0], size - r[1], size - r[2], r[3])
        val cy = floatArrayOf(r[0], r[1], size - r[2], size - r[3])
        // 每段圆弧的起始角（度）—— 与上面角心的顺序一一对应
        val startDeg = floatArrayOf(180f, 270f, 0f, 90f)

        val out = ArrayList<Float>(perCorner * 8 + 8)
        for (k in 0 until 4) {
            for (i in 0..perCorner) {
                val deg = startDeg[k] + 90f * i / perCorner
                val t = Math.toRadians(deg.toDouble())
                val x = (cx[k] + r[k] * cos(t)).toFloat()
                val y = (cy[k] + r[k] * sin(t)).toFloat()
                // 半径 0 的角会在同一个点上产生 perCorner+1 个重复点，去重（首尾相接处同理）
                if (out.size >= 2) {
                    val lx = out[out.size - 2]
                    val ly = out[out.size - 1]
                    if (lx == x && ly == y) continue
                }
                out.add(x)
                out.add(y)
            }
        }
        // 闭合多边形的首尾若重合，去掉最后一个（契约：首点 ≠ 末点）
        if (out.size >= 4) {
            val n = out.size
            if (out[0] == out[n - 2] && out[1] == out[n - 1]) {
                out.removeAt(n - 1)
                out.removeAt(n - 2)
            }
        }
        return out.toFloatArray()
    }
}
