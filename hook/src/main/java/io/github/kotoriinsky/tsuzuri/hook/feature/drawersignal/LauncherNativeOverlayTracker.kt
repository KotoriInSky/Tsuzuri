package io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.LauncherOverlaySignal
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import java.io.File

/**
 * 桌面遮挡信号的**读取侧**：轮询桌面进程写下的那个共享文件（native hook 那条路）。
 *
 * 与 [LauncherDrawerTracker] 的关系：那个是「从外面猜」（system_server 侧看 WMS `softInputMode`），
 * 这个是「桌面自己报」（hook `DesktopServiceImpl.notifyDrawerOpen/Close` +
 * `notifyFolderOpen/Close`）。两条并行，任一说被遮挡就抑制。
 *
 * ## 三条纪律
 *
 * 1. **轮询线程只干这一件事**：读文件 + 解析 + 把结果放进一个 `@Volatile`。
 *    输入分发线程只读那个 volatile（外加一次单调钟取时），**绝不在输入线程上读文件**。
 * 2. **读不到 = 未知 = 不抑制**（与 [LauncherDrawerTracker] 同一条 fail-closed 方向）。
 *    这条信号失效的表现是静默的，所以 [describe] 会进安装报告 —— 那是唯一看得见它的地方。
 * 3. **文件是 0666 的**（桌面进程要能写），别的内容理论上也能写进来 →
 *    解析在 [LauncherOverlaySignal.parse] 里严格校验，任何不合格式的内容一律当未知。
 *
 * ## 时钟
 *
 * 写入方用 `CLOCK_MONOTONIC`（native 侧），这里必须用**同一个钟**：
 * `System.nanoTime()` 就是 `CLOCK_MONOTONIC`；**不能用** `SystemClock.elapsedRealtime()`
 * （那是 `CLOCK_BOOTTIME`，含休眠时间，两者会漂）。
 */
object LauncherNativeOverlayTracker {

    /**
     * 轮询间隔。桌面侧写入节拍 ~100ms、心跳 1s；这里 150ms 跟得上。
     * 为什么要快：这条信号是「让位」用的（抽屉/文件夹/模态态一出现就把索引条收掉），
     * 延迟越大，「条先闪一下」越明显。150ms 已经把最坏延迟压到 ~250ms。
     */
    private const val POLL_MS = 150L

    @Volatile private var state: LauncherOverlaySignal.State? = null
    @Volatile private var running = false
    @Volatile private var lastDescribe: String = "overlay=unknown(未启动)"
    private var thread: Thread? = null

    /** 启动轮询。**主线程/安装线程调**。返回进安装报告的那一段。 */
    fun start(ctx: HookContext): String {
        if (running) return lastDescribe
        running = true
        val file = File(LauncherOverlaySignal.FILE_PATH)
        val t = Thread({ loop(ctx, file) }, "FP-OverlayPoll")
        t.isDaemon = true
        thread = t
        t.start()
        return lastDescribe
    }

    private fun loop(ctx: HookContext, file: File) {
        var lastLogged = ""
        while (running) {
            val parsed = try {
                if (file.canRead()) LauncherOverlaySignal.parse(file.readText()) else null
            } catch (t: Throwable) {
                null
            }
            state = parsed
            val nowMono = System.nanoTime() / 1_000_000L
            val d = LauncherOverlaySignal.describe(parsed, nowMono)
            lastDescribe = d
            if (d != lastLogged) {
                // 只在状态变化时打（轮询本身不打，免得把日志刷满）
                lastLogged = d
                ctx.log(Log.INFO, "侧滑索引：桌面遮挡信号 = $d")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** 输入分发线程只读这个。 */
    fun isOverlaySuppressed(): Boolean =
        running && LauncherOverlaySignal.blocks(state, System.nanoTime() / 1_000_000L)

    fun describe(): String = lastDescribe

    /** 关态清理。可从任何线程调，重复调安全。 */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        state = null
        lastDescribe = "overlay=unknown(未启动)"
    }
}
