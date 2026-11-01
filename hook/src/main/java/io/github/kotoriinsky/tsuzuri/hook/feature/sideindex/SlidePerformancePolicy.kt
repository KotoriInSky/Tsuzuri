package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.os.Handler
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.OverlayArbiter
import android.os.Looper

/**
 * 输入分发线程 → 主线程的请求桥，外加振动节流。
 *
 * ## 为什么必须有这个类
 *
 * [SideIndexDetector.onTouch] 跑在**输入分发线程**上，而浮层更新
 * （`WindowManager.addView/removeViewImmediate`、`TextView.setText`、
 * 动画）**只能在主线程**。从输入线程直接碰 UI 会把整棵 View 树挂到那个线程上，
 * 之后主线程的任何交互都会出问题 —— 而且这类错往往延迟暴露、极难定位。
 *
 * 所以输入线程只做两件事：
 *  1. 写一个 `@Volatile` 字段（"我要第 N 个字母"/"手指在 (x,y)"/"抬手了"）；
 *  2. 保证**至多 post 一个**可复用的 Runnable。
 * 主线程的 Handler 消费它，读最新的字段值。
 *
 * ## 为什么可以只 post 一次（去重）
 *
 * 手指快速划过字母条时一秒能产生上百个 MOVE。每个都 post 一个 Runnable，
 * 主线程队列里就会堆几十个「渲染字母 A→B→C→…」，全是白做的。
 * Appindex 的解法是 `SlidePerformancePolicy.requestLetter/consumePendingLetter`
 * 这种「只记最新、谁先用谁清空」的槽位；我们把「谁去 post」也收进同一个类，
 * 于是去重规则只有一条，不会两处各写一半。
 *
 * 去重的安全性来自**单写者纪律**：
 *  - `letterScheduled` 只有输入线程写 `true`，只有主线程写 `false`；
 *  - 两边都不做「读-改-写」，所以不存在丢更新。
 * 最坏情况是某一帧的字母渲染被跳过（手指下一帧就会再送一次，自愈），
 * 绝不会出现「排下了但永远不渲染」。
 *
 * 这里**零分配**：Runnable 复用同一个实例，Message 走系统池，
 * 字段全是基本类型。这条是规范 §7.4 对输入线程的硬要求。
 *
 * ## 振动节流
 *
 * [shouldEmitHaptic] / [HAPTIC_MIN_INTERVAL_MS] 的**数值**沿用参考实现
 * （Appindex 的 `FloatingService.vibrateShort`），但保留它的理由不是「照抄」：
 * 手指划过时一秒上百次 MOVE，每次都发一次振动 = 马达长鸣，而不是「一格一响」。
 * 节流与 Android 触觉规范**不冲突** —— 规范管的是「用哪个 API 表达哪个语义」，
 * 不管「同一语义最多多久发一次」。档位与语义常量在 [SideIndexHaptics] 里，
 * 实际发振动由主线程侧的 [SideIndexOverlay.tick] 执行。
 */
