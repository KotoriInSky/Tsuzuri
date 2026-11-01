package io.github.kotoriinsky.tsuzuri.core.geometry

/**
 * 侧滑索引的起手热区：**唯一一份判定与求值**。
 *
 * 为什么要有这个文件：真实判定原本是 hook 里
 * `SideIndexDetector` 在 ACTION_DOWN 写的三道闸，两个保留区是 `SideIndex` 的私有常量；
 * 设置界面的预览画的只是「把手」，与判定无关 —— 用户调「触发宽度」时看到的那一条
 * 根本不是能起手的区域。收进来之后 hook 的判定、预览、触发页的屏幕高亮
 * 三处读同一份常量与同一个算式。
 *
 * ## 热区的形状
 *
 * 左 / 右边缘各一条竖带，宽 `trigger_width`dp，**整条边缘**（不是把手那一段），
 * 扣掉顶部 [TOP_GUARD_DP] 与底部 [CORNER_GUARD_DP]：
 *
 *  - 底部：底角小窗与手势导航的地盘，侧滑索引不抢。它同时覆盖了 Flyme
 *    守卫里「y > 屏幕高 − 底部 insets」那一条（`SlideAppsTouchController.java:104`）。
 *  - 顶部：系统的下拉区（状态栏 / 控制中心）。2026-09-14 实测反馈「沿边缘下滑呼出索引，
 *    容易拉开控制中心」—— 两者抢的是同一条边缘上的同一段位移。取 120dp：状态栏 +
 *    手势提示条之外还留了余量，宁可少一截起手区，也不要和系统抢。
 *
 * 两个保留区**不做成参数**（ADR 同一条）：它们是与系统手势的仲裁边界，不是手感量。
 *
 * ## 判定顺序必须与真机逐位一致
 *
 * [classify] 先判底部、再判顶部、最后判边缘带；两侧同时命中（带宽超过半屏）时
 * 归右侧 —— 这与改造前 `SideIndexDetector.handle` 的 `onRight = inRight` 完全相同，
 * 单测里钉住了这几条边界。
 */
object SideIndexHotZone {

    /** 底部角落保留区（dp）。小窗优先，这里不抢。 */
    const val CORNER_GUARD_DP = 56f

    /** 顶部保留区（dp）：**不许在这一带起手**。理由见类注释。 */
    const val TOP_GUARD_DP = 120f

    /** 一次按下的判定结果。输入分派线程上调用：返回枚举常量，不分配。 */
    enum class Verdict {
        /** 落在底部保留区。 */
        BOTTOM_GUARD,

        /** 落在顶部保留区。 */
        TOP_GUARD,

        /** 不在任何一条允许的边缘带里。 */
        OUTSIDE,

        /** 左边缘起手。 */
        LEFT,

        /** 右边缘起手。 */
        RIGHT,
    }

    /**
     * 判定 DOWN 点 `(x, y)` 能不能起手、从哪一侧起手。
     *
     * 全部入参都是 px；保留区由调用方按 `dp × density` 换算好传进来
     * （hook 侧把它们放进 `TriggerConfig` 快照，DOWN 时不碰 Resources）。
     */
    fun classify(
        x: Float,
        y: Float,
        screenW: Float,
        screenH: Float,
        edgePx: Float,
        allowLeft: Boolean,
        allowRight: Boolean,
        topGuardPx: Float,
        bottomGuardPx: Float,
    ): Verdict {
        if (y > screenH - bottomGuardPx) return Verdict.BOTTOM_GUARD
        if (y < topGuardPx) return Verdict.TOP_GUARD
        val inLeft = allowLeft && x < edgePx
        val inRight = allowRight && x > screenW - edgePx
        return when {
            inRight -> Verdict.RIGHT
            inLeft -> Verdict.LEFT
            else -> Verdict.OUTSIDE
        }
    }

    /**
     * 热区矩形（px）：左带在前、右带在后，关掉的一侧不出现。给预览与屏幕高亮画用。
     *
     * 矩形边界与 [classify] 的严格不等号差一个像素（`ScreenRect` 是左闭右开），
     * 画出来看不出差别；判定只认 [classify]。
     * 非有限或 ≤0 的宽度、被保留区吃光的高度 → 不出矩形（画空，而不是画一条 NaN）。
     */
    fun zones(
        screenW: Float,
        screenH: Float,
        density: Float,
        widthDp: Float,
        allowLeft: Boolean,
        allowRight: Boolean,
    ): List<ScreenRect> {
        val edge = widthDp * density
        val top = TOP_GUARD_DP * density
        val bottom = screenH - CORNER_GUARD_DP * density
        if (!edge.isFinite() || edge <= 0f || !bottom.isFinite() || bottom <= top) return emptyList()
        return buildList {
            if (allowLeft) add(ScreenRect(0f, top, edge, bottom))
            if (allowRight) add(ScreenRect(screenW - edge, top, screenW, bottom))
        }
    }
}
