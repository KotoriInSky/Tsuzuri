package io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import io.github.kotoriinsky.tsuzuri.core.DrawerSignal
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * 在 **system_server** 里观察「澎湃抽屉开着吗」，并把结论广播给 SystemUI。
 *
 * ## 为什么落在 system_server
 *
 * 判据是 WMS 侧的窗口属性（桌面窗口的 `softInputMode`，见 [DrawerSignal] 的类注释），
 * 而它不是任何公开窗口信息的一部分：`AccessibilityWindowInfo` 与 `WindowInfo` 都不带它，
 * SystemUI 跨进程也反射不到 WMS 的 `mWindowMap`。system_server 里能直接看到
 * WMS 的入参，而且模块**本来就注入 system_server**（设备实测 LSPosed 日志：
 * `(system)[io.github.kotoriinsky.tsuzuri,FP-Registry,...] process=system systemServer=true`）。
 *
 * ## hook 点：`WindowManagerService#relayoutWindow`
 *
 * 这是**唯一**能拿到「客户端这次想用什么 `softInputMode`」的地方。依据（本机
 * `services.jar` 的 AOSP 源码，`WindowManagerService.java:2462`）：
 *
 * ```java
 * attrChanges = win.mAttrs.copyFrom(attrs);   // attrs 就是 relayoutWindow 的第 3 个参数
 * ```
 *
 * 也就是说客户端的属性每次都是**从 relayoutWindow 的参数**拷进 `WindowState.mAttrs` 的，
 * 而 `dumpsys window windows` 打印的 `mAttrs={... sim=... }` 正是这份数据 ——
 * 我们的判据与取证用的那条信号是**同一份**东西，不是近似。
 *
 * ### 签名逐版本漂移（所以必须运行期按「方法名 + 参数类型」找）
 *
 * 同一台设备的 dex 里已经是新形状了：
 *
 * ```
 * relayoutWindow(Session, IWindow, WindowManager$LayoutParams, int×6, WindowRelayoutResult, SurfaceControl) : int
 * ```
 *
 * 而 AOSP 14 的旧形状是 18 个参数（`Rect outFrame` / `InsetsState` / `InsetsSourceControl[]` /
 * `Bundle` 那一串）。**所以这里不硬编码任何签名**：枚举 `declaredMethods` 里所有叫
 * `relayoutWindow` 的方法，取参数里带 `WindowManager.LayoutParams` 的那一个，并记住它的
 * **下标**（旧形状是 2，新形状也是 2，但下标由运行期算，不写死）。找不到 = 正常情况
 * （OTA 改名）→ 返回 `hooked=0` + `missing=[...]`，SystemUI 侧因此收不到信号而判
 * 「未知」，行为与未安装模块完全一致。
 *
 * ### 为什么不用 `deoptimize()`
 *
 * 规范 §7.8 说系统框架里「hook 不生效是常态」、需要 `deoptimize()`。这里**不**用它：
 * `relayoutWindow` 是一个几百行的巨型方法（AOSP 源码里它横跨约 450 行），
 * AOT 不可能把它内联进调用方（`Session.relayout`），所以 hook 会正常生效；
 * 而 `deoptimize` 会把调用方打回解释执行，对系统最热的方法做这件事是**破坏性能**的操作。
 * 真机上 hook 不生效的表现是「抽屉里照弹」，那时再来评估这一条 —— 报告里列为待验证。
 *
 * ## 热路径纪律（这是 system_server 里最热的几个方法之一）
 *
 * hook 体只做四件事：一个计数器自增、读一个 int、读一个 String 引用、比一个 int。
 * **零分配、零 IO、零日志、零 IPC、零锁**；而且**只读参数、绝不修改参数** ——
 * 改 WMS 的入参会波及整个系统。广播一律转投后台线程，不在 hook 体里发。
 *
 * ## 稳定性纪律（这里的异常逃逸 = 整个系统重启）
 *
 * hook 体连 `Throwable` 一起兜住（规范 §7.1：`PROTECTIVE` 只兜 exception、不兜 `Error`），
 * 出错就是「这次没有信号」，绝不是崩溃。后台线程与心跳里的每一处也都各自兜住。
 */