internal class SlidePerformancePolicy(
    private val onLetter: (Int, Boolean) -> Unit,
    private val onPointer: (Float, Float, Boolean) -> Unit,
    private val onCommit: (Float, Float, Boolean) -> Unit,
    private val onCancel: () -> Unit,
    /**
     * 手势开始/结束。**在主线程回调**。
     *
     * 用途只有一个：让常驻的边缘把手在手势进行中消失（Appindex 的
     * `applyTriggerVisibility` / 浮窗盖住触发条是同一个效果）。把手是 View，
     * 所以它只能由主线程改可见性 —— 这条和别的请求一样必须过这座桥。
     */
    private val onGesture: (Boolean) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /** 本次手势贴在哪一侧。手势开始时由输入线程写一次。 */
    @Volatile
    private var onRight = false

    /**
     * 是否有手势正在进行。
     *
     * 输入线程写、主线程读。用途只有一个：「最近使用」列表变化时不要在手势中途
     * 换掉字母表 —— 输入线程在做手势开始时已经把字母表抄进了自己的几何里，
     * 中途换掉就会出现「看到的条」和「碰到的条」不是同一根。
     */
    @Volatile
    var gestureActive: Boolean = false
        private set

    @Volatile
    private var pendingLetter = -1
    private var letterScheduled = false

    @Volatile
    private var pendingPointer = false
    @Volatile
    private var pointerX = 0f
    @Volatile
    private var pointerY = 0f
    private var pointerScheduled = false

    @Volatile
    private var commitX = 0f
    @Volatile
    private var commitY = 0f

    /**
     * 手势状态的最新值，等主线程来取。
     *
     * 和字母槽位同样的合并规则：一次「按下-抬起」如果短到两帧之内，主线程只会收到
     * 「结束了」—— 那是**正确的终态**（把手回到它该在的地方），中间那一帧的隐藏没有意义。
     */
    @Volatile
    private var pendingGestureActive = false
    private var gestureScheduled = false

    // ── 输入线程 ──────────────────────────────────────────────────────

    /** 手势成立。之后所有请求都按这一侧处理。 */
    fun startGesture(onRight: Boolean) {
        this.onRight = onRight
        gestureActive = true
        // 告诉扇形「索引在接管」：它武装/提交前会查（见 OverlayArbiter）
        OverlayArbiter.indexShowing = true
        scheduleGesture(true)
    }

    /** 手势结束（抬手/取消）。 */
    fun endGesture() {
        gestureActive = false
        OverlayArbiter.indexShowing = false
        scheduleGesture(false)
    }

    private fun scheduleGesture(active: Boolean) {
        pendingGestureActive = active
        if (!gestureScheduled) {
            gestureScheduled = true
            main.post(gestureTick)
        }
    }

    /** 请求渲染第 [slot] 个字母。 */
    fun requestLetter(slot: Int) {
        pendingLetter = slot
        if (!letterScheduled) {
            letterScheduled = true
            // 和 Appindex 一样延后约一帧：同一帧内的多次切换只渲染最后一次，
            // 也避免在一个绘制帧中途插进去重建视图
            main.postDelayed(letterTick, LETTER_TICK_MS)
        }
    }

    /** 请求更新手指位置（只用于高亮）。 */
    fun requestPointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        pendingPointer = true
        if (!pointerScheduled) {
            pointerScheduled = true
            main.post(pointerTick)
        }
    }

    /** 抬手：由主线程做命中测试并启动。 */
    fun requestCommit(x: Float, y: Float) {
        commitX = x
        commitY = y
        dropPendingRender()
        main.post(commitTick)
    }

    /** 手势被取消（系统抢走了 / 异常）。 */
    fun requestCancel() {
        dropPendingRender()
        main.post(cancelTick)
    }

    /**
     * 手势结束的那一刻，把**欠着的渲染请求作废**。
     *
     * ## 为什么必须在这里作废（2026-09-15，用户报的「图标飘到状态栏」）
     *
     * [requestLetter] 是**延后 [LETTER_TICK_MS] 才渲染**的，而抬手这条路上
     * `commit → dismiss()` 是立刻执行的。抬手若落在那个 16ms 窗口里，就会出现
     * 「窗口已经拆了、渲染才到」：浮层把窗口重新建出来，而位置用的是已经被
     * `dismiss()` 清成 0 的 `fingerY` → 面板被摆到**屏幕顶端**（压着状态栏），
     * 一直留到下一次手势结束。
     *
     * 作废的时机必须在**输入线程**（抬手那一帧），不能等主线程的 commit/cancel 任务：
     * 那个任务和延迟渲染在同一条队列上排队，谁先跑取决于 `when`，而这里要的是
     * 「无论谁先跑，渲染都不该再发生」。`pendingLetter` / `pendingPointer` 都是
     * volatile，从这里写是安全的。
     *
     * ⚠️ 只作废**渲染**：`commit` 本身仍然要跑（抬手命中要启动应用），
     * 所以这里不动 `commitX/commitY`，也不摘 `commitTick`。
     */
    private fun dropPendingRender() {
        // 诊断：抬手时**确实**有欠着的渲染，说明这条竞态真的撞上了。
        // 为什么需要它：修好之后的表现是「什么都没发生」，而「什么都没发生」既可能是修好了、
        // 也可能是这次没撞上 —— 有了这一行，`tools/repro-side-index-zombie.sh` 才能确定地判
        // 「竞态发生了，且被挡住了」。限次的原因同 SideIndexOverlay.LATE_RENDER_LOG_MAX。
        if (pendingLetter >= 0 && droppedAtUp < 20) {
            droppedAtUp++
            android.util.Log.i("FP-SideIndex", "抬手作废了欠着的渲染（共 " + droppedAtUp + " 次，这就是那条竞态）")
        }
        pendingLetter = -1
        pendingPointer = false
    }

    /** [dropPendingRender] 的日志计数（只在输入线程读写）。 */
    private var droppedAtUp = 0

    /**
     * 卸载时清空队列。可以从任何线程调（框架的卸载跑在框架线程上）。
     *
     * `removeCallbacks` 只能摘掉还没跑的；已经在跑的那个之后会被
     * 浮层自己的 `released` 闸门挡掉。
     */
    fun stop() {
        gestureActive = false
        letterScheduled = false
        pointerScheduled = false
        gestureScheduled = false
        pendingLetter = -1
        pendingPointer = false
        main.removeCallbacks(letterTick)
        main.removeCallbacks(pointerTick)
        main.removeCallbacks(commitTick)
        main.removeCallbacks(cancelTick)
        main.removeCallbacks(gestureTick)
    }

    // ── 主线程 ────────────────────────────────────────────────────────

    private val letterTick = Runnable {
        letterScheduled = false
        val slot = pendingLetter
        pendingLetter = -1
        if (slot >= 0) onLetter(slot, onRight)
    }

    private val pointerTick = Runnable {
        pointerScheduled = false
        if (!pendingPointer) return@Runnable
        pendingPointer = false
        onPointer(pointerX, pointerY, onRight)
    }

    private val commitTick = Runnable { onCommit(commitX, commitY, onRight) }

    private val cancelTick = Runnable { onCancel() }

    private val gestureTick = Runnable {
        gestureScheduled = false
        onGesture(pendingGestureActive)
    }

    companion object {
        /** 字母渲染的合并窗口，取自 Appindex 的 `postDelayed(..., 16L)`。 */
        private const val LETTER_TICK_MS = 16L

        /**
         * 振动最小间隔。
         *
         * 数值沿用参考实现（Appindex 的 `SlidePerformancePolicy.HAPTIC_MIN_INTERVAL_MS`），
         * 但保留它的理由是**行为**上的，不是「照抄」：手指划过字母条时一秒上百个 MOVE，
         * 每个都发一次振动就变成马达长鸣，而不是「一格一响」。
         * 这条节流与 Android 触觉规范不冲突 —— 规范约束「用哪个 API 表达哪个语义」，
         * 不约束「同一语义最多多久发一次」。
         */
        const val HAPTIC_MIN_INTERVAL_MS = 35L

        /**
         * 该不该发这一次振动。
         *
         * 规则（与重构前逐字相同，重构只换掉了「发什么」，没有换「发不发」）：
         * 强制（启动应用）一定发；否则距上一次不足 [HAPTIC_MIN_INTERVAL_MS] 就吞掉。
         * `nowMs < lastHapticAtMs` 那一条是防时钟回拨（`uptimeMillis` 理论上单调，
         * 但输入事件带的是 `eventTime`，两者不保证同源，留着更安全）。
         */
        fun shouldEmitHaptic(lastHapticAtMs: Long, nowMs: Long, force: Boolean): Boolean {
            if (force || lastHapticAtMs < 0L) return true
            if (nowMs < lastHapticAtMs) return false
            return nowMs - lastHapticAtMs >= HAPTIC_MIN_INTERVAL_MS
        }
    }
}

