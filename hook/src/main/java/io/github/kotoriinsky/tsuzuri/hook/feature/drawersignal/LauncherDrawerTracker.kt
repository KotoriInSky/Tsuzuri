package io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.DrawerSignal
import io.github.kotoriinsky.tsuzuri.hook.HookContext

/**
 * SystemUI 侧的抽屉状态追踪器：收 system_server 的信号，缓存成**一个** `@Volatile`。
 *
 * 形状与 [io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv.ForegroundTracker]
 * **完全同构**（那是本仓库已经跑通的形态）：
 *
 * ```
 * 后台（广播接收器，主线程 Handler）
 *      ↓ 写
 * @Volatile 字段
 *      ↓ 只读
 * 输入分发线程（SideIndexDetector.onTouch）
 * ```
 *
 * ## 为什么必须是这个形状
 *
 * 手势判定跑在**输入分发线程**上，那里禁止任何 IPC / IO / 分配（规范 §7.4/§7.5）。
 * 所以接收器只写 volatile 字段，[isDrawerSuppressed] 只读 volatile 字段 ——
 * 输入线程上零 IPC。广播本身在 SystemUI 的主线程投递，也不占输入线程。
 *
 * ## 优雅降级（本条的硬要求，方向不能反）
 *
 * 三种「没有信号」的情况 —— 接收器注册失败、system_server 侧观察器没装上
 * （`hooked=0`）、心跳超时 —— 全部落到同一个结论：**未知 → 不抑制**，
 * 也就是**与未安装模块时的行为完全一致**（抽屉里照弹）。
 * 绝不能因为收不到信号就把侧滑索引整个禁掉：那会把「这个新功能没生效」
 * 变成「老功能坏了」，是更严重的事故。
 *
 * 「未知」在这一层是可证明的：初值 `lastSignalUptimeMs = 0`，而
 * [DrawerSignal.isSignalFresh] 对 `<= 0` 恒返回 false。
 */
object LauncherDrawerTracker {

    /** 抽屉当前是否打开。只有主线程（接收器回调）写。 */
    @Volatile
    private var open = false

    /** 最后一条信号的时间（`uptimeMillis`）。0 = 从来没收到过 = 通道未知。 */
    @Volatile
    private var lastSignalUptimeMs = 0L

    /** 收到过多少条信号（含心跳）。诊断用。只有主线程写。 */
    @Volatile
    private var signalCount = 0

    /** 非 null = 接收器注册失败，内容是原因（进安装报告，见 [describe]）。 */
    @Volatile
    private var registerNote: String? = null

    private var receiver: BroadcastReceiver? = null

    private var appContext: Context? = null

    @Volatile
    private var hookCtx: HookContext? = null

    /**
     * 抽屉现在开着、且信号通道还活着 → 侧滑索引必须不响应。
     *
     * **这是输入分发线程唯一允许调用的入口**：两次 volatile 读 + 一次
     * `SystemClock.uptimeMillis()`（只在 `open == true` 时才走到）。
     * 普通桌面态（`open == false`）下只读一次 volatile 就返回。
     */
    fun isDrawerSuppressed(): Boolean {
        // 先读最可能为 false 的那个：绝大多数手势都发生在抽屉没开的时候
        if (!open) return false
        return DrawerSignal.isSignalFresh(lastSignalUptimeMs, SystemClock.uptimeMillis())
    }

