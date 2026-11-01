package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.max

/**
 * 展开动画的**时长与插值** —— 真机与设置页预览共用的那一份公式。
 *
 * ## 为什么要有这一类
 *
 * 动效参数（`animation_speed` / `fan_row_stagger` / `fan_rotation` / `fan_reveal_amount`）
 * 在真机上是「看得见但读不出来」的东西：速度是快是慢、错开有没有生效、自转幅度够不够，
 * 只能靠反复划手势去感受。最终决定是**在预览里真的演一遍**，
 * 而那要求预览与真机用**同一套时长与曲线** —— 否则「预览里看着挺好」到了真机是另一回事，
 * 而这个项目已经在几何上吃过这种亏（预览与真机给出两个答案，用户只会相信预览）。
 *
 * ## 与 hook 侧的关系（**已知债，写在明处**）
 *
 * `hook/feature/smallwindow/FanOverlay.kt` 里有一份**同样公式的实现**
 * （`startReveal` / `reveal` / `revealRotation` / `easeOutBack`），它逐字对应
 * `FanOverlayView.java` 的 373-374 / 391-393 / 400 / 403 / 702-746 行，并且已经在设备上
 * 验证过。本轮**刻意不动它**：host 侧的动画改动没有可自动化的回归手段（只能靠人眼看），
 * 在没有设备验证的前提下重写一段已验证的代码，风险大于收益。
 *
 * 所以这里是**第二处**实现。改任何一侧都必须同步改另一侧 —— 两处都写了这段注释，
 * `FanRevealTest` 把基准值钉死（真机日志里的 222ms / 8ms 这类数），
 * 哪天 hook 侧调整了常量，这里会以测试失败的形式暴露出来。
 *
 * 公式（`FanOverlayView`）：
 *
 * ```
 * revealMs  = clamp(max(45, 170 * 100 / speed), ·, 400)
 * rotationMs= clamp(max(45, 150 * 100 / speed), ·, 400)
 * staggerMs = clamp(max(8,  1200 / speed),       ·, 400)   ← 注意**不乘 100**
 * fadeMs    = clamp(max(45, 110 * 100 / speed), ·, 400)
 * total     = max(revealMs, rotationMs + staggerMs * (count - 1))
 * tail      = max(total, fadeMs)
 * 进度      = easeOutBack(clamp(elapsed / revealMs, 0, 1))
 * 大小      = 图标直径 × (0.78 + 0.22 × 进度) × 选中放大
 * 倾角      = 自转幅度 × 方向 × (1 − easeOutBack(clamp((elapsed − staggerMs×index)/rotationMs)))
 * ```
 *
 * 注意**只有自转是错峰的**：位移与缩放所有项共用一个进度 —— HyperGesture 就是
 * 「所有项一起飞出、各自歪着转正」，上一版真机侧曾按项错开整个出现时间，那是错的。
 */
class FanAnimationTiming(
    /** `animation_speed`（%）。**是除数不是毫秒**：100 = 基准速度。 */
    speedPercent: Float,
    /** 槽位数（错峰总时长按它算）。 */
    val count: Int,
) {
    private val speed: Float = if (speedPercent.isFinite() && speedPercent > 0f) speedPercent else 100f

    val revealMs: Float = clampMs(max(MIN_MS, BASE_REVEAL_MS * 100f / speed))
    val rotationMs: Float = clampMs(max(MIN_MS, BASE_ROTATION_MS * 100f / speed))
    val staggerMs: Float = clampMs(max(MIN_STAGGER_MS, STAGGER_BASE_MS / speed))
    val fadeMs: Float = clampMs(max(MIN_MS, BASE_FADE_MS * 100f / speed))

    /** 一轮动画真正的长度（= ValueAnimator 的 duration）。 */
    val totalMs: Float =
        max(revealMs, rotationMs + staggerMs * (count.coerceAtLeast(1) - 1)).let { max(it, fadeMs) }

    private fun clampMs(v: Float): Float = v.coerceAtMost(MAX_MS)

    private companion object {
        // 与 FanOverlay 的同名常量一一对应（那边在 ValueAnimator 的注释里）
        const val BASE_REVEAL_MS = 170f
        const val BASE_ROTATION_MS = 150f
        const val BASE_FADE_MS = 110f
        const val STAGGER_BASE_MS = 1200f
        const val MAX_MS = 400f
        const val MIN_MS = 45f
        const val MIN_STAGGER_MS = 8f
    }
}

/**
 * 某一时刻的动画状态。**纯值对象**：给一个 `elapsedMs` 就给出这一帧的进度、大小倍率、
 * 每一项的倾角，不持有任何时间源（预览那边用 `withFrameNanos` 喂它）。
 */
class FanAnimation(
    val timing: FanAnimationTiming,
    /** 从动画开始算起的毫秒数。超过 [FanAnimationTiming.totalMs] 就是终态。 */
    val elapsedMs: Float,
    /** `fan_rotation`（度）。0 = 不转。 */
    val rotationDegrees: Float,
    /** 贴右角时自转方向取反（`FanOverlayView.java:719-720`）。 */
    val openToRight: Boolean,
) {
    /** 位移/缩放共用的进度（`easeOutBack` 之后，可能略微过冲 > 1）。 */
    val reveal: Float = easeOutBack((elapsedMs / timing.revealMs).coerceIn(0f, 1f))

    /** 整体淡入（窗口那一层）。 */
    val alpha: Float = (elapsedMs / timing.fadeMs).coerceIn(0f, 1f)

    /** 图标直径的缩放倍率：`0.78 → 1.0`。 */
    val diameterScale: Float = 0.78f + 0.22f * reveal

    /** 这一帧结束了吗（调用方据此停在终态）。 */
    val finished: Boolean get() = elapsedMs >= timing.totalMs

    /**
     * 第 [index] 项的倾角（度）。按项错峰：`(elapsed − stagger × index) / rotationMs`，
     * 归零过程同样走 [easeOutBack]，到达后恒为 0。
     */
    fun rotationFor(index: Int): Float {
        if (rotationDegrees == 0f) return 0f
        val t = ((elapsedMs - timing.staggerMs * index) / timing.rotationMs).coerceIn(0f, 1f)
        val p = easeOutBack(t)
        if (p >= 1f) return 0f
        return rotationDegrees * (if (openToRight) -1f else 1f) * (1f - p)
    }

    companion object {
        /**
         * 缓出过冲曲线，逐字来自 `FanOverlayView.easeOutBack`（`:743-746`）：
         * `2.70158·(v−1)³ + 1 + 1.70158·(v−1)²`。
         *
         * 过冲是**故意的**：图标会稍微越过终点再弹回来（v=0.85 附近最大值约 1.02），
         * 抹平它就不是 HyperGesture 的手感了。
         */
        fun easeOutBack(v: Float): Float {
            val s = v - 1f
            return 2.70158f * s * s * s + 1f + 1.70158f * s * s
        }
    }
}