/**
 * 侧滑索引的触觉反馈策略：**「哪个交互 → 哪个档位 → 走哪条 API 路径」的全部规则**。
 *
 * ## 这一版为什么重做
 *
 * 上一版是照抄第三方模块 Appindex 的写法：把「强度百分比」换算成 `1..255` 的振幅，
 * 再 `VibrationEffect.createOneShot(时长, 振幅)` 自己拼一个波形。
 * 那**不是** Android 推荐的触觉反馈做法，Android 官方
 * [Add haptic feedback to events](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)
 * 把三种做法排了序，并明确推荐第一种：
 *
 *  1. **`View.performHapticFeedback(int)` + 常量**（官方首选）。三个关键性质：
 *     - **不需要 `VIBRATE` 权限**；
 *     - **自动尊重用户的触觉反馈设置**（系统设置 `HAPTIC_FEEDBACK_ENABLED`），
 *       被关掉时 `performHapticFeedback` 直接返回 false，我们什么都不用做；
 *     - **自带 fallback**：底层实现随设备不同，常量本身负责映射到该设备能做到的效果，
 *       **不需要我们自己探测设备支持**。官方明说：唯一要留意的只是「这个常量是哪个 API 级别加进来的」。
 *     还有一个更容易被忽略的点：**这些常量是按「交互的功能语义」定义的，不是按「振动效果类型」**
 *     —— 使用者只该考虑「用户在干什么」，让平台去决定那一刻该震成什么样。
 *  2. **预定义 `VibrationEffect`**（`createPredefined(EFFECT_TICK / EFFECT_CLICK / …)`）：
 *     需要更强的表达时用。官方要求这一条**必须**先验证设备能力，并避开 ERM 马达。
 *  3. **`VibrationEffect.Composition` + 原语**（`PRIMITIVE_TICK / PRIMITIVE_CLICK`，带 `scale`）：
 *     需要自己编排时用，同样必须先验证原语是否被支持。
 *
 * 官方还明确两点，正好否掉了上一版的做法：
 *  - **UI 反馈不要用长振动**；
 *  - **有预定义效果可用时，不要自己拼 duration + amplitude**。
 *
 * 所以上一版的 `haptic_strength`（百分比 → 振幅）与 `haptic_duration`（毫秒）
 * 在规范实现下**没有对应的 API**：`performHapticFeedback` 不吃振幅也不吃时长，
 * 预定义效果是固定波形，Composition 只有 `scale`（0..1）这一个力度旋钮，
 * 而且是**原语的**力度，不是「一次性的振幅百分比」。这两个滑块因此失去意义
 * （见 [Mode] 的档位设计说明与本次改动的报告）。
 *
 * ## 档位为什么是「表达力」而不是「强度」
 *
 * 规范不给「把同一次交互调强 30%」这种能力 —— 它给的是**三条表达力递增的路径**。
 * 所以新参数面的轴是「愿意走哪条路径」，不是「多大振幅」：
 *
 * | 档位 | 走哪条路 | 依据 |
 * | --- | --- | --- |
 * | [Mode.LIGHT] | 只用 `View` 常量 | 官方首选；零权限、尊重系统设置、自带 fallback |
 * | [Mode.STANDARD] | 预定义效果，能力不足回落 `View` 常量 | 官方第二条路径；**必须**先验证支持 |
 * | [Mode.STRONG] | `Composition` + 原语（`scale` 拉满），逐级回落 | 官方第三条路径；**必须**先验证原语与 `hasAmplitudeControl()` |
 *
 * ## 三个触发点各自的语义（按语义挑常量，不按手感挑）
 *
 * | 触发点 | 用户在干什么 | `HapticFeedbackConstants` | 语义理由 |
 * | --- | --- | --- | --- |
 * | [Trigger.LETTER] | 手指沿字母条移动，槽位换成另一个字母 | `CLOCK_TICK` | 官方语义是「按时钟的小时刻度」。字母条就是一根刻度条，用户是「压在某个刻度上」。`SEGMENT_TICK` 的语义偏「控件被移到新值」（滑杆拖动）、`TEXT_HANDLE_MOVE` 专指文本光标把手（这里没有任何文本），都不如它贴 |
 * | [Trigger.ITEM_FOCUS] | 手指移到另一个应用格，高亮落位 | `SEGMENT_TICK` | 官方语义是「离散取值控件被移到新值」。应用面板就是一列离散取值（每格一个值），高亮换格正是「移到新值」。注意 `SCROLL_ITEM_FOCUS/SCROLL_TICK` **不是公开 API**（见 [Constant] 的说明），不能用 |
 * | [Trigger.CONFIRM] | 抬手命中，启动这个应用 | `CONFIRM` | 官方语义是「交互确认/成功」，官方示例就是提交成功时发 `CONFIRM`。`REJECT` 明确不用：抬手落在字母条上是**什么都不启动**，那是「没有输入」，不是「输入被拒绝」 |
 *
 * ## 这个对象是纯的
 *
 * 不引用任何 `android.*` 类型 —— 平台的常量值由 [SideIndexOverlay] 用枚举映射过去
 * （见它的 `viewConstantValue` / `effectValue` / `primitiveValue`）。这样这一整套
 * 判据可以脱离设备被逐个用例验证，而不是埋在 SystemUI 的主线程里靠手感试。
 */
