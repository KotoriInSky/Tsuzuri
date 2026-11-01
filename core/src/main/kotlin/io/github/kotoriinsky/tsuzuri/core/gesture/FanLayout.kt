package io.github.kotoriinsky.tsuzuri.core.gesture

/**
 * 底角扇形菜单的几何。
 *
 * ## 模型来自 Flyme（已在设备反编译产物里确认）
 *
 * 素材：`research/decompiled/systemuitools/sources/com/flyme/systemuitools/windowmode/widget/GestureAppLauncher.java`
 *
 *  - **布局**：`m(i, n)` 算第 i 项的角度
 *    - `inset == 0`：`angle = (90 / (n + 1)) * (i + 1)`
 *    - `inset != 0`：`f = (90 - inset * 2) / n`；`angle = i * f + f / 2 + inset`
 *    也就是把 n 项摊在 **90° 扇**里。
 *  - **命中**：`n(x, y) = degrees(acos((centerY - y) / r))` 取手指极角，
 *    再看距离是否落在该项的环带 `[内径, 外径]` 内（`o(x, y)` 算距离）。
 *  - **展开方向**：圆心贴在屏幕左/右下角，`centerY` 取屏幕纵向中心（`calcGesturePosition`）。
 *
 * ## 为什么放在 `:core` 而不是留在 hook 代码里
 *
 * 和 [CornerGesture] 同一个理由：几何是纯数学，放在这里才能用 JVM 单测钉住
 * （见 `FanLayoutTest`）。**手势偶尔选错项**这种问题，靠真机手指试是极难定位的，
 * 而它几乎总是几何算错。
 *
 * ## 一处有意的偏离
 *
 * Flyme 的 `n()` 用 `acos((centerY - y) / R)`，其中 `R` 是一个常量半径。
 * 手指距离不等于 `R` 时 `(centerY - y) / R` 会超出 `[-1, 1]`，`acos` 返回 NaN。
 * 这里改用 `atan2(dx, dyUp)` —— 数学上等价（都是「相对竖直方向的夹角」），
 * 但**不会因为距离变化而失效**。函数注释里标了这个差别。
 */
object FanLayout {

    // ⚠️ 生产代码目前已不引用本文件（2026-09-13）。
    // 扇形手势的几何改按 HyperGesture 实现（见 hook/.../feature/smallwindow/FanArcs.kt）：
    // 它是 1–3 层同心弧、每层 70°(12°–82°)、中间层错位 3.5°、命中走「逐个图标的圆」。
    // 本文件是 **Flyme** 的单层 90° 扇模型。
    //
    // 之所以留着而不是删：那 19 个单测（FanLayoutTest）是目前唯一把「极角 + 环带」
    // 这套算法钉住的资产，而 Flyme 的模型在对照魅族真机时仍要用。真要删，请连同
    // FanLayoutTest 一起删，别只删一边 —— 只删实现会让测试变成悬空的装饰。


    /** 扇形张角。Flyme 是 90°。 */
    const val SPAN_DEG = 90.0

    /** 角度偏移（度）。0 = 贴边不留缝。 */
    const val DEFAULT_INSET_DEG = 0.0

    /**
     * 第 [index] 项（共 [count] 项）在扇里的角度。
     *
     * @param insetDeg 两端各留出的角度，非 0 时走 Flyme 的第二条公式
     */
    fun itemAngle(index: Int, count: Int, insetDeg: Double = DEFAULT_INSET_DEG): Double {
        if (count <= 0) return 0.0
        if (insetDeg == 0.0) {
            return (SPAN_DEG / (count + 1)) * (index + 1)
        }
        val step = (SPAN_DEG - insetDeg * 2) / count
        return index * step + step * 0.5 + insetDeg
    }

    /** 全部项的角度，方便一次性喂给 [hitTest]。 */
    fun itemAngles(count: Int, insetDeg: Double = DEFAULT_INSET_DEG): List<Double> =
        (0 until count).map { itemAngle(it, count, insetDeg) }

    /**
     * 手指相对扇形圆心的极角（度），0° = 正上方，90° = 正侧方（**朝屏内那一侧**）。
     *
     * ⚠️ 关键：贴右展开时必须把 x **镜像**再算角度。
     *
     * Flyme 的 `o()` 里有一行 `if (p == 1) { f6 = getMeasuredWidth() - f6; }` ——
     * 它的扇形**始终在左侧局部坐标系里计算**，贴右展开时先把 x 翻过来，
     * 于是 0..90° 那一套公式左右通用。漏掉这一步的症状是：
     * 贴右展开算出的角度全为负，命中判定永远返回 -1，表现为「右边划不出来、左边正常」。
     * （这条是我写这个类时漏了、被 `FanLayoutTest` 抓出来的。）
     *
     * 这是对 Flyme `n()` 的等价改写，另外还在分母上做了一处偏离，见类注释。
     * 手指正好在圆心上时方向无意义，返回 null。
     *
     * @param openToRight 扇形是否贴右展开（来自 [opensToRight]）
     */
    fun polarAngle(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        openToRight: Boolean,
    ): Double? {
        // 朝屏内的水平分量：贴左时 x 增大朝内，贴右时 x 减小朝内
        val inwardDx = (if (openToRight) centerX - x else x - centerX).toDouble()
        // 屏幕 y 轴朝下增长，所以「向上」是 centerY - y
        val dyUp = (centerY - y).toDouble()
        if (inwardDx == 0.0 && dyUp == 0.0) return null
        return Math.toDegrees(kotlin.math.atan2(inwardDx, dyUp))
    }

    /** 手指到圆心的距离。对应 Flyme 的 `o(x, y)`。 */
    fun radius(x: Float, y: Float, centerX: Float, centerY: Float): Double {
        val dx = (x - centerX).toDouble()
        val dy = (y - centerY).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 命中判定：手指落在哪一项上。
     *
     * @param angleDeg 手指极角（来自 [polarAngle]）
     * @param radius 手指到圆心的距离（来自 [radius]）
     * @param angles 各项角度（来自 [itemAngles]）
     * @param inner 环带内径；小于它算「还没滑出去」
     * @param outer 环带外径；大于它算「滑过头了」
     * @return 命中项下标；未命中返回 -1（调用方按「取消」处理）
     */
    fun hitTest(
        angleDeg: Double,
        radius: Double,
        angles: List<Double>,
        inner: Double,
        outer: Double,
    ): Int {
        if (angles.isEmpty()) return -1
        if (radius < inner || radius > outer) return -1
        val step = SPAN_DEG / angles.size
        val half = step * 0.5
        for ((i, a) in angles.withIndex()) {
            // 每个项占 [a - half, a + half) 的角度带，与 Flyme 的 q() 一致
            if (angleDeg >= a - half && angleDeg < a + half) return i
        }
        return -1
    }

    /**
     * 扇形贴哪个角展开 —— 由最初按下的位置决定（Flyme 的 `calcGesturePosition`：
     * 触摸在屏幕右半侧就贴右边）。
     *
     * @return true = 贴右下角，false = 贴左下角
     */
    fun opensToRight(touchX: Float, screenWidth: Float): Boolean = touchX > screenWidth / 2f

    /**
     * 扇形圆心。
     *
     * Flyme 取屏幕**纵向中心**作为 `centerY`（`setCenterPosition(screenSize.y)`），
     * 横向则贴触发的那一侧边。
     */
    fun center(
        openToRight: Boolean,
        screenWidth: Float,
        screenHeight: Float,
    ): Pair<Float, Float> = Pair(
        if (openToRight) screenWidth else 0f,
        screenHeight / 2f,
    )
}
