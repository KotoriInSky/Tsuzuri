package io.github.kotoriinsky.tsuzuri.core

/**
 * 截图 / 录屏「完美状态栏」的契约（实现见 hook 侧 `CleanStatusBarFeature`，快捷开关见界面侧 `CleanStatusBarTile`）。
 *
 * ## 做法
 *
 * 借系统**演示模式**把时间、电量、信号换成固定的「完美」值；它在澎湃 4 上唯独**不藏通知图标**——
 * `DemoMode.COMMANDS` 里还有 `notifications`，但 `CentralSurfacesImpl.dispatchDemoCommand` 是空方法，
 * 没有任何接收方。所以演示模式期间由模块另用自己的 token 写一条状态栏 disable 记录
 * （`DISABLE_NOTIFICATION_ICONS = 0x20000`），退出时只清这一条。
 *
 * 进入演示模式靠 SystemUI 自己注册的 `com.android.systemui.demo` 广播（接收器没挂权限）；
 * 系统设置 `sysui_demo_allowed` 为 0 时系统不受理，模块**不替用户改**这个全局设置。
 */
object CleanStatusBar {

    /** 快捷开关 → SystemUI：进入 / 退出完美状态栏。接收端按发送方 uid 校验是不是本模块。 */
    const val ACTION = "io.github.kotoriinsky.tsuzuri.action.CLEAN_STATUSBAR"
    const val EXTRA_ON = "on"

    /**
     * 请求来源。`screenshot` = system_server 截图前发来的「先换好状态栏」：SystemUI 进入后
     * [SCREENSHOT_HOLD_MS] 自动退出（只退它自己开的那次）；没有这个 extra = 快捷开关。
     */
    const val EXTRA_REASON = "reason"
    const val REASON_SCREENSHOT = "screenshot"

    /** 截图请求放行前等多久：演示命令在 SystemUI 主线程上一次跑完，再留一两帧给状态栏重绘。 */
    const val SCREENSHOT_DELAY_MS = 300L

    /** 截图后多久恢复：截图服务拿到请求后立刻截，2 秒足够覆盖截图动画开始前的那一帧。 */
    const val SCREENSHOT_HOLD_MS = 2000L

    /** SystemUI 的演示模式广播（`DemoModeController$broadcastReceiver$1`）。 */
    const val DEMO_ACTION = "com.android.systemui.demo"

    /** 状态栏 disable1 位：通知图标（本机 `DisableFlagsLoggerKt` 实测）。 */
    const val DISABLE_NOTIFICATION_ICONS = 0x20000

    /** 子项名（`clean_statusbar.<name>`）。 */
    const val HIDE_NOTIFICATION_ICONS = "hide_notification_icons"
    const val AUTO_RECORDING = "auto_recording"
    const val AUTO_SCREENSHOT = "auto_screenshot"
    const val HIDE_ISLAND = "hide_island"

    /**
     * 超级岛所在窗口的标题（`dumpsys window windows` 实测 `Window{… DynamicIslandWindow}`）。
     * 岛的内容由小米插件画，但窗口挂在 SystemUI 进程里；完美状态栏期间把这个窗口的根视图设成 INVISIBLE。
     */
    const val ISLAND_WINDOW_TITLE = "DynamicIslandWindow"

    /** 预览与演示模式里用的固定时间。 */
    const val DEMO_CLOCK = "0816"

    /**
     * 进入演示模式时依次发出的命令（`command` + 其余 extra）。顺序就是发送顺序：先 `enter`。
     *
     * 只给出「满格、满电、无杂项」这一组；值都是 AOSP 演示模式公开的命令格式。
     */
    val enterCommands: List<Map<String, String>> = listOf(
        mapOf("command" to "enter"),
        mapOf("command" to "clock", "hhmm" to DEMO_CLOCK),
        mapOf("command" to "battery", "level" to "100", "plugged" to "false", "powersave" to "false"),
        mapOf("command" to "network", "wifi" to "show", "level" to "4", "fully" to "true"),
        mapOf("command" to "network", "mobile" to "show", "level" to "4", "datatype" to "none", "fully" to "true"),
        mapOf("command" to "network", "airplane" to "hide"),
        mapOf("command" to "notifications", "visible" to "false"),
    )

    val exitCommands: List<Map<String, String>> = listOf(mapOf("command" to "exit"))
}
