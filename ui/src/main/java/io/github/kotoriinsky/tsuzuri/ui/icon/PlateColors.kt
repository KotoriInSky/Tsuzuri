package io.github.kotoriinsky.tsuzuri.ui.icon

import androidx.compose.ui.graphics.Color

/**
 * 色块图标的底色。
 *
 * 前四个从 HyperOS 4 设置首页截图逐像素取色（Xiaomi 17 Pro，2026-10-21，
 * WLAN / 移动网络 / 我的设备 / 妙享背屏 的色块中段）；其余是按同一明度、饱和度
 * 补齐的色相，**待真机补测**（设置首页往下滚还有橙、红、青等色块）。
 *
 * 规则：同一张卡里相邻两行不用同一种颜色 —— HyperOS 靠颜色差扫读。
 */
object PlateColors {
    val Blue = Color(0xFF4A80F7)
    val Green = Color(0xFF61CA51)
    val Gray = Color(0xFF8F9CAE)
    val Purple = Color(0xFF7468F0)

    // 以下待真机补测
    val Orange = Color(0xFFFF8A3D)
    val Red = Color(0xFFF2564A)
    val Teal = Color(0xFF2BB9C8)
    val Indigo = Color(0xFF5A6CF0)
}