internal object LauncherDrawerObserver {

    private const val TAG = "FP-DrawerObserver"

    private const val WMS_CLASS = "com.android.server.wm.WindowManagerService"
    private const val RELAYOUT_METHOD = "relayoutWindow"

    /**
     * 解析不到桌面包名时的兜底。
     *
     * `onSystemServerStarting` 发生得很早，那时 PackageManager 可能还没就绪。
     * 这个字面量是**整个模块的前提**（ADR-0001：目标就是澎湃桌面 `com.miui.home`），
     * 不是「抄来的设备常量」；用的是兜底值时会在日志里明说，且会报进安装报告。
     */
    private const val FALLBACK_HOME = "com.miui.home"

    /** `WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION`：负一屏 overlay 的窗口类型（dumpsys 实测）。 */
    private const val TYPE_DRAWN_APPLICATION = 4
    private const val ASSISTANT_OVERLAY_TITLE = "LauncherOverlayWindow:com.miui.personalassistant"
    private const val ASSISTANT_LOG_MAX = 8

    /** 诊断日志预算：报多少个「同包名同 type 但不是主窗口」的窗口（有界，避免热路径刷日志）。 */
    private const val WINDOW_DIAGNOSTIC_BUDGET = 8

    /**
     * 「第 N 个参数不是 LayoutParams」连续到多少次就报一次。
     *
     * 取 200：`attrs == null` 是合法且偶发的（客户端只要求重新布局），
     * 连到 200 次基本只可能是**下标算错**，而那正是需要人去查的情形。
     */
    private const val NULL_ARG_ALERT = 200

    /** 性能探针的两个采样点（自安装起）。只打两条，不做周期性日志。 */
    private const val RATE_PROBE_1_MS = 60_000L
    private const val RATE_PROBE_2_MS = 300_000L

    // ── 安装期解析出来的只读状态（热路径直接读，不再做任何解析） ──────────

    /** `LayoutParams` 在 `relayoutWindow` 参数表里的下标。-1 = 没找到目标方法。 */
    @Volatile
    private var lpArgIndex = -1

    /** 桌面包名。热路径上用来过滤。 */
    @Volatile
    private var homePackage = FALLBACK_HOME

    /** `"<桌面包>/"`。预先拼好 —— 热路径上不许拼字符串。 */
    @Volatile
    private var titleMarker = DrawerSignal.titleMarker(FALLBACK_HOME)

    private var handle: XposedInterface.HookHandle? = null

    @Volatile
    private var hookCtx: HookContext? = null

    /** system_server 的 Context（发广播用）。解析一次，后台线程读。 */
    @Volatile
    private var appContext: Context? = null

    // ── 热路径状态 ──────────────────────────────────────────────────────

    /**
     * `relayoutWindow` 被调用的次数。
     *
     * `LongAdder` 而不是 `AtomicLong`：这个方法的调用来自几十个线程，
     * 高争用下 `AtomicLong` 会在同一条缓存行上打架，`LongAdder` 是每个线程一个槽。
     * 它存在的唯一理由是**给「hook 的性能开销」一个实测数字**（见 [rateProbe]）。
     */
    private val relayoutCount = LongAdder()

    /** 上一次看到的桌面窗口 `softInputMode`。`Int.MIN_VALUE` = 还没见过。 */
    @Volatile
    private var lastSoftInputMode = Int.MIN_VALUE

    /** 上一次广播出去的结论。主线程外的读写在语义上是「最后一次写的赢」，够用。 */
    @Volatile
    private var lastOpen = false

    /** 抽屉（主窗口 sim）那一半结论。 */
    @Volatile
    private var drawerOpen = false

    /**
     * 负一屏那一半结论（2026-10-25 加）。
     *
     * 负一屏是 PA 进程的 `LauncherOverlayWindow:com.miui.personalassistant`（`ty=DRAWN_APPLICATION`），
     * 实测收起时 `alpha=0.0` 且带 `NOT_TOUCHABLE`。展开时它必须能接触摸，
     * 所以判据取「去掉了 NOT_TOUCHABLE 或 alpha > 0」。**展开态的属性待真机验证**，
     * 第一次翻转会打一条 INFO 留证据。
     */
    @Volatile
    private var assistantOpen = false

