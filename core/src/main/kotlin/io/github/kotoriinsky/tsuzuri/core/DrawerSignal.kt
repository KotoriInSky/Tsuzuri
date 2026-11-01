package io.github.kotoriinsky.tsuzuri.core

/**
 * 「澎湃抽屉现在开着吗」这条信号：**跨进程契约** + **纯判定逻辑**。
 *
 * ## 为什么这条信号要绕到 system_server
 *
 * 产品需求：抽屉打开时不要弹侧滑索引。
 * 难点全在**判据**上：抽屉是 Flutter 渲染的，桌面进程里一个 dex 都没有
 * （反射 / hook 全是死路），而所有纯读取的外部信号在真机上都实测为零 ——
 * 抽屉不是独立 window、不是独立 SurfaceFlinger layer、不是另一个 task、Settings 里
 * 没有实时键、没有广播、17 个 provider 里唯一有「状态」味的那个查询返回 `No result found`。
 *
 * 唯一**确定性**的判据是 WMS 侧的窗口属性：抽屉打开时桌面把自己的
 * `WindowManager.LayoutParams.softInputMode` 从 `state=always_hidden adjust=nothing`
 * 改成 `adjust=resize`（抽屉底部有搜索框，要让输入法能顶起来）。真机 `dumpsys window` 对照：
 *
 * ```
 * 桌面态：mAttrs={... sim={state=always_hidden adjust=nothing} ...}
 * 抽屉态：mAttrs={... sim={adjust=resize}                     ...}
 * ```
 *
 * 这个属性**只有 WMS 认**（`AccessibilityWindowInfo` 与 `WindowInfo` 都不带它），
 * 所以观察点只能放在 system_server，判定结果再广播给 SystemUI 用。
 *
 * ## 这个文件里放什么
 *
 * 契约（action / 权限 / extra / 超时 / 键名）与**纯函数判定**。放在 `:core` 是因为：
 *  - 两侧（system_server 的观察器、SystemUI 的追踪器）必须用**同一份**判据，
 *    写两份必然漂移，而漂移的表现是「信号发了但没人认」—— 不报错，只是静默不生效；
 *  - `:core` 能脱离 Android 跑单元测试，而这里的每个判据都有明确的错法
 *    （漏判 = 抽屉里照弹；误判 = 桌面上永远不弹），必须钉住。
 */
object DrawerSignal {

    /**
     * 广播 action。**system_server 发、SystemUI 收。**
     *
     * 为什么不走 remote prefs（`getRemotePreferences`）：那是**用户配置**的通道
     * （`Keys.GROUP` 的语义就是配置），把「抽屉现在开着」这种运行时状态写进去会
     * ① 落盘持久化；② 与用户配置混在同一份存储里；③ 进程重启后残留一个 stale 的
     * `true`（上一次抽屉开着时被杀 → 重启后索引在桌面上被永久抑制）。
     * 而广播是一条两侧都能打日志、失败能当场定位的通道。
     *
     * 为什么不往 `/data/adb` 写文件：实测那里是 `0700 root`，SystemUI 读不到，
     * 会**静默**失败。
     */
    const val ACTION = "io.github.kotoriinsky.tsuzuri.action.LAUNCHER_DRAWER"

    /**
     * 接收方注册时要求的权限。
     *
     * **必须有它**：SystemUI 在这台机器上跑在自己的 shared user 里
     * （设备实测 `sharedUser=android.uid.systemui/10226`，进程 `Uid: 10226`，
     * 而 system_server 是 1000），所以 system_server 发的广播跨 uid ——
     * 接收方只能注册成 `RECEIVER_EXPORTED`。而导出的接收器如果不要权限，
     * 任何第三方应用都能发一条 `open=true` 把侧滑索引永久抑制掉。
     * `DUMP` 是 `signature|privileged|development`，第三方拿不到、system_server 无条件持有。
     */
    const val PERMISSION = "android.permission.DUMP"

    /** SystemUI 的包名（广播只投递给它，不用 action 做全系统广播）。 */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 抽屉是否打开。**这是唯一的判据输出**，其余 extra 只用于诊断。 */
    const val EXTRA_OPEN = "open"

    /** 信号序号（每发一条 +1）。用来在日志上区分「重复信号」与「通道卡住」。 */
    const val EXTRA_SEQ = "seq"

    /** 观察器到今天为止见过多少次 `relayoutWindow`（hook 的性能开销就是它，见报告）。 */
    const val EXTRA_RELAYOUTS = "relayouts"

    /** true = 这条是心跳（状态没变，只是告诉对方「通道还活着」）。 */
    const val EXTRA_HEARTBEAT = "heartbeat"

