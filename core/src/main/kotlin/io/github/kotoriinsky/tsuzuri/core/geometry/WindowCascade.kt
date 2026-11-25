package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 小窗呼出时的**防严丝合缝阶梯偏移**（纯几何，零 Android 依赖）。
 *
 * ## 为什么需要它
 *
 * 用户选了 Flyme 预设或自定义摆放时，每次从扇形呼出小窗都会算出完全相同的屏幕矩形。
 * 已有正常小窗停在那里时，新呼出的小窗会与旧窗严丝合缝完全重合，看不出底下还压着一个窗。
 *
 * ## 约定
 *
 *  - **只避开已有的正常小窗**：调用方传入的 [occupied] 已排除挂边（pin）与迷你窗；
 *    没有正常小窗（或已有窗已被拖走、不与 [desired] 严丝合缝）时，原样返回 [desired]，零扰动。
 *  - **尺寸不变、永不出屏**：只平移 `(left, top)`，宽高保持不变并严格夹在 `[0, screen - size]` 内。
 *  - **无状态**：完全按屏幕当下真实小窗位置计算，关掉或移走旧窗后下次立刻回到原位。
 */
object WindowCascade {

    /** 默认错位步长（dp）。12dp 在主流手机上约 36~40px，刚好清晰露边又不靠近屏幕边缘。 */
    const val DEFAULT_STEP_DP = 12f

    /** 判定「严丝合缝」的距离阈值比例：X 与 Y 同时小于 `stepPx × 它` 即认为重合。 */
    private const val FLUSH_THRESHOLD_RATIO = 0.6f

    /** 最多向外探测几档步长。 */
    private const val MAX_STEPS = 4

    /**
     * 若 [desired] 与 [occupied] 中任一正常小窗严丝合缝，返回一个轻微错位且不出屏的矩形；
     * 否则原样返回 [desired]。
     */
    fun avoidFlushOverlap(
        desired: ScreenRect,
        occupied: List<ScreenRect>,
        screenW: Float,
        screenH: Float,
        stepPx: Float,
    ): ScreenRect {
        if (occupied.isEmpty() || !WindowBounds.isUsable(desired)) return desired
        if (!stepPx.isFinite() || stepPx <= 1f) return desired
        if (!screenW.isFinite() || !screenH.isFinite() || screenW <= 0f || screenH <= 0f) return desired

        val minSep = stepPx * FLUSH_THRESHOLD_RATIO
        if (!isFlushWithAny(desired, occupied, minSep)) return desired

        val maxLeft = max(0f, screenW - desired.width)
        val maxTop = max(0f, screenH - desired.height)

        // 先按阶梯方向（右下优先，其次左下 / 右上 / 左上，以及单轴兜底）找第一个不严丝合缝的位置
        val candidates = ArrayList<ScreenRect>(MAX_STEPS * 8)
        for (k in 1..MAX_STEPS) {
            val d = stepPx * k
            addCandidate(candidates, desired, d, d, maxLeft, maxTop)
            addCandidate(candidates, desired, -d, d, maxLeft, maxTop)
            addCandidate(candidates, desired, d, -d, maxLeft, maxTop)
            addCandidate(candidates, desired, -d, -d, maxLeft, maxTop)
            addCandidate(candidates, desired, 0f, d, maxLeft, maxTop)
            addCandidate(candidates, desired, 0f, -d, maxLeft, maxTop)
            addCandidate(candidates, desired, d, 0f, maxLeft, maxTop)
            addCandidate(candidates, desired, -d, 0f, maxLeft, maxTop)
        }

        for (c in candidates) {
            if (!isFlushWithAny(c, occupied, minSep)) return c
        }

        // 极端情况（窗口几乎满宽满高、余量小于阈值）：挑离最近已有窗距离最大的那个候选，尽量错开
        var best = desired
        var bestScore = minChebyshevDistance(desired, occupied)
        for (c in candidates) {
            val score = minChebyshevDistance(c, occupied)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun addCandidate(
        out: MutableList<ScreenRect>,
        base: ScreenRect,
        dx: Float,
        dy: Float,
        maxLeft: Float,
        maxTop: Float,
    ) {
        val left = (base.left + dx).coerceIn(0f, maxLeft)
        val top = (base.top + dy).coerceIn(0f, maxTop)
        if (abs(left - base.left) < 0.5f && abs(top - base.top) < 0.5f) return
        out.add(ScreenRect(left, top, left + base.width, top + base.height))
    }

    private fun isFlushWithAny(rect: ScreenRect, occupied: List<ScreenRect>, minSep: Float): Boolean =
        occupied.any { o ->
            abs(rect.left - o.left) < minSep && abs(rect.top - o.top) < minSep
        }

    /** 候选矩形到所有已有窗的最小切比雪夫距离（max(|Δx|, |Δy|)）：越大越不严丝合缝。 */
    private fun minChebyshevDistance(rect: ScreenRect, occupied: List<ScreenRect>): Float {
        var m = Float.MAX_VALUE
        for (o in occupied) {
            val d = max(abs(rect.left - o.left), abs(rect.top - o.top))
            m = min(m, d)
        }
        return m
    }
}
