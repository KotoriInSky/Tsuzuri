package io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.LauncherOverlaySignal
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 桌面遮挡信号的**读取侧**：接收桌面进程内 `libfpnative.so` 推送的模态态快照
 * （抽屉 / 文件夹 / 编辑态 / 小部件预览 / 堆叠展开）。
 *
 * 与 [LauncherDrawerTracker] 的关系：那个是「从外面猜」（system_server 侧看 WMS `softInputMode`），
 * 这个是「桌面自己报」（hook `DesktopServiceImpl.notify*`）。两条并行，任一说被遮挡就抑制。
 *
 * ## 传输通道（单包零 root）
 *
 * 1. **主通道（本地回环 UDP）**：绑定 `127.0.0.1:49731`，事件驱动收包（变化延迟 <50ms、空闲 0 CPU）；
 *    不依赖 `/data/local/tmp` 预建 0666 文件。
 * 2. **兼容兜底（共享文件）**：若 UDP 绑定失败或超期未收到包且旧路径 `/data/local/tmp/fp_launcher_state.txt`
 *    可读，则退回文件读取。
 *
 * ## 三条纪律
 *
 * 1. **后台线程只干接收与解析**：把结果放进 `@Volatile`；输入分发线程只读该 volatile + 单调钟。
 * 2. **收不到 / 过期 = 未知 = 不抑制**（fail-closed 到「照弹」）。
 * 3. **严格校验**：任何不合格式的数据一律丢弃（[LauncherOverlaySignal.parse]）。
 */
object LauncherNativeOverlayTracker {

    private const val FILE_POLL_MS = 150L
    private const val UDP_SO_TIMEOUT_MS = 1500

    @Volatile private var state: LauncherOverlaySignal.State? = null
    @Volatile private var running = false
    @Volatile private var lastDescribe: String = "overlay=unknown(未启动)"
    @Volatile private var udpSocket: DatagramSocket? = null
    private var thread: Thread? = null

    /** 启动监听。**主线程/安装线程调**。返回进安装报告的那一段。 */
    fun start(ctx: HookContext): String {
        if (running) return lastDescribe
        running = true
        val file = File(LauncherOverlaySignal.FILE_PATH)
        val t = Thread({ loop(ctx, file) }, "FP-OverlayRecv")
        t.isDaemon = true
        thread = t
        t.start()
        return lastDescribe
    }

    private fun loop(ctx: HookContext, file: File) {
        val sock = openUdpSocket(ctx)
        if (sock != null) {
            udpSocket = sock
            udpLoop(ctx, sock, file)
            return
        }
        filePollLoop(ctx, file)
    }

    private fun openUdpSocket(ctx: HookContext): DatagramSocket? = try {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.soTimeout = UDP_SO_TIMEOUT_MS
        s.bind(InetSocketAddress(InetAddress.getByName(LauncherOverlaySignal.UDP_HOST), LauncherOverlaySignal.UDP_PORT))
        ctx.log(Log.INFO, "侧滑索引：桌面遮挡 UDP 接收已就绪 (${LauncherOverlaySignal.UDP_HOST}:${LauncherOverlaySignal.UDP_PORT})")
        s
    } catch (t: Throwable) {
        ctx.log(Log.WARN, "侧滑索引：桌面遮挡 UDP 绑定失败，退回文件轮询 (${t.javaClass.simpleName}: ${t.message})")
        null
    }

    private fun udpLoop(ctx: HookContext, sock: DatagramSocket, fallbackFile: File) {
        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)
        var lastLogged = ""
        try {
            while (running && !sock.isClosed) {
                try {
                    pkt.length = buf.size
                    sock.receive(pkt)
                    val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    val parsed = LauncherOverlaySignal.parse(text)
                    if (parsed != null) {
                        state = parsed
                        lastLogged = updateDescribe(ctx, parsed, "udp", lastLogged)
                    }
                } catch (_: SocketTimeoutException) {
                    // 定期刷新 stale 描述；若旧兼容文件存在且当前 UDP 无新鲜信号则兜底读一次
                    val nowMono = System.nanoTime() / 1_000_000L
                    var cur = state
                    if (LauncherOverlaySignal.isStale(cur, nowMono)) {
                        val fileParsed = readFallbackFile(fallbackFile)
                        if (fileParsed != null && !LauncherOverlaySignal.isStale(fileParsed, nowMono)) {
                            state = fileParsed
                            cur = fileParsed
                        }
                    }
                    lastLogged = updateDescribe(ctx, cur, "udp", lastLogged)
                } catch (_: Throwable) {
                    if (!running || sock.isClosed) return
                }
            }
        } finally {
            runCatching { sock.close() }
            if (udpSocket === sock) udpSocket = null
        }
    }

    private fun filePollLoop(ctx: HookContext, file: File) {
        var lastLogged = ""
        while (running) {
            val parsed = readFallbackFile(file)
            state = parsed
            lastLogged = updateDescribe(ctx, parsed, "file", lastLogged)
            try {
                Thread.sleep(FILE_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readFallbackFile(file: File): LauncherOverlaySignal.State? = try {
        if (file.canRead()) LauncherOverlaySignal.parse(file.readText()) else null
    } catch (_: Throwable) {
        null
    }

    private fun updateDescribe(
        ctx: HookContext,
        parsed: LauncherOverlaySignal.State?,
        ch: String,
        lastLogged: String,
    ): String {
        val nowMono = System.nanoTime() / 1_000_000L
        val d = LauncherOverlaySignal.describe(parsed, nowMono)
        lastDescribe = d
        // 日志去重：忽略纯心跳带来的 seq 递增，只在遮挡位或通道健康态变化时记录
        val logKey = d.replace(Regex(" seq=\\d+"), "")
        if (logKey != lastLogged) {
            ctx.log(Log.INFO, "侧滑索引：桌面遮挡信号($ch) = $d")
            return logKey
        }
        return lastLogged
    }

    /** 输入分发线程只读这个。 */
    fun isOverlaySuppressed(): Boolean =
        running && LauncherOverlaySignal.blocks(state, System.nanoTime() / 1_000_000L)

    fun describe(): String = lastDescribe

    /** 关态清理。可从任何线程调，重复调安全。 */
    fun stop() {
        running = false
        val s = udpSocket
        udpSocket = null
        if (s != null) runCatching { s.close() }
        thread?.interrupt()
        thread = null
        state = null
        lastDescribe = "overlay=unknown(未启动)"
    }
}
