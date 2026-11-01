package io.github.kotoriinsky.tsuzuri.core

/**
 * 常用活动的**预置清单** —— 「写好的内容」，用户点一下就能填进输入框。
 *
 * ## 为什么要有这个东西
 *
 * 活动全名是**应用自己定的**（`com.tencent.mm/.plugin.scanner.ui.BaseScanUI`），
 * 既查不到（未导出的活动对外不可见）也猜不出
 * （类名前缀跟包名可以完全不同，小爱就是活例子）。
 * 让每个用户自己去翻 apk 是不现实的，所以把**已知可用的**列在这里。
 *
 * ## ⚠️ 收录纪律（这是本文件最重要的部分）
 *
 * **每一条都必须是真机上实际验证过的**，不能凭印象写。
 * 这条纪律不是形式主义 —— 本轮就踩了：凭印象写的
 * `com.eg.android.AlipayGphone/.scan.ui.activity.ScanActivity` 实测
 * `Error: Activity class … does not exist`，而支付宝的扫码根本不是活动
 * （它是 `ScanWidgetProvider` 桌面小组件 + 快捷方式）。**猜一个类名的代价
 * 是用户点了没反应**，而这正是这个特性要消灭的那类体验。
 *
 * 验证方式（在电脑上跑，`adb shell` 会直接告诉你）：
 *
 * ```
 * adb shell am start -W -n '包名/活动全名'
 * ```
 *
 * - `Error: Activity class … does not exist` → **不能收录**；
 * - `Status: ok`（带 `LaunchState`）→ 组件存在，可收录。
 *
 * ⚠️ 但 `Status: ok` **不等于点了就好使** —— 有些页面需要前置状态
 * （小爱识屏要有截图来源）。
 * 这类页面**照样可以收录**，因为用户可能就是想试；但**不要在文案里承诺
 * 「一定能开」**。本清单只保证「这个组件存在」。
 *
 * ## 类名漂移（已知代价，接受）
 *
 * 应用升级可能改类名，届时这一条会失效（点了没反应，模块日志里是
 * 「组件不存在」）。**这是可接受的**：预置项只是**填进输入框**的起点，
 * 用户看到填进去的串、也能自己改。**不做版本探测、不做可信度标记** ——
 * 等收到具体反馈再逐条更新，比现在猜「哪条可能过期」更可靠。
 *
 * ## 为什么放在 core
 *
 * 纯数据 + 纯字符串逻辑，不依赖 `android.*`，所以能在 JVM 上单测：
 * **每一条都必须过 [ActivityTargets.check]**（写错一个字符的预置项
 * 等于把一个 bug 塞进用户输入框）。这条由 `ActivityPresetsTest` 钉住。
 */
object ActivityPresets {

    /**
     * 一条预置活动。
     *
     * [target] 是**裸的「包名/活动全名」**（不带 `activity:` 前缀）——
     * 与用户手填的形式一致，填进输入框后走的是同一条校验、同一个写入路径。
     * 前缀由 [ActivityTargets.stored] 在写入时补，预置项不该关心存储形式。
     *
     * [labelKey] 是 UI 层的字符串资源键（`core` 不持有文案，与
     * `Catalog` 里 `PrefSpec` 用 labelKey 同一个理由）。
     */
    class Preset(val target: String, val labelKey: String) {
        /** 包名（校验已保证有 `/`）。 */
        val packageName: String get() = target.substringBefore(ShortcutEntries.SEP)
    }

    /**
     * 预置项本体。
     *
     * 顺序 = 界面上的显示顺序。**把最常用的放前面**（用户不用横向滚太远）。
     *
     * 文案键一律用 `activity_import.preset.*` 前缀，与
     * `link_import.preset.*`（`LinkImportScreen`）同构。
     */
    val ALL: List<Preset> = listOf(
        // 微信扫一扫：与链接项的 `weixin://scanqrcode` 指向同一个功能，
        // 但走活动直达（少一层 scheme 分发）。实测 `LaunchState: HOT`。
        Preset(
            "com.tencent.mm/.plugin.scanner.ui.BaseScanUI",
            "activity_import.preset.wx_scan",
        ),
        // 微信主界面。实测 `UNKNOWN` —— 注意这一个**不代表组件不存在**：
        // 微信主界面往往已在栈里，AMS 只是把它提到前台，所以不打 `LaunchState`。
        // （`UNKNOWN` 的含义：它**不是**「打不开」的判据。）
        Preset(
            "com.tencent.mm/.ui.LauncherUI",
            "activity_import.preset.wx_main",
        ),
        // 小爱识屏。实测组件存在，但**裸启动会报「屏幕截图获取失败」** ——
        // 它需要 `db.startScreenRecognition` 提供截图。
        // 照样收录：用户想知道的正是「怎么用扇形打开识屏」，把它填进去看到
        // 真实反馈，比在文档里解释半天更直接。后续若接了广播改道，这一条会自然生效。
        Preset(
            "com.miui.voiceassist/com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
            "activity_import.preset.xiaoai_ocr",
        ),
        // 系统设置。实测 `WARM`。放它是因为「设置里的某个二级页」是最高频的
        // 「想直达但只能点到应用首页」场景，用它当示例最直观。
        Preset(
            "com.android.settings/.Settings",
            "activity_import.preset.settings",
        ),
        // 计算器。实测 `COLD`。选它是因为**简单且零依赖** ——
        // 用户第一次试这个功能时，填一条「点了就真的开」的最能建立信任，
        // 而小爱识屏那种会报错的适合放在后面（用户已经理解了机制再看）。
        Preset(
            "com.miui.calculator/.cal.CalculatorActivity",
            "activity_import.preset.calculator",
        ),
    )

    /** [labelKey] → 目标串。UI 点一下按钮时用。 */
    fun targetOf(labelKey: String): String? =
        ALL.firstOrNull { it.labelKey == labelKey }?.target
}