internal object SideIndexHaptics {

    // ── 触发点：按「用户在干什么」分类，不按「震一下」分类 ──────────────

    /**
     * 侧滑索引里会发触觉反馈的三个交互。三个都要保留（见本次改动报告）。
     *
     * 举起的时机（调用方）：
     *  - [LETTER]：[SideIndexOverlay.show] 里面板内容换掉之后；
     *  - [ITEM_FOCUS]：[SideIndexOverlay.setHighlight] 里高亮格子变了之后；
     *  - [CONFIRM]：[SideIndexOverlay.commit] 里启动成功之后（`force = true`，
     *    因为抬手那一下必须响，不能被 35ms 节流吃掉）。
     */
    internal enum class Trigger { LETTER, ITEM_FOCUS, CONFIRM }

    // ── 用户档位 ────────────────────────────────────────────────────────

    /**
     * 用户能选的档位。**轴是「表达力」**，不是「强度百分比」——
     * 理由见本对象顶部的说明。
     *
     * [OFF] 只是「把总开关表达在这一处」的等价写法：
     * [resolveMode] 里总开关（老键 `haptic`）的优先级更高，两者任一为关就是关。
     * 这样做的代价是「关」有两个入口，收益是用户不必去找另一个开关。
     */
    internal enum class Mode { OFF, LIGHT, STANDARD, STRONG }

