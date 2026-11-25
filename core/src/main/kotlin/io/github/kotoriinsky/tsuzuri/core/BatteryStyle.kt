package io.github.kotoriinsky.tsuzuri.core

/**
 * 状态栏电池样式（`battery_style`）的契约。实现见 hook 侧 `BatteryStyleFeature`。
 *
 * 换掉 HyperOS 的 `MiuiBatteryMeterView` 的画法：自绘的电池挂在它的 overlay 上，原有子视图
 * 排成 0×0（不画），高度沿用宿主测量结果；颜色取宿主百分比文字的当前颜色，所以深浅色
 * 跟随状态栏。关掉（或选「系统」）就摘掉 overlay、重新排版，宿主原样接管。
 *
 * ⚠️ 与其他改电池的模块（如 `btm.m.os4.systemuihook` 的电量百分比选项）同时开会互相覆盖。
 */
object BatteryStyle {
    const val STYLE = "style"
    const val COLORED = "colored"

    /** 样式线上值。 */
    const val NUMBER = "number"
    const val RING = "ring"
    const val VERTICAL = "vertical"
    const val HORIZONTAL = "horizontal"

    val STYLES = listOf(NUMBER, RING, VERTICAL, HORIZONTAL)

    /** 低电量阈值（含）：≤20 且未充电时，彩色状态下标红。 */
    const val LOW_LEVEL = 20

    fun isLow(level: Int, charging: Boolean) = !charging && level in 0..LOW_LEVEL
}