    /**
     * 心跳间隔。
     *
     * 为什么需要心跳：状态只在**变化**时才有广播，而「抽屉开着」可能持续很久
     * （用户在抽屉里翻）。没有心跳时接收端区分不了「抽屉一直开着」和「通道已经死了」，
     * 而这两种情况的正确行为正好相反（抑制 / 回到今天的行为）。15s 一条
     * 定向后台广播的代价可以忽略。
     */
    const val HEARTBEAT_MS = 15_000L

    /**
     * 多久收不到信号就认为通道死了。
     *
     * 取 3 倍心跳（允许丢两条）。超时后接收端判「**未知**」= 不抑制，也就是回到
     * **未安装模块时的行为** —— 这是本条的硬要求：优雅降级的方向必须是「照今天一样弹」，
     * 绝不能是「索引整个不工作」。
     *
     * 时钟用 `uptimeMillis`（不计深睡）而不是 `elapsedRealtime`：深睡期间观察器和
     * 接收器一起停，系统状态不会变，醒来后上一次的判定仍然有效，不该因为
     * 「睡了 8 小时」就被判过期。
     */
    const val STALE_MS = HEARTBEAT_MS * 3

    /** 门控开关的键后缀（完整键 = `Keys.pref(<特性>, 它)`）。 */
    const val DRAWER_GATE_PREF = "drawer_gate"

    /**
     * 键**没在 `Catalog` 里声明**时的默认值。
     *
     * 为什么默认是 `true`：这条就是这个需求本身（抽屉里不弹），
     * 「没声明」不等于「用户不要」。而且它的失效方向是安全的 ——
     * 通道拿不到信号时判据恒为「未知 → 不抑制」，也就是回到今天的行为，
     * 不会把侧滑索引整个禁掉。
     *
     * 声明落地后这个常量只在「声明被误删」时才生效，两侧读到它都会把
     * `(未声明,用默认值)` 打进安装报告 —— 静默用默认值是这个项目点名要避免的偏差。
     */
    const val DEFAULT_DRAWER_GATE = true

    // ── softInputMode 判定 ──────────────────────────────────────────────
    //
    // 下面三个常量逐字对应 `android.view.WindowManager.LayoutParams` 的公开 SDK 常量。
    // 这里重新声明是因为 `:core` **不允许** import android.*（纯 JVM 模块，要能跑单测）。
    // 它们不是「抄来的魔数」：值就是 AOSP 的公开常量值，改这几行等于改契约。

    /** `WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST`。 */
    const val SOFT_INPUT_MASK_ADJUST = 0x00f0

    /** `WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE`。抽屉态实测的 adjust 位。 */
    const val SOFT_INPUT_ADJUST_RESIZE = 0x0010

    /** `WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING`。桌面态实测值的 adjust 位。 */
    const val SOFT_INPUT_ADJUST_NOTHING = 0x0030

    /** `WindowManager.LayoutParams.TYPE_BASE_APPLICATION`。Activity 主窗口。 */
    const val TYPE_BASE_APPLICATION = 1

    /**
     * 这个 `softInputMode` 是不是「抽屉开着」。
     *
     * 只看 adjust 位：抽屉态实测 `adjust=resize`（`0x10`），桌面态实测
     * `state=always_hidden adjust=nothing`（`0x33` → adjust 位 `0x30`）。
     *
     * 为什么不写成「等于某个精确值」：`state` 位会被别的路径改（例如
     * `SOFT_INPUT_STATE_VISIBLE`），而我们要的是「这个窗口准备让输入法把它顶起来」
     * 这件事 —— adjust 位才是它的语义。
     */
    fun isDrawerOpen(softInputMode: Int): Boolean =
        (softInputMode and SOFT_INPUT_MASK_ADJUST) == SOFT_INPUT_ADJUST_RESIZE