    /**
     * "强"档的一次性振幅（255 满量程取 220，留一点余量避免个别马达打满时失真）。
     * 只在设备支持振幅控制（[DeviceSupport.hasAmplitudeControl]）时才会被用到。
     */
    const val STRONG_AMPLITUDE = 220

    /** "强"档的时长。**短**是刻意的：UI 反馈不该有长振动（规范原话）。 */
    const val STRONG_ONE_SHOT_MS = 25L

    // ── 规范里的三条路径 ────────────────────────────────────────────────

    /** 这一次触觉反馈实际该调哪个 API。 */
    internal enum class Path {
        /** 什么都不发。 */
        NONE,

        /** `View.performHapticFeedback(常量)` —— 官方首选路径。 */
        VIEW_CONSTANT,

        /** `Vibrator.vibrate(VibrationEffect.createPredefined(效果))`。 */
        PREDEFINED_EFFECT,

        /** `Vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(原语, scale).compose())`。 */
        COMPOSITION,

        /**
         * `Vibrator.vibrate(VibrationEffect.createOneShot(时长, 振幅))` —— **只有"强"档用**。
         *
         * ## 为什么必须留这一条（这是产品经理实测反馈逼出来的）
         *
         * 「轻 / 标准 / 强」三档原设计全部走语义路径，装机后发现**三档物理上是同一个波形**：
         *   - 标准 = 预定义 `EFFECT_TICK/CLICK`，AOSP 内部就是 `PRIMITIVE_TICK/CLICK` **默认力度**；
         *   - 强 = 同一个原语、`scale` 拉满 = 同一个波形；
         *   - 轻 = `CLOCK_TICK/SEGMENT_TICK`，AOSP 通常也映射到同一个 `EFFECT_TICK`。
         * 安装报告把实际路径打出来之后这件事是**可见的**（三档都是 `composition`），
         * 但"可见"不等于"能分辨"——**调语义常量调不出差别**。
         *
         * 所以"强"档改用振幅，这是唯一能让它**物理上更强**的手段。
         * ⚠️ 这**刻意偏离**了官方「有预定义效果时不要自己拼 duration+amplitude」那条建议：
         * 那条建议是为了避免劣化手感，而这里的目标正是"更重的反馈"，
         * 且它是**用户主动选强档**才生效、并已确认 `hasAmplitudeControl()`。
         * 没有振幅控制的设备**走不到这条路**（回落成原来的 Composition）。
         */
        ONE_SHOT,
    }

