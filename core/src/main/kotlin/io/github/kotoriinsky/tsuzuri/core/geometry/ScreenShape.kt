package io.github.kotoriinsky.tsuzuri.core.geometry

/**
 * 预览画的那块屏幕：**当前所在屏幕**的真实尺寸、四角圆角与挖孔（屏幕 px，当前朝向）。
 *
 * 为什么要有它：预览原来用设置界面窗口的 `displayMetrics` 取尺寸，圆角是写死的
 * 「短边 6% / 7%」，挖孔不画 —— 平板、折叠屏内屏（接近方形）、横屏下画出来都是
 * 「一台比例不对的手机」。现在由 ui 从系统读真值（`WindowManager.maximumWindowMetrics`、
 * `Display.getRoundedCorner`、`DisplayCutout`）组装成这个对象，两个预览画布与「触发」页
 * 的屏幕高亮都用它；折叠 / 展开、转屏时重新读一次。
 *
 * 读不到的部分有退路（见 [of]），退路只影响**观感**，不影响几何：尺寸读不到就没有预览。
 */
class ScreenShape(
    val width: Float,
    val height: Float,
    val density: Float,
    /** 四角圆角半径（px），顺序：左上、右上、右下、左下。0 = 直角。 */
    val corners: FloatArray,
    /** 挖孔 / 刘海的外接矩形（px）。画的时候按胶囊形画（打孔屏的外接矩形是正方形 → 圆）。 */
    val cutouts: List<ScreenRect>,
    /** 圆角是不是系统给的真值（`false` = 用了 [FALLBACK_CORNER_RATIO] 估的）。诊断用。 */
    val cornersReal: Boolean,
) {
    val isLandscape: Boolean get() = width > height

    override fun toString(): String =
        "ScreenShape(${width.toInt()}x${height.toInt()} d=$density " +
            "r=${corners.joinToString("/") { it.toInt().toString() }}${if (cornersReal) "" else "(估)"} " +
            "cutouts=${cutouts.size})"

    companion object {
        /** 系统没给圆角时按短边的这个比例估（与改造前预览的观感一致）。 */
        const val FALLBACK_CORNER_RATIO = 0.07f

        /**
         * 组装并清洗：尺寸非有限或 ≤0 → `null`；圆角任何一角缺失（`null`）就整体用估值，
         * 单角夹到 `[0, 短边/2]`；挖孔丢掉空矩形，并夹进屏幕范围。
         *
         * @param corners 系统给的四角半径（左上、右上、右下、左下），`null` 表示这一角读不到。
         */
        fun of(
            width: Float,
            height: Float,
            density: Float,
            corners: List<Float?>,
            cutouts: List<ScreenRect>,
        ): ScreenShape? {
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
            val half = minOf(width, height) / 2f
            val real = corners.size == 4 && corners.all { it != null && it.isFinite() && it >= 0f }
            val radii = if (real) {
                FloatArray(4) { corners[it]!!.coerceIn(0f, half) }
            } else {
                FloatArray(4) { minOf(width, height) * FALLBACK_CORNER_RATIO }
            }
            val holes = cutouts
                .map { ScreenRect(it.left.coerceIn(0f, width), it.top.coerceIn(0f, height), it.right.coerceIn(0f, width), it.bottom.coerceIn(0f, height)) }
                .filter { it.width > 0f && it.height > 0f }
            return ScreenShape(width, height, if (density.isFinite() && density > 0f) density else 1f, radii, holes, real)
        }
    }
}