    /**
     * 注册接收器。**必须显式调用**（不能在 [isDrawerSuppressed] 里懒加载 ——
     * 那就等于在输入线程上注册广播接收器）。
     *
     * @return 通道描述（进安装报告的那个 `drawer=` 字段）
     */
    fun start(context: Context, ctx: HookContext): String {
        if (receiver != null) return describe()
        appContext = context
        hookCtx = ctx

        val r = SignalReceiver()
        val filter = IntentFilter(DrawerSignal.ACTION)
        // 投递到主线程：本项目的其余状态都假定「主线程读写」，保持一致
        val handler = Handler(Looper.getMainLooper())

        // 三种注册形状，从「最安全且最可能是对的」往下试。
        //
        // 为什么不是「一种写死」：SystemUI 在这个 ROM 上跑在自己的 shared user 里
        // （实测 `android.uid.systemui` = uid 10226，system_server 是 1000），
        // 也就是说**跨 uid**，所以必须 EXPORTED；而 EXPORTED 不要权限就等于
        // 「谁都能伪造抽屉状态」，所以首选带 `android.permission.DUMP`（第三方拿不到）。
        // 后面两种只是兜底：某些 ROM 上对系统身份发来的广播本来就会放行，
        // 那就不必承担权限声明的风险（例如权限根本没被授给 system 身份时会全部收不到）。
        val attempts = listOf(
            Triple(DrawerSignal.PERMISSION, Context.RECEIVER_EXPORTED, "exported+dump"),
            Triple(null, Context.RECEIVER_EXPORTED, "exported"),
            Triple(null, Context.RECEIVER_NOT_EXPORTED, "not-exported"),
        )
        var note: String? = null
        for ((permission, flags, name) in attempts) {
            try {
                context.registerReceiver(r, filter, permission, handler, flags)
                receiver = r
                registerNote = null
                ctx.log(
                    Log.INFO,
                    "抽屉信号：接收器已注册（$name），等 system_server 的信号（未收到前判「未知」= 不抑制）",
                )
                return describe()
            } catch (t: Throwable) {
                note = "$name:${t.javaClass.simpleName}"
                ctx.log(Log.WARN, "抽屉信号：注册失败（$name），试下一种", t)
            }
        }
        registerNote = note
        ctx.log(
            Log.WARN,
            "抽屉信号：接收器注册全部失败（$note）→ 抽屉门控不生效，" +
                "侧滑索引行为与未安装模块一致（抽屉里会照弹）",
        )
        return describe()
    }

    /** 注销 + 把状态清回「未知」。关态必须与「没装模块」完全一致。 */
    fun stop() {
        val r = receiver
        val context = appContext
        receiver = null
        if (r != null && context != null) {
            runCatching { context.unregisterReceiver(r) }
                .onFailure { hookCtx?.log(Log.WARN, "抽屉信号：注销接收器失败（无害，下次安装会重建）", it) }
        }
        appContext = null
        open = false
        lastSignalUptimeMs = 0L
        signalCount = 0
        registerNote = null
        hookCtx = null
    }

    /** 通道描述（进安装报告 / 日志）。可从任何线程调。 */
    fun describe(): String = DrawerSignal.describe(
        lastSignalUptimeMs = lastSignalUptimeMs,
        nowUptimeMs = SystemClock.uptimeMillis(),
        open = open,
        signalCount = signalCount,
        registerNote = registerNote,
    )

    /**
     * 收到一条信号。
     *
     * 广播接收器的回调里逃出去的异常就是 SystemUI 崩溃（用户看到黑屏），
     * 所以连 `Throwable` 一起兜住 —— 与项目里所有回调的规矩一致。
     */
    private class SignalReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                accept(intent)
            } catch (_: Throwable) {
                // 收下这条信号失败 = 这一条不算数，通道会在心跳超时后判「未知」
            }
        }
    }

    /** 只有 main(Handler) 线程调它。 */
    private fun accept(intent: Intent) {
        if (intent.action != DrawerSignal.ACTION) return
        val next = intent.getBooleanExtra(DrawerSignal.EXTRA_OPEN, false)
        val heartbeat = intent.getBooleanExtra(DrawerSignal.EXTRA_HEARTBEAT, false)
        val seq = intent.getIntExtra(DrawerSignal.EXTRA_SEQ, -1)
        val relayouts = intent.getLongExtra(DrawerSignal.EXTRA_RELAYOUTS, -1L)

        // 心跳也要刷新时间戳 —— 它的全部意义就是「通道还活着」
        lastSignalUptimeMs = SystemClock.uptimeMillis()
        signalCount++

        val changed = next != open
        open = next
        val ctx = hookCtx ?: return
        if (changed) {
            ctx.log(
                Log.INFO,
                "抽屉信号：open=$next seq=$seq heartbeat=$heartbeat relayouts=$relayouts → " +
                    if (next) "侧滑索引在抽屉里被抑制" else "侧滑索引恢复（桌面态照常）",
            )
        } else if (signalCount == 1) {
            // 第一条（通常是观察器装上时主动发的那条）：证明通道真的通了。
            // 只打这一条，心跳本身不打，否则每 15s 一条噪声。
            ctx.log(
                Log.INFO,
                "抽屉信号：通道已连通（第一条）open=$next heartbeat=$heartbeat seq=$seq relayouts=$relayouts",
            )
        }
    }
}