    /**
     * 要用的 `android.view.HapticFeedbackConstants` 常量。
     *
     * 这里放枚举而不是直接写平台的 int，有两个理由：
     *  1. 这个对象要保持纯 Kotlin（可脱离设备验证）；
     *  2. 平台常量到 int 的映射集中在 [SideIndexOverlay] 一个 `when` 里，
     *     改起来不会漏。
     *
     * 各个常量是哪个 API 级别加的（取自 SDK 37 的 `data/api-versions.xml`，
     * 本模块 `minSdk = 36`，所以三个都可用；列出来是因为官方明确说
     * **「用常量唯一需要留意的就是它的 API 级别」**）：
     *  - `CLOCK_TICK` since 21
     *  - `CONFIRM` since 30
     *  - `SEGMENT_TICK` since 34
     *
     * ⚠️ `SCROLL_TICK` / `SCROLL_ITEM_FOCUS` / `SCROLL_LIMIT` 在
     * `android-stubs-src.jar`（34/35/36/37.0 都查过）里**根本不存在** ——
     * 它们不是公开 API，对系统外代码不可见，所以**不能用**。
     */
    internal enum class Constant { CLOCK_TICK, SEGMENT_TICK, CONFIRM }

    /**
     * 要用的 `android.os.VibrationEffect` 预定义效果。
     *
     * `EFFECT_TICK` = 短促刻度，`EFFECT_CLICK` = 更明确的「咔」。两者都是系统自己的
     * 预定义波形：**不吃时长也不吃振幅**，这正是规范要的（不要自己拼 duration + amplitude）。
     */
    internal enum class Effect { TICK, CLICK }

    /** 要用的 `VibrationEffect.Composition` 原语。 */
    internal enum class Primitive { TICK, CLICK }

    /**
     * 一次触觉反馈的完整决定：走哪条路 + 那条路要带的参数。
     *
     * [primitiveScale] **只对 [Path.COMPOSITION] 有意义**（`addPrimitive` 的第二个参数，
     * 规范里取值 `0..1`）。它是本实现里唯一还与「力度」有关的数：
     * [Mode.STRONG] 刻意取原语允许的上限，让「强」这个档位真的强在力度上，
     * 而不是偷偷换一个语义不同的常量。
     */
    internal data class Plan(
        val path: Path,
        val constant: Constant = Constant.CLOCK_TICK,
        val effect: Effect = Effect.TICK,
        val primitive: Primitive = Primitive.TICK,
        val primitiveScale: Float = MAX_PRIMITIVE_SCALE,
        /** [Path.ONE_SHOT] 的振幅（1..255）。只有那条路会读它。 */
        val oneShotAmplitude: Int = 0,
        /** [Path.ONE_SHOT] 的时长（毫秒）。**短**是刻意的：UI 反馈不该有长振动。 */
        val oneShotMs: Long = 0L,
    ) {
        val emits: Boolean get() = path != Path.NONE
    }

    /**
     * 设备能力探测的结果，**一次安装探一次**（探测是 Binder 调用，不该在手指路径上做）。
     *
     * 为什么要这些：走 `View` 常量那条路「不需要探测」（官方原话是常量自带 fallback），
     * 但走预定义效果与 Composition 那两条路时，官方**明确要求**先验证支持情况，
     * 并验证 `hasAmplitudeControl()` 以避开 ERM 马达（那类马达没有振幅控制，
     * 给它的 `scale` 只会被劣化成「通/断」，听感比一句短 tick 还糟）。
     */
    internal data class DeviceSupport(
        val hasVibrator: Boolean = false,
        val supportsPredefinedTick: Boolean = false,
        val supportsPredefinedClick: Boolean = false,
        val supportsPrimitiveTick: Boolean = false,
        val supportsPrimitiveClick: Boolean = false,
        val hasAmplitudeControl: Boolean = false,
    ) {
        /** 预定义效果这条路能不能走。 */
        fun supports(effect: Effect): Boolean = hasVibrator && when (effect) {
            Effect.TICK -> supportsPredefinedTick
            Effect.CLICK -> supportsPredefinedClick
        }

        /** Composition 这条路能不能走。`hasAmplitudeControl()` 是官方要求的那一条。 */
        fun supports(primitive: Primitive): Boolean =
            hasVibrator && hasAmplitudeControl && when (primitive) {
                Primitive.TICK -> supportsPrimitiveTick
                Primitive.CLICK -> supportsPrimitiveClick
            }

        companion object {
            /** 什么都没探到（`Vibrator` 拿不到 / 探测抛异常）。 */
            val UNKNOWN = DeviceSupport()
        }
    }

