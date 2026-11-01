package io.github.kotoriinsky.tsuzuri.core.geometry

/**
 * 触发热区（底部两角那两块矩形）的**唯一一份求值**。
 *
 * 为什么要有这个文件：热区在两处被求值——hook 侧 `FanConfig.spec()`（真机判定）与
 * `FanPreview`（设置页画的两个热区框）。此前两边各写一行 `screen * percent / 100`，
 * 基准一变就要记得改两处，漏一处的表现是「预览画的框和真机的框不一样大」。
 * 收进来之后两侧传同一批入参、拿到同一个数。
 *
 * ## 基准是「竖屏屏幕」，不是当下屏幕（2026-09-20 用户拍板）
 *
 * 两个百分比若按**当下**屏幕的宽/高求值，转屏后轴互换，热区会变形。
 * 本机（1220×2656，密度 3.25）的对照：
 *
 * | | 竖屏 | 横屏（按当下轴） |
 * | --- | --- | --- |
 * | 角宽 = 18% | 220px ≈ 68dp | 479px ≈ **147dp** |
 * | 角高 = 5.5% | 146px ≈ 45dp | 67px ≈ **21dp** |
 *
 * 横屏下变成一条又宽又扁的带子：纵向只剩 21dp，离底边约 8mm 的起手点直接落空。
 * Flyme 的热区是固定的 44dp × 44dp 方块，天生与朝向无关；我们保留百分比参数，
 * 但把求值基准钉在**竖屏屏幕**上（宽 ← 短边、高 ← 长边）——横竖屏就是同一块热区。
 *
 * 与「小窗横屏保持竖屏形状」是同一个哲学：形状由竖屏定义，横屏整块搬过去。
 * 竖屏下（W < H）`min/max` 恰好就是 `(W, H)`，**一个像素都不变**，老用户无感。
 *
 * ## 退化
 *
 * 非有限输入（NaN / Inf）与 ≤0 的结果一律退到最小 1px：热区不可用时的失败方向是
 * 「几乎无法触发」（与没装模块一致），而不是 NaN 沿比较链传播成不可预测的分支。
 */
object FanHotZone {

    /** 触发区宽（px）= `widthPercent%` × **竖屏屏宽**（短边）。 */
    fun widthPx(screenW: Float, screenH: Float, widthPercent: Float): Float {
        val base = if (screenW.isFinite() && screenH.isFinite()) {
            minOf(screenW, screenH)
        } else {
            0f
        }
        return if (widthPercent.isFinite()) {
            maxOf(1f, base * widthPercent / 100f)
        } else {
            1f
        }
    }

    /** 触发区高（px）= `heightPercent%` × **竖屏屏高**（长边）。 */
    fun heightPx(screenW: Float, screenH: Float, heightPercent: Float): Float {
        val base = if (screenW.isFinite() && screenH.isFinite()) {
            maxOf(screenH, screenW)
        } else {
            0f
        }
        return if (heightPercent.isFinite()) {
            maxOf(1f, base * heightPercent / 100f)
        } else {
            1f
        }
    }

    /**
     * 左、右两角的热区矩形（px），与 hook `FanGeometry.cornerAt` 的判据对齐：
     * 贴底边、贴侧边，宽 [widthPx]、高 [heightPx]。预览画框与触发页的屏幕高亮都用它。
     */
    fun cornerRects(
        screenW: Float,
        screenH: Float,
        widthPercent: Float,
        heightPercent: Float,
    ): Pair<ScreenRect, ScreenRect> {
        val w = widthPx(screenW, screenH, widthPercent)
        val h = heightPx(screenW, screenH, heightPercent)
        return ScreenRect(0f, screenH - h, w, screenH) to
            ScreenRect(screenW - w, screenH - h, screenW, screenH)
    }

    /** 实际可起手的角（`corners` 关掉的一角不出现）。左在前、右在后。 */
    fun zones(
        screenW: Float,
        screenH: Float,
        widthPercent: Float,
        heightPercent: Float,
        allowLeft: Boolean,
        allowRight: Boolean,
    ): List<ScreenRect> {
        val (left, right) = cornerRects(screenW, screenH, widthPercent, heightPercent)
        return buildList {
            if (allowLeft) add(left)
            if (allowRight) add(right)
        }
    }
}
