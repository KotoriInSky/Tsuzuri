package io.github.kotoriinsky.tsuzuri.ui.state

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 本应用能不能用 root，以及怎么用 —— **只在这一处判定**。
 *
 * 为什么需要它：这是 LSPosed 模块，目标设备必然有 KernelSU / Magisk / APatch 之一。
 * 有 root 就能做框架热重载做不到的事：**直接重启被 hook 的进程**，让新配置与新代码
 * 立刻生效（系统界面/负一屏重启一次是几秒的黑屏，但它是「现在就要生效」的唯一办法）。
 *
 * 三种结果必须分开，因为对用户的意义完全不同：
 *  - [State.OK]：能跑；
 *  - [State.DENIED]：su 在，但本应用还没在 Root 管理器里放行 —— 用户点一下就能解决；
 *  - [State.ABSENT]：这台设备没有 root（或 su 被藏了）—— 只能走框架热重载那条路。
 * 合成一句「需要 root」会把可解决的那一种也变成无解。
 */
object Su {

    private const val TAG = "FP-Su"

    /** su 的候选路径（KernelSU / Magisk / APatch 的常见落点；`su` 那条交给 PATH）。 */
    private val PATHS = listOf("/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "su")

    /** 一次探测 / 一条命令的结果。 */
    enum class State { OK, DENIED, ABSENT }

    @Volatile
    private var cached: State? = null

    /**
     * 跑一条 root 命令（**阻塞**，必须在 IO 线程调用）。
     *
     * 先把 stdout 读干净再 `waitFor`：反过来的话，输出超过管道缓冲就会把进程挂住，
     * 而这里在 IO 线程上等，表现为「一直在转」。
     */
    fun exec(command: String, timeoutSeconds: Long = 5L): State {
        var sawPermissionDenied = false
        for (bin in PATHS) {
            try {
                val p = ProcessBuilder(bin, "-c", command).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                val code = if (finished) p.exitValue() else -1
                if (finished && code == 0) {
                    if (out.isNotBlank()) Log.i(TAG, "$bin: ${out.trim().take(200)}")
                    return State.OK
                }
                // 起得来但退出码非 0：可能是这条命令本身失败，也可能是 su 拒绝
                // （KernelSU 拒绝时通常直接结束进程、退出码非 0）。看输出判。
                val denied = out.contains("denied", ignoreCase = true) ||
                    out.contains("permission", ignoreCase = true)
                Log.w(TAG, "$bin 退出码 $code，输出：${out.trim().take(200)}")
                if (denied) sawPermissionDenied = true else return State.DENIED
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                if (msg.contains("denied", ignoreCase = true) || msg.contains("error=13")) {
                    sawPermissionDenied = true
                }
                Log.w(TAG, "$bin 起不来 ${t.javaClass.simpleName}：$msg")
            }
        }
        return if (sawPermissionDenied) State.DENIED else State.ABSENT
    }

    /** 探测一次并缓存。`force = true` 时重新探测（用户在 Root 管理器里刚放行）。 */
    suspend fun probe(force: Boolean = false): State = withContext(Dispatchers.IO) {
        cached?.takeIf { !force }?.let { return@withContext it }
        val r = exec("id -u", timeoutSeconds = 3L)
        cached = r
        r
    }

    /** 供界面复用的上次探测结果；`null` = 还没探测过。 */
    val lastKnown: State? get() = cached
}
