package io.github.kotoriinsky.tsuzuri.core.gesture

/**
 * 底角手势的几何判定。
 *
 * ## 为什么单独放在 `:core` 而不是留在 `CornerSwipeDetector` 里
 *
 * 这段判定原来长在 hook 代码里，和 `MotionEvent` 绑死，于是**没法测**。
 * 而它恰恰是整个特性里最容易算错的部分：角度区间、inward 方向、屏幕坐标 y 轴朝下，
 * 三个都错一处就表现为「手势偶尔不灵」，靠肉眼在真机上划很难定位。
 *
 * 抽成纯函数之后它可以在 JVM 上跑单元测试（`CornerGestureTest`），
 * 而 Android 那一侧只剩「读 MotionEvent 的 x/y 和 action」这点胶水。
 *
 * 判据本身照搬 Flyme 的三段式。
 */
object CornerGesture {

    enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Flyme 实测：从按到判定必须发生在 300ms 内。 */
    const val MAX_DURATION_MS = 300L

    /** 角度窗口（度）。Flyme 原文是 20°–90°，90° 是纯竖直，取 88 留余量。 */
    const val MIN_ANGLE_DEG = 20.0
    const val MAX_ANGLE_DEG = 88.0

    /**
     * 按下的点落在哪个底角方块里。
     *
     * @param cornerSize 角块边长（像素）
     * @return 命中的角；不在任何角上（或该角被配置禁用）返回 null
     */
    fun cornerAt(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerSize: Float,
        allowLeft: Boolean,
        allowRight: Boolean,
    ): Corner? {
        // 必须从底部开始 —— 顶部角落不参与
        if (y < height - cornerSize) return null
        if (allowLeft && x < cornerSize) return Corner.BOTTOM_LEFT
        if (allowRight && x > width - cornerSize) return Corner.BOTTOM_RIGHT
        return null
    }

    /**
     * 位移方向是否构成「朝屏内上方」的斜线，是则返回角度（度）。
     *
     * 坐标系注意：屏幕 y 轴**向下**增长。所以「手指向上滑」时
     * `y_now < y_down`，向上位移要写成 `dy = yDown - y`。
     * 调用方传进来的 `dy` 必须是这个已经翻正过的值。
     *
     * 左下角要求 `dx > 0`（右手方向 = 朝屏内），右下角要求 `dx < 0`。
     *
     * @return 归一化后的角度；方向不合法返回 null
     */
    fun inwardAngle(dx: Float, dy: Float, corner: Corner): Double? {
        val inward = when (corner) {
            Corner.BOTTOM_LEFT -> dx
            Corner.BOTTOM_RIGHT -> -dx
        }
        if (inward <= 0f || dy <= 0f) return null
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), inward.toDouble()))
    }

    /** 角度是否落在有效窗口内。 */
    fun isAngleValid(
        angleDeg: Double,
        min: Double = MIN_ANGLE_DEG,
        max: Double = MAX_ANGLE_DEG,
    ): Boolean = angleDeg >= min && angleDeg <= max

    /**
     * 一步到位的判定：给定按下点和当前点，这次位移算不算「底角斜滑」。
     *
     * @param downX/downY 按下点
     * @param x/y 当前点
     * @param minDistance 起步最小位移（像素）
     */
    fun isSwipe(
        downX: Float,
        downY: Float,
        x: Float,
        y: Float,
        corner: Corner,
        minDistance: Float,
    ): Boolean {
        val dx = x - downX
        val dy = downY - y
        if (dx * dx + dy * dy < minDistance * minDistance) return false
        val angle = inwardAngle(dx, dy, corner) ?: return false
        return isAngleValid(angle)
    }
}