    /** 这一条待发信号是不是心跳。 */
    @Volatile
    private var heartbeatPending = false

    @Volatile
    private var installed = false

    private val signalSeq = AtomicInteger()

    /** 广播连续失败次数（用于「只记一次失败 + 恢复时记一次」与退避重试）。 */
    private val broadcastFailures = AtomicInteger()

    /** 退避：1s / 2s / 4s / 8s 封顶 —— 开机完成前那几秒够用，又不会在真出问题时刷日志。 */
    private fun retryDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 3)
        return (1000L shl shift).coerceAtMost(8000L)
    }

    private val diagnosticBudget = AtomicInteger(WINDOW_DIAGNOSTIC_BUDGET)

    /**
     * 连续多少次没在 `lpArgIndex` 上拿到 `LayoutParams`。
     *
     * 故意用**普通 Int** 而不是原子类型：它只是诊断计数，多线程下偶尔丢一两个数
     * 完全不影响结论（我们只关心「有没有连续 200 次」），而在热路径上加一次
     * 原子操作是不值得的。见 [noteMissingLayoutParams]。
     */
    @Volatile
    private var nullArgCount = 0

    // ── 后台线程（发广播、发心跳、打性能探针） ────────────────────────────

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * 安装观察器。
     *
     * @return 安装报告。**找不到目标方法时返回 `hooked=0` 而不是抛异常** ——
     *         调用方（`HookRegistry`）据此决定不把这个组件记进 installed 集合。
     */
    fun install(ctx: HookContext): InstallReport {
        if (installed) return InstallReport(1, notes = listOf("already installed"))

        hookCtx = ctx
        appContext = resolveSystemContext(ctx)

        val homeNote = resolveHome(ctx)
        val cls = loadWmsClass(ctx)
            ?: return InstallReport(0, missing = listOf(WMS_CLASS), notes = listOf("wms_class_not_found"))
        val found = findRelayout(cls)
            ?: return InstallReport(
                0,
                missing = listOf("$WMS_CLASS#$RELAYOUT_METHOD(<LayoutParams>)"),
                notes = listOf("wms_method_not_found", homeNote),
            )
        val (method, argIndex) = found
        lpArgIndex = argIndex

        // 后台线程必须先起来：hook 一装上就可能立刻有信号要发。
        val ht = HandlerThread("fp-drawer-signal").apply {
            isDaemon = true
            start()
        }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        val hooked = try {
            ctx.module.hook(method).intercept { chain ->
                // 热路径：唯一的 try/catch 在这里。**不能让它抛出去** ——
                // 这里抛出去的最坏后果是整个系统重启，而最好的后果也只是
                // 「这次没有信号」。所以连 Throwable 一起兜（规范 §7.1）。
                try {
                    inspectRelayout(chain)
                } catch (_: Throwable) {
                    // 故意不记日志：热路径上记日志本身就是事故（规范 §7.4）
                }
                chain.proceed()
            }.also { handle = it }
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "抽屉观察器：hook $WMS_CLASS#$RELAYOUT_METHOD 失败，本次没有抽屉信号", t)
            0
        }

        if (hooked == 0) {
            releaseThread()
            return InstallReport(0, missing = listOf("$WMS_CLASS#$RELAYOUT_METHOD"), notes = listOf(homeNote))
        }

        installed = true

        // 心跳：状态不变时也要让 SystemUI 知道「通道还活着」。
        h.postDelayed(heartbeat, DrawerSignal.HEARTBEAT_MS)
        // 性能探针：两个采样点，给「hook 开销」一个数字（不做周期性日志，避免噪声）。
        h.postDelayed({ rateProbe(RATE_PROBE_1_MS) }, RATE_PROBE_1_MS)
        h.postDelayed({ rateProbe(RATE_PROBE_2_MS) }, RATE_PROBE_2_MS)
        // 立刻发一条初始状态：接收端起始是 unknown；一条信号让它马上变成确定的 closed
        // （= 照常弹），这样「信号通不通」从第一秒就看得见，而不是等到用户开一次抽屉。
        postSignal(heartbeat = false)

        val sig = describeMethod(method, argIndex)
        ctx.log(
            Log.INFO,
            "抽屉观察器已装上：$sig $homeNote relayouts=${relayoutCount.sum()}",
        )
        return InstallReport(
            hooked = 1,
            notes = listOf("observer=$sig", homeNote),
        )
    }

    fun uninstall() {
        installed = false
        lpArgIndex = -1
        try {
            handle?.unhook()
        } catch (_: Throwable) {
            // 卸不掉没有补救手段，也不能因此抛出去
        }
        handle = null
        releaseThread()
        hookCtx = null
        appContext = null
        lastSoftInputMode = Int.MIN_VALUE
        lastOpen = false
        drawerOpen = false
        assistantOpen = false
        signalSeq.set(0)
    }

    private fun releaseThread() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    // ── 热路径 ─────────────────────────────────────────────────────────

    /**
     * 热路径本体。**只观察、绝不修改**（不调用 `proceed(args)`）。
     *
     * 过滤顺序按「便宜且能筛掉最多」排：
     * `type` 的 int 比较（系统里 type==TYPE_BASE_APPLICATION 的窗口只有几十个，
     * 绝大多数调用在这一行就返回了）→ title 的 `startsWith`（硬条件）
     * → `packageName` 的 String 比较（软校验）。
     * 三个判据的松紧与顺序都由 [DrawerSignal.isLauncherMainWindow] 负责，
     * 这里只负责「取参数」与「出错就有界地报一条」。
     */
    private fun inspectRelayout(chain: XposedInterface.Chain) {
        relayoutCount.increment()

        val idx = lpArgIndex
        if (idx < 0) return
        // attrs 可以为 null：客户端只要求重新 layout 时会传 null（WMS 侧 `if (attrs != null)`）
        val lp = chain.getArg(idx) as? WindowManager.LayoutParams
        if (lp == null) {
            noteMissingLayoutParams(idx)
            return
        }
        // 见过一次正常值之后就不再计数（普通 int 读写：这是纯诊断计数，
        // 多线程下偶尔丢一两个数没有影响，不值得为它加一次原子操作到热路径上）
        if (nullArgCount != 0) nullArgCount = 0
        if (lp.type == TYPE_DRAWN_APPLICATION) {
            val t = lp.title as? String
            if (t == ASSISTANT_OVERLAY_TITLE) onAssistantRelayout(lp.flags, lp.alpha)
            return
        }
        if (lp.type != DrawerSignal.TYPE_BASE_APPLICATION) return
        val title = lp.title as? String
        val pkg = lp.packageName
        if (DrawerSignal.isLauncherMainWindow(pkg, lp.type, title, homePackage, titleMarker)) {
            onLauncherRelayout(lp.softInputMode)
            return
        }
        // 走到这里 = 「是 Activity 主窗口，但不是桌面主窗口」。
        // **只在两种值得看一眼的形状下**才进诊断分支（否则系统里每个应用的主窗口
        // 都会走到这里，那会白白给热路径加一次原子操作）：
        //  ① 包名是桌面的 —— 实测存在这种 window（`LauncherOverlayWindow:com.android.quicksearchbox`
        //     的 LayoutParams 就是 `pkg = com.miui.home ty=BASE_APPLICATION`）；
        //  ② title 为空 —— 判据会因此**完全失灵**（信号一条都不发），
        //     没有这条日志就完全看不出来。上限 8 条，烧完之后稳态代价几乎为零。
        if (pkg == homePackage || title == null) {
            noteOtherActivityWindow(pkg, lp.type, title, lp.softInputMode)
        }
    }

    /**
     * 桌面主窗口的 `softInputMode` 变了。
     *
     * 只在**结论**翻转时广播：`softInputMode` 的 `state` 位会单独变（例如抽屉里点搜索框），
     * 那种变化不影响「抽屉开着吗」，没必要打扰 SystemUI。
     */
    private fun onLauncherRelayout(softInputMode: Int) {
        if (softInputMode == lastSoftInputMode) return
        lastSoftInputMode = softInputMode
        drawerOpen = DrawerSignal.isDrawerOpen(softInputMode)
        publish()
    }

    /** 负一屏窗口 relayout：只在结论翻转时广播（与抽屉合并成同一条「桌面被占用」信号）。 */
    private fun onAssistantRelayout(flags: Int, alpha: Float) {
        val open = (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0 || alpha > 0f
        if (open == assistantOpen) return
        assistantOpen = open
        if (assistantFlips < ASSISTANT_LOG_MAX) {
            assistantFlips++
            hookCtx?.log(
                Log.INFO,
                "负一屏信号：open=$open fl=0x${Integer.toHexString(flags)} alpha=$alpha",
            )
        }
        publish()
    }

    private fun publish() {
        val open = drawerOpen || assistantOpen
        if (open == lastOpen) return
        lastOpen = open
        postSignal(heartbeat = false)
    }

    private var assistantFlips = 0

    /**
     * 把「发一条信号」转投后台线程。
     *
     * **绝不在 hook 体里发广播**：那是一次跨进程调用 + 要拿 AMS 的锁，而我们正站在
     * WMS 的锁里、站在整个系统最热的路径上（规范 §7.5：hook 回调里禁止跨进程调用）。
     * `Handler.post` 复用池化的 `Message`，不分配。
     */
    private fun postSignal(heartbeat: Boolean) {
        val h = handler ?: return
        heartbeatPending = heartbeat
        h.post(signal)
    }

    /** 后台线程：真正发广播。 */
    private val signal: Runnable = Runnable {
        val ctx = hookCtx ?: return@Runnable
        val context = appContext
        if (context == null) {
            if (signalSeq.get() == 0) {
                ctx.log(Log.WARN, "抽屉观察器：system_server 里拿不到 Context，信号发不出去（SystemUI 侧会判未知）")
            }
            return@Runnable
        }
        val open = lastOpen
        val heartbeat = heartbeatPending
        val seq = signalSeq.incrementAndGet()
        val sent = try {
            val intent = Intent(DrawerSignal.ACTION)
                // 定向到 SystemUI：不用 action 做全系统广播（那会让所有应用都收到一条
                // 无意义的广播，也会让「谁能伪造它」的边界变模糊）。
                .setPackage(DrawerSignal.SYSTEM_UI_PACKAGE)
                .putExtra(DrawerSignal.EXTRA_OPEN, open)
                .putExtra(DrawerSignal.EXTRA_SEQ, seq)
                .putExtra(DrawerSignal.EXTRA_RELAYOUTS, relayoutCount.sum())
                .putExtra(DrawerSignal.EXTRA_HEARTBEAT, heartbeat)
            // 前台广播队列：抽屉一开就要尽快抑制，排队等后台广播队列会引入秒级延迟
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
            true
        } catch (t: Throwable) {
            // ⚠️ **这不是错误状态**：实测这台机器上这里是
            // `NullPointerException: IActivityManager.broadcastIntentWithFeature(...) on a null object reference`
            // —— system_server 装 hook 的时机（`onSystemServerStarting`）**早于 AMS 构造**，
            // 那一刻 `ActivityManager.getService()` 还是 null，`sendBroadcast` 必然 NPE。
            // 而抽屉门控只关心「用户打开抽屉」这件事，那一定发生在开机完成之后，
            // 所以正确处理是**短间隔重试**，而不是干等下一次心跳（15s）。
            val n = broadcastFailures.incrementAndGet()
            if (n == 1) {
                ctx.log(
                    Log.WARN,
                    "抽屉观察器：广播暂时发不出去（启动早期 AMS 未就绪，属预期），将自动重试：" + t,
                )
            }
            val h = handler
            if (h != null && !h.hasCallbacks(signal)) {
                h.postDelayed(signal, retryDelayMs(n))
            }
            false
        }
        if (sent && broadcastFailures.getAndSet(0) > 0) {
            ctx.log(Log.INFO, "抽屉观察器：广播已恢复（此前失败 N 次后打通），系统状态已同步给 SystemUI")
        }
        // 只在**状态变化**与**第一条**上记日志：心跳每 15s 一条，记下来就是纯噪声。
        if (sent && (!heartbeat || seq == 1)) {
            ctx.log(
                Log.INFO,
                "抽屉信号：open=$open sim=0x${Integer.toHexString(lastSoftInputMode)} seq=$seq" +
                    " heartbeat=$heartbeat relayouts=${relayoutCount.sum()}",
            )
        }
    }

    /** 心跳：让 SystemUI 能区分「抽屉一直开着」和「通道死了」。 */
    private val heartbeat = object : Runnable {
        override fun run() {
            postSignal(heartbeat = true)
            handler?.postDelayed(this, DrawerSignal.HEARTBEAT_MS)
        }
    }

    /**
     * 性能探针：把「自安装起 `relayoutWindow` 被调用了多少次」按耗时折算成速率。
     *
     * 只打两条（安装 +60s / +300s），**不做周期性日志** —— 这条信息的用途是给
     * 「hook 一个 system_server 热方法的开销」一个可核对的数字，不是长期监控。
     */
    private fun rateProbe(elapsedMs: Long) {
        val ctx = hookCtx ?: return
        val n = relayoutCount.sum()
        // 整数折算，避免 String.format 带 Locale 的坑（这条只是量级参考，不需要小数）
        val perSec = if (elapsedMs <= 0) 0L else n * 1000L / elapsedMs
        ctx.log(
            Log.INFO,
            "抽屉观察器：relayouts=$n 在 ${elapsedMs / 1000}s 内（约 $perSec/s，" +
                "hook 体只做「计数器+int+String 比较」）",
        )
    }

    /**
     * `chain.getArg(idx)` 不是 `LayoutParams` —— 两种可能，这条日志负责把它们分开：
     *
     *  1. **合法**：客户端只要求重新 layout，`attrs` 就是 null（WMS 源码里
     *     `relayoutWindow` 的第一件事就是 `if (attrs != null)`）。这种会一直观察到
     *     正常值，计数器会被 [inspectRelayout] 里那一行清零。
     *  2. **下标算错了**：那就**永远**只会看到 null，抽屉信号一条都发不出去，
     *     而用户看到的表现是「抽屉里照弹」—— 也就是这个功能没做。
     *     没有这条日志的话，`hooked=1` 的安装报告看起来完全正常，无从下手。
     *
     * 只在连续 [NULL_ARG_ALERT] 次之后报**一次**，所以稳态下没有任何日志开销。
     */
    private fun noteMissingLayoutParams(idx: Int) {
        nullArgCount++
        if (nullArgCount != NULL_ARG_ALERT) return
        hookCtx?.log(
            Log.WARN,
            "抽屉观察器：前 $NULL_ARG_ALERT 次 relayoutWindow 的第 $idx 个参数都不是 " +
                "WindowManager.LayoutParams（拿到 null），下标可能算错了 —— " +
                "抽屉信号将一条都不发，请核对安装报告里的 observer=...lpIdx=",
        )
    }
    /**
     * 诊断：报一个「是 Activity 主窗口但不是桌面主窗口」的窗口。
     *
     * 有界（最多 [WINDOW_DIAGNOSTIC_BUDGET] 条）。调用点已经先筛过一遍
     * （只报「包名是桌面的」或「title 为空」这两种值得看一眼的形状），
     * 所以这个函数本身只负责格式化 —— 预算烧完之后连它都不会被调到。
     */
    private fun noteOtherActivityWindow(pkg: String?, type: Int, title: String?, sim: Int) {
        if (diagnosticBudget.getAndDecrement() <= 0) return
        val ctx = hookCtx ?: return
        // 只有走到这里才允许分配（有界次数）
        ctx.log(
            Log.INFO,
            "抽屉观察器：忽略窗口 pkg=${pkg ?: "null"} type=$type title=${title ?: "null"} " +
                "sim=0x${Integer.toHexString(sim)}（不满足 ty=BASE_APPLICATION 且 title 以 $titleMarker 开头）",
        )
    }

    // ── 安装期解析（都是冷路径，允许分配与反射） ──────────────────────────

    /**
     * 枚举 `relayoutWindow` 的所有重载，取参数里带 `WindowManager.LayoutParams` 的那个，
     * 并算出它的**下标**。返回 null = 本 ROM 上没有这个形状（正常降级点）。
     *
     * 为什么要「参数最少的那个」：同名重载理论上可能存在（真实设备上只有一个）。
     */
    private fun findRelayout(cls: Class<*>): Pair<Method, Int>? = runCatching {
        val lpClass = Class.forName("android.view.WindowManager\$LayoutParams", false, cls.classLoader)
        var best: Pair<Method, Int>? = null
        for (m in cls.declaredMethods) {
            if (m.name != RELAYOUT_METHOD) continue
            val idx = m.parameterTypes.indexOfFirst { lpClass.isAssignableFrom(it) }
            if (idx < 0) continue
            if (best == null || m.parameterCount < best.first.parameterCount) best = m to idx
        }
        best
    }.getOrNull()

    /** 找到 `WindowManagerService` 的**运行时**那个 Class 对象（用多个 ClassLoader 逐个试）。 */
    private fun loadWmsClass(ctx: HookContext): Class<*>? {
        val loaders = listOfNotNull(
            ctx.classLoader,
            LauncherDrawerObserver::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        for (l in loaders) {
            runCatching { Class.forName(WMS_CLASS, false, l) }.getOrNull()?.let { return it }
        }
        // 最后手段：用本类的 ClassLoader（会委托到 boot，system_server 的框架类都在那里）
        return runCatching { Class.forName(WMS_CLASS) }.getOrNull()
    }

    /** 解析桌面包名；解析不到就用 [FALLBACK_HOME] 并如实记进报告。 */
    private fun resolveHome(ctx: HookContext): String {
        val context = appContext
        val resolved = context?.let {
            runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                it.packageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }.getOrNull()
        }
        return if (resolved.isNullOrEmpty()) {
            // 兜底值也是可用值（本机桌面就是它），但**必须在报告里看得见**
            homePackage = FALLBACK_HOME
            titleMarker = DrawerSignal.titleMarker(FALLBACK_HOME)
            "home=$FALLBACK_HOME(兜底)"
        } else {
            homePackage = resolved
            titleMarker = DrawerSignal.titleMarker(resolved)
            "home=$resolved(解析)"
        }
    }

    /**
     * system_server 的 Context。
     *
     * ① `ActivityThread.currentApplication()` —— 与 SystemUI 侧 `SystemUiEnv.context()`
     *    同一条路，在任何应用进程里都成立；`ActivityThread.systemMain()` 会建一个
     *    framework 的 Application，所以它在本机 system_server 上应当非空。
     * ② 兜底 `ActivityThread.getSystemContext()`（`@hide` 静态方法，只能反射）。
     */
    private fun resolveSystemContext(ctx: HookContext): Context? {
        val at = try {
            Class.forName("android.app.ActivityThread")
        } catch (_: Throwable) {
            ctx.log(Log.WARN, "抽屉观察器：找不到 ActivityThread，信号通道会是未知状态")
            return null
        }
        runCatching { at.getMethod("currentApplication").invoke(null) as? Context }
            .getOrNull()?.let { return it }
        return runCatching { at.getMethod("getSystemContext").invoke(null) as? Context }
            .onFailure { ctx.log(Log.INFO, "抽屉观察器：getSystemContext 也不可用（${it.javaClass.simpleName}）") }
            .getOrNull()
    }

    /**
     * 目标方法的**真实签名**（进日志与安装报告）。OTA 之后第一件事就是拿它和这里对的
     * 记号比 —— 与 `InstallReport.missing` 一起构成「落点还在不在」的证据。
     */
    private fun describeMethod(m: Method, argIndex: Int): String {
        val params = m.parameterTypes.joinToString(",") { it.simpleName }
        return "#${m.name}(args=${m.parameterCount},lpIdx=$argIndex)[$params]"
    }
}
