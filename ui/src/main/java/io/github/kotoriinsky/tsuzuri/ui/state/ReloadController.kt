package io.github.kotoriinsky.tsuzuri.ui.state

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「让改动生效」的**唯一入口**：先走框架热重载，不行再用 root 重启进程。
 *
 * 为什么要分成两级：
 *  - LSPosed 2.2.0 / API 102 提供了 `hotReloadModule()`，但它有三个前提（单一 java 入口、
 *    重写 `onHotReloading()`、在 `onHotReloaded()` 里重装 hook）。本模块目前**只满足一部分**，
 *    真机上它经常回 UNSUPPORTED / FAILED —— 所以界面不能把「点了重载」写成成功；
 *  - 有 root 时可以退到最可靠的一条：`killall <进程>`，进程由系统重新拉起，
 *    新配置和新代码一定生效（代价是那几秒黑屏）。
 *
 * 本类只负责「做」和「如实回报」，不决定文案。三种结果（成功 / 部分 / 完全失败）
 * 由调用方按 [Outcome] 自己表达 —— 文案在页面里，判定在这里。
 */
object ReloadController {

    private const val TAG = "FP-Reload"

    /** 一次重载的全部结果。 */
    data class Outcome(
        /** 框架给了几个目标。0 = 框架没连上或当前没有目标进程。 */
        val total: Int,
        /** 热重载成功数。 */
        val hotOk: Int,
        /** 热重载失败 + 超时未回话的数量。 */
        val hotFailed: Int,
        /** 本机 root 状态（用来决定要不要给「用 Root 重启」这个入口）。 */
        val root: Su.State,
        /** 这次真的用 root 重启了几个进程。 */
        val rootRestarted: Int,
    ) {
        /** 全部目标都已换上新代码 —— 只有这一种能说「已生效」。 */
        val fullyReloaded: Boolean get() = total > 0 && hotFailed == 0 && rootRestarted == 0

        /** 至少有一个进程换上了新代码。 */
        val anyReloaded: Boolean get() = hotOk > 0 || rootRestarted > 0
    }

    /**
     * 重载全部目标。热重载失败时**不自动**用 root 杀进程：
     * 那会让系统界面黑一下，必须是用户明确要求的动作（[restartWithRoot] 单独暴露）。
     */
    suspend fun reloadAll(): Outcome {
        val report = FrameworkBridge.reloadAllAwait()
        val root = Su.lastKnown ?: Su.probe()
        Log.i(
            TAG,
            "重载：total=${report.total} ok=${report.ok} failed=${report.failed} pending=${report.pending} root=$root",
        )
        return Outcome(
            total = report.total,
            hotOk = report.ok,
            hotFailed = report.failed + report.pending,
            root = root,
            rootRestarted = 0,
        ).also { if (it.anyReloaded) ConfigStore.clearPendingReload() }
    }

    /**
     * 用 root 重启进程。**调用前必须已经让用户确认过**（会黑屏/重启系统界面）。
     *
     * 杀谁：
     *  - 优先杀框架标成 STALE 的目标（它明确在跑旧代码）；
     *  - 一个 STALE 都没有时，杀**全部**目标进程 —— 用户按这个按钮的意图就是
     *    「我不管，全部重新来一遍」，杀一半会让他再按一次。
     *
     * @return 真的尝试重启的进程数；0 = 没 root、没目标，或命令失败。
     */
    suspend fun restartWithRoot(): Pair<Int, Su.State> = withContext(Dispatchers.IO) {
        val root = Su.probe()
        if (root != Su.State.OK) return@withContext 0 to root
        val stale = FrameworkBridge.staleProcessNames()
        val names = (stale.ifEmpty { FrameworkBridge.runningProcessNames() }).distinct()
        if (names.isEmpty()) return@withContext 0 to root
        // killall 一次杀掉，避免逐条 su（每条都要过一次授权，慢且会弹多次提示）
        val result = Su.exec("killall " + names.joinToString(" "))
        val restarted = if (result == Su.State.OK) names.size else 0
        if (restarted > 0) ConfigStore.clearPendingReload()
        Log.i(TAG, "root 重启：${names.joinToString()} -> $result（$restarted 个）")
        // 进程由系统拉起需要一点时间；太早 refresh 会读到「目标没了」。
        delay(1_500)
        FrameworkBridge.refresh()
        restarted to root
    }
}