    /**
     * 这个窗口是不是**桌面自己的 Activity 主窗口**。
     *
     * ## title 是硬条件，packageName 是软校验（顺序与松紧都是被证据逼出来的）
     *
     * `title` 必须以 [titleMarker]（`"<homePackage>/"`）开头 —— 这是**唯一**能把
     * 桌面主窗口和那个同名同 type 的 overlay 区分开的判据（见下）。
     *
     * `packageName` 只在**非空且与桌面包名不符**时才否决。为什么不把它当硬条件：
     * 它是 `WindowManager.LayoutParams` 的字段，客户端不一定会填（可能由 WMS 在
     * addWindow 时补）。真机上它确实有值 —— 依据是本机 `framework.jar` 里
     * `WindowManager$LayoutParams.copyFrom` 的字节码：`packageName` 与 `mTitle`
     * 都是**无条件**从来源拷贝的（`iget-object` 源 → `iput-object` 目标，只加了一层
     * 「值变了才拷」的判断），而 `dumpsys window windows` 打印的 `mAttrs` 正是
     * `relayoutWindow` 参数 copyFrom 之后的结果，里面 `pkg = com.miui.home`
     * —— 也就是说客户端每次都把 packageName 传过来了。
     * 但把「必须非空」写成硬条件，会在某个 ROM 上因为一个字段没填而让整条信号
     * **静默死亡**；写成软校验则最坏退化成「只按 title 判」，而 title 已经是强证据。
     *
     * ## 为什么不能只看「包名 + type」
     *
     * 桌面进程实测有 **6 个**带 `pkg = com.miui.home` 的窗口（`GestureStubLeft/Right`、
     * `AntiMistakeTouchView`、`MAGNIFICATION_OVERLAY`×2、主窗口），所以 type 必须看；
     * 而 `Window #13 LauncherOverlayWindow:com.android.quicksearchbox`
     * （owner uid 10140，quicksearchbox 进程）的 `LayoutParams` 也是
     * `pkg = com.miui.home ty=BASE_APPLICATION` —— 只按「包名 + type」过滤会把它算进来，
     * 而它是**另一个窗口**，它的 `softInputMode` 未必跟抽屉一起变，那会产生假信号。
     * 真正的主窗口 title 是 `com.miui.home/com.miui.home.launcher.Launcher`
     * （`dumpsys` 里 `Window{...}` 打印的就是 `mAttrs.getTitle()`），
     * 而 overlay 的 title 形如 `LauncherOverlayWindow:com.android.personalassistant`，
     * 前缀对不上。
     *
     * 两个字符串参数都由调用方**预先算好**：这个函数在被 hook 的热路径上被调用
     * （`relayoutWindow` 是系统里最热的几个方法之一），这里不许拼字符串、不许分配。
     */
    fun isLauncherMainWindow(
        packageName: String?,
        type: Int,
        title: String?,
        homePackage: String,
        titleMarker: String,
    ): Boolean {
        // ① int 比较：系统里 type == TYPE_BASE_APPLICATION 的窗口只有几十个，
        //    绝大多数调用在这一行就返回了
        if (type != TYPE_BASE_APPLICATION) return false
        // ② 硬条件：String.startsWith 不分配，且这是区分主窗口与 overlay 的唯一判据
        if (title == null || !title.startsWith(titleMarker)) return false
        // ③ 软校验：只在客户端确实填了、且填得不一样时否决（String.equals 先比引用）
        if (packageName != null && packageName != homePackage) return false
        return true
    }

    /** 由桌面包名拼出 title 标记。**只在安装时调用一次**（热路径上不许拼字符串）。 */
    fun titleMarker(homePackage: String): String = homePackage + "/"

    /**
     * 上一次信号还算不算「新鲜」。
     *
     * `lastSignalUptimeMs <= 0` = **从来没收到过任何信号**（观察器没装上 / 接收器注册失败 /
     * 广播被拦）→ 一律判不新鲜 → 调用方不抑制 → 行为与未安装模块完全一致。
     * 这条是「优雅降级的硬要求」在纯函数这一层的落点。
     */
    fun isSignalFresh(
        lastSignalUptimeMs: Long,
        nowUptimeMs: Long,
        staleMs: Long = STALE_MS,
    ): Boolean {
        if (lastSignalUptimeMs <= 0L) return false
        // 时钟倒退（理论上只有重启，但便宜的防御）按不新鲜处理 —— 方向更安全
        if (nowUptimeMs < lastSignalUptimeMs) return false
        return nowUptimeMs - lastSignalUptimeMs <= staleMs
    }

    /**
     * 通道状态 → 给安装报告 / 日志看的**一个**字符串。
     *
     * 为什么必须出现在安装报告里：这条信号失效的表现是**静默**的（抽屉里又弹出来了，
     * 日志里一条都没有）。报告里那个 `drawer=` 字段就是「这次到底有没有信号」的唯一
     * 可见处 —— 与 `recent_store=` 同一个理由。
     *
     * @param registerNote 非 null = 接收器注册就失败了（通道根本没建立），内容是要报的原因
     */
    fun describe(
        lastSignalUptimeMs: Long,
        nowUptimeMs: Long,
        open: Boolean,
        signalCount: Int,
        registerNote: String?,
    ): String {
        if (registerNote != null) return "drawer=unavailable($registerNote)"
        if (!isSignalFresh(lastSignalUptimeMs, nowUptimeMs)) {
            return if (signalCount == 0) "drawer=unknown(未收到信号)" else "drawer=unknown(通道超时)"
        }
        return (if (open) "drawer=open(" else "drawer=closed(") + "已收到${signalCount}条)"
    }
}
