package io.github.kotoriinsky.tsuzuri.core

/**
 * 状态栏通知图标（`statusbar_icons`）的契约。实现见 hook 侧 `StatusBarIconsFeature`。
 *
 * ## 两件事都在 HyperOS 的 `NotificationIconContainer` 里
 *
 *  - **超级岛遮挡**：岛够宽时，岛左缘之后的通知图标被标成 `inIslandState = 10`，
 *    播「缩到 0.4、模糊 30、透明」的退场动画再移走；岛收起时反着播回来。
 *    真机 2026-11-25：摘要态 3 字的测试岛（约 540px）就会移走第二个图标。
 *    动画靠 Folme 的视图 tag 续接，tag 残留时回来的图标可能停在半缩半糊的样子。
 *    [ISLAND_INSTANT] 打开后：进出岛一律不播动画，直接消失 / 直接恢复原样。
 *  - **省略点**：图标放不下时，第一个溢出的图标画成小圆点（`visibleState = 1`）。
 *    [HIDE_OVERFLOW_DOT] 打开后改成直接隐藏（`visibleState = 2`）。
 *
 * 两个子项都在 hook 里现读，切换立即生效；总开关关闭时完全不碰。
 */
object StatusBarIcons {
    const val ISLAND_INSTANT = "island_instant"
    const val HIDE_OVERFLOW_DOT = "hide_overflow_dot"
}