    // ── 参数面 ──────────────────────────────────────────────────────────

    /** 新参数面的键（`side_index.haptic_mode`）。**容错读法的键名以这里为准。** */
    const val KEY_MODE = "haptic_mode"

    /** 新键的合法取值，顺序即设置界面的显示顺序。 */
    val MODE_VALUES: List<String> = listOf("off", "light", "standard", "strong")

    /** 新键读不到时用的档位。 */
    val DEFAULT_MODE: Mode = Mode.STANDARD

    /**
     * 原语 `scale` 的上限。规范里 `addPrimitive` 的 scale 取值 `0..1`，
     * 1 就是「这个原语能给出的最大力度」。
     */
    const val MAX_PRIMITIVE_SCALE = 1f

    /** 老键 `haptic_strength` 在设置界面里声明的下限（见 core/Catalog 的 `min = 10f`）。 */
    const val LEGACY_STRENGTH_UI_MIN = 10

    /** 老强度 → 档位的迁移分界（见 [modeFromLegacyStrength]）。 */
    private const val LEGACY_LIGHT_MAX = 40
    private const val LEGACY_STANDARD_MAX = 80

    // ── 档位解析（新键优先，老键兜底） ──────────────────────────────────

    /**
     * 解析新键。**没声明 / 空值 / 不认识的值都返回 null**（=「没有新键」），
     * 由 [resolveMode] 决定回落成什么。绝不在这里替调用方选默认值 ——
     * 「键不存在」与「键存在但值是默认档」是两件事，混在一起会让安装报告失去信息。
     */
    fun modeFromKey(raw: String?): Mode? {
        val v = raw?.trim()?.lowercase()
        if (v.isNullOrEmpty()) return null
        return when (v) {
            "off" -> Mode.OFF
            "light" -> Mode.LIGHT
            "standard" -> Mode.STANDARD
            "strong" -> Mode.STRONG
            // 未知值：不崩、不当作关，交给 resolveMode 落默认档
            else -> null
        }
    }

    /**
     * 老键 `haptic_strength` → 档位的迁移。
     *
     * ## 为什么是「分界」而不是「百分比 → 振幅」的换算
     *
     * 老值是一个**振幅百分比**，新档位是**表达力档**，两者不是同一个量纲，
     * 严格说不可换算。这里做的是**用户意图迁移**：老值高 = 用户想要更明显的反馈，
     * 那就落到更高的表达力档。分界取 40 / 80（[LEGACY_LIGHT_MAX] / [LEGACY_STANDARD_MAX]），
     * 覆盖老键的默认值 60 → [Mode.STANDARD]。
     *
     * ## 0..9 为什么算「关」
     *
     * 设置界面给 `haptic_strength` 声明的区间是 `10..100`，所以 `0..9`
     * **不是界面能产生的值**：它只可能来自更早版本写进库里的、或者被外部改过的存值。
     * 老代码把 `0` 当「关振动」，这里保守地保留那个语义（[LEGACY_STRENGTH_UI_MIN] 以下一律
     * 按关处理），理由是**不能因为重构就替用户把振动打开** —— 那是「关振动失效」，
     * 正是这次重构要防的事。
     *
     * 注意上一版代码里那个「振幅 == 0 就 `vibrator.cancel()`」的分支已经没有存在的位置了：
     * 新实现根本没有振幅这个概念，[Mode.OFF] 在 [planFor] 的第一行就被拦掉。
     */
    fun modeFromLegacyStrength(percent: Int): Mode = when {
        percent < LEGACY_STRENGTH_UI_MIN -> Mode.OFF
        percent < LEGACY_LIGHT_MAX -> Mode.LIGHT
        percent < LEGACY_STANDARD_MAX -> Mode.STANDARD
        else -> Mode.STRONG
    }

    /**
     * 这次安装到底用哪个档位。**唯一的入口**，[SideIndexOverlay] 只调它。
     *
     * 优先级（从上到下，先命中先返回）：
     *  1. 总开关（老键 `haptic`）关 → [Mode.OFF]。**这一条保证了「关」永远关得掉**，
     *     不受新键有没有接线、值是什么影响；
     *  2. 新键 `haptic_mode` 有合法值 → 用它；
     *  3. 否则按老键 `haptic_strength` 迁移（[modeFromLegacyStrength]）。
     *
     * 第 3 条是「新键还没在 core/Catalog 接线」时的路径：那不是错误状态，
     * 老用户的库里有强度值，就用它，行为不退化。
     */
    fun resolveMode(hapticEnabled: Boolean, modeKey: String?, legacyStrengthPercent: Int): Mode {
        if (!hapticEnabled) return Mode.OFF
        modeFromKey(modeKey)?.let { return it }
        return modeFromLegacyStrength(legacyStrengthPercent)
    }

    // ── 语义映射 ────────────────────────────────────────────────────────

    /** 这个交互用哪个 `HapticFeedbackConstants`。**按语义挑**（见对象顶部那张表）。 */
    fun constantFor(trigger: Trigger): Constant = when (trigger) {
        Trigger.LETTER -> Constant.CLOCK_TICK
        Trigger.ITEM_FOCUS -> Constant.SEGMENT_TICK
        Trigger.CONFIRM -> Constant.CONFIRM
    }

    /** 这个交互在预定义效果那条路上用哪个效果（「确认」比「刻度」更明确）。 */
    fun effectFor(trigger: Trigger): Effect = when (trigger) {
        Trigger.LETTER, Trigger.ITEM_FOCUS -> Effect.TICK
        Trigger.CONFIRM -> Effect.CLICK
    }

    /** 这个交互在 Composition 那条路上用哪个原语（与 [effectFor] 保持一致）。 */
    fun primitiveFor(trigger: Trigger): Primitive = when (trigger) {
        Trigger.LETTER, Trigger.ITEM_FOCUS -> Primitive.TICK
        Trigger.CONFIRM -> Primitive.CLICK
    }

    // ── 决定 ────────────────────────────────────────────────────────────

    /**
     * 给定触发点、档位、设备能力，这一次该发什么。
     *
     * 三条路径都不成立时**一定**有回落（最后落到 [Path.VIEW_CONSTANT]），
     * 因为官方首选路径不需要任何能力探测 —— 也就是说「档位开着」就一定有反馈，
     * 不存在「设了强档、设备不支持、结果什么都不响」这种静默失效。
     */
    fun planFor(trigger: Trigger, mode: Mode, device: DeviceSupport): Plan {
        if (mode == Mode.OFF) return Plan(Path.NONE)

        val constant = constantFor(trigger)
        val effect = effectFor(trigger)
        val primitive = primitiveFor(trigger)

        // 强档：**优先用振幅**（那才是"更强"，见 Path.ONE_SHOT 的说明）。
        // 没有振幅控制的设备（ERM 马达）走不到这里，回落成下面的原语路径。
        if (mode == Mode.STRONG && device.hasAmplitudeControl) {
            return Plan(
                Path.ONE_SHOT, constant, effect, primitive, MAX_PRIMITIVE_SCALE,
                oneShotAmplitude = STRONG_AMPLITUDE, oneShotMs = STRONG_ONE_SHOT_MS,
            )
        }

        // 规范第三条路径：Composition 原语（含 hasAmplitudeControl 检查）
        if (mode == Mode.STRONG && device.supports(primitive)) {
            return Plan(Path.COMPOSITION, constant, effect, primitive, MAX_PRIMITIVE_SCALE)
        }

        // 规范第二条路径：预定义效果
        if (mode != Mode.LIGHT && device.supports(effect)) {
            return Plan(Path.PREDEFINED_EFFECT, constant, effect, primitive, MAX_PRIMITIVE_SCALE)
        }

        // 规范首选路径：View 常量（零探测、零权限、自带 fallback）
        return Plan(Path.VIEW_CONSTANT, constant, effect, primitive, MAX_PRIMITIVE_SCALE)
    }

    /**
     * 一句话描述当前档位在**每个触发点**上实际会走哪条路，进安装报告。
     *
     * 为什么要把这个写进报告：`hooked=0` 那种「打开了但没生效」的表现，
     * 基本都是因为没人看见「实际选中的是哪条路」。这里让它看得见。
     */
    fun describe(mode: Mode, device: DeviceSupport): String = buildString {
        append("mode=").append(mode.name.lowercase())
        for (t in Trigger.entries) {
            append(' ').append(t.name.lowercase()).append('=')
            append(planFor(t, mode, device).path.name.lowercase())
        }
    }
}
