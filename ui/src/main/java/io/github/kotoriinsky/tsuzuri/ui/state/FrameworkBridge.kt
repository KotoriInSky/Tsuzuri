package io.github.kotoriinsky.tsuzuri.ui.state

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/** 一个正在运行模块代码的进程。 */
data class TargetInfo(
    val processName: String,
    val pid: Int,
    val uid: Int,
    val state: HookedTarget.State,
    val loadedVersionCode: Long,
)

/**
 * 框架状态的三态。
 *
 * ⚠️ 这一层不是装饰：`connected == false` 其实有两种完全不同的含义 ——
 * 「还没问过框架」和「问了，框架说它不在」。把两者合并成一个空白界面，
 * 用户会以为模块坏了，而真正的原因可能只是「还没读完」。
 * 所以加载态必须有独立的、和空态不一样的字面。
 */
enum class FrameworkPhase {
    /** 还没有拿到框架的答复（刚启动 / 服务刚断，可能马上会连上）。 */
    LOADING,

    /** 拿到了答复，且连上了。 */
    READY,

    /** 确定拿不到：等待超时，或者绑定之后服务掉线。 */
    ERROR,
}

/**
 * 前端能看到的框架状态。
 *
 * 这里的每一项都来自框架的 Binder 服务（`io.github.libxposed:service`），
 * **不是**我们自己探测出来的 —— 所以它的可信度等同于 LSPosed 管理器里显示的信息。
 */
data class FrameworkStatus(
    val phase: FrameworkPhase = FrameworkPhase.LOADING,
    val connected: Boolean = false,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int = 0,
    val scope: Set<String> = emptySet(),
    val targets: List<TargetInfo> = emptyList(),
    val error: String? = null,
) {
    /**
     * 作用域里缺了哪些**必需**的进程。
     *
     * 判据是 [Catalog.settingsScopes]（界面可见特性的并集），**不是** `requiredScopes`
     * （后者含已下架特性）—— 否则首页会去要一个任何开关都不会用到的进程，
     * 用户勾完也永远看不出它有什么用。理由写在 `Catalog.settingsScopes` 的 KDoc 里。
     */
    val missingScopes: List<ScopeTarget>
        get() = ModuleScopes.declared.filter { it.packageName !in scope }

    /** 正在等框架答复 —— 界面这时候只能说「读取中」，不能说「未连接」。 */
    val isLoading: Boolean get() = phase == FrameworkPhase.LOADING

    val staleTargets: List<TargetInfo>
        get() = targets.filter { it.state == HookedTarget.State.STALE }

    val isFullyConfigured: Boolean
        get() = connected && missingScopes.isEmpty() && targets.isNotEmpty() && staleTargets.isEmpty()
}

/**
 * 与 LSPosed 框架的桥。
 *
 * 为什么做成全局单例而不是 ViewModel：`XposedServiceHelper.registerListener`
 * 是**进程级、只应调用一次**的 API（它内部缓存 binder，替换 listener 会丢掉旧的）。
 * 放进会重建的 ViewModel 里，旋转屏幕就会重新注册一遍。
 */
object FrameworkBridge {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private const val TAG = "FP-Bridge"

    /**
     * 等服务绑定的上限。
     *
     * 为什么需要它：`registerListener` 在**模块没启用 / LSPosed 没装**时
     * 不会有任何回调 —— 没有「没找到服务」这种事件。如果只按回调判断，
     * 界面会永远停在「读取中」。所以用时间兜底：超过这个时长还没绑上，
     * 就判定为错误态，让用户看到「连不上」而不是一个转不完的圈。
     */
    private const val BIND_TIMEOUT_MS = 4_000L

    private val _status = mutableStateOf(FrameworkStatus())
    val status: State<FrameworkStatus> = _status

    private val started = AtomicBoolean(false)
    private var service: XposedService? = null

    /** 开始等待的时刻，配合 [BIND_TIMEOUT_MS] 区分「还在读」与「读不到」。 */
    private var startedAtMillis = 0L

    /**
     * 框架给的原始 target 对象。
     *
     * 必须留着它本身，不能只用自己映射的 [TargetInfo]：
     * `HookedTarget` 的构造器是包内可见的，我们**造不出来**，
     * 而 `hotReloadModule()` 要的正是这个类型。
     */
    private var rawTargets: List<HookedTarget> = emptyList()

    /** 每个 [TargetInfo] 在 [rawTargets] 里的下标。 */
    private val targetIndex = HashMap<String, Int>()

    /** 在 MainActivity.onCreate 里调一次。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        startedAtMillis = SystemClock.elapsedRealtime()
        // 起始状态是 LOADING（FrameworkStatus 的默认值），不是 ERROR：
        // 刚启动时我们确实还不知道框架在不在。
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(svc: XposedService) {
                service = svc
                Log.i(TAG, "service bound: ${svc.frameworkName} ${svc.frameworkVersion}")
                ConfigStore.onFrameworkBound(svc)
                refresh()
            }

            override fun onServiceDied(svc: XposedService) {
                Log.w(TAG, "service died")
                service = null
                rawTargets = emptyList()
                targetIndex.clear()
                _status.value = FrameworkStatus(
                    phase = FrameworkPhase.ERROR,
                    connected = false,
                    error = "service died",
                )
                ConfigStore.onFrameworkLost()
            }
        })
    }

    val current: XposedService? get() = service

    /** 重新拉一次状态。任何一步失败都只降级、不抛异常。 */
    fun refresh() {
        val svc = service
        if (svc == null) {
            val waited = SystemClock.elapsedRealtime() - startedAtMillis
            val timedOut = waited >= BIND_TIMEOUT_MS
            _status.value = FrameworkStatus(
                phase = if (timedOut) FrameworkPhase.ERROR else FrameworkPhase.LOADING,
                connected = false,
                error = if (timedOut) "framework service not bound" else null,
            )
            return
        }
        _status.value = try {
            val raw = runCatching { svc.runningTargets.orEmpty() }.getOrDefault(emptyList())
            rawTargets = raw
            targetIndex.clear()
            raw.forEachIndexed { i, t -> targetIndex[t.processName] = i }
            FrameworkStatus(
                phase = FrameworkPhase.READY,
                connected = true,
                frameworkName = runCatching { svc.frameworkName }.getOrNull(),
                frameworkVersion = runCatching { svc.frameworkVersion }.getOrNull(),
                apiVersion = runCatching { svc.apiVersion }.getOrDefault(0),
                scope = runCatching { svc.scope?.toSet() ?: emptySet() }.getOrDefault(emptySet()),
                targets = raw.map { t ->
                    TargetInfo(
                        processName = t.processName,
                        pid = t.pid,
                        uid = t.uid,
                        state = t.state,
                        loadedVersionCode = t.loadedVersionCode,
                    )
                },
            )
        } catch (t: Throwable) {
            FrameworkStatus(
                phase = FrameworkPhase.ERROR,
                connected = false,
                error = t.javaClass.simpleName,
            )
        }
    }

    /**
     * 请求重载目标进程，让它重新读一次配置并重装 hook。
     *
     * 这条路比 `killall` 好在**不闪屏** —— 用户改完开关不用看着系统界面重启。
     * 失败时（老框架不支持、目标拒绝重载）只回报错误，不做任何补救动作。
     */
    /**
     * 一次「重载全部」的结果。
     *
     * 分开记 `ok` 与 `failed` 是因为**这个项目不开热重载**（
     * `autoHotReload=false`；热重载有三个前提，真重载靠 `killall` 重启进程）。
     * 也就是说这个接口在真机上大概率返回 UNSUPPORTED / FAILED ——
     * 界面**必须**照实说，一律报「已重载」是在宣称一件没发生的事。
     */
    data class ReloadReport(
        /** 下发过请求的进程数。 */
        val total: Int,
        /** 真正重载成功的。 */
        val ok: Int,
        /** 已回话且未成功的（失败 / 不支持 / 进程已死）。 */
        val failed: Int,
    ) {
        /** 发过请求、但还没回话的。 */
        val pending: Int get() = total - ok - failed
    }

    private val _reloadReport = mutableStateOf<ReloadReport?>(null)

    /** 最近一次「重载全部」的结果；`null` = 没按过。 */
    val reloadReport: State<ReloadReport?> = _reloadReport

    fun clearReloadReport() {
        _reloadReport.value = null
    }

    fun requestReload(processName: String?): Boolean {
        val idx = processName?.let { targetIndex[it] }
            ?: rawTargets.indexOfFirst { it.state == HookedTarget.State.STALE }
                .takeIf { it >= 0 }
            ?: 0
        val target = rawTargets.getOrNull(idx) ?: return false
        return hotReload(target)
    }

    /**
     * 重载**全部**目标进程（系统界面、负一屏、传送门……）。
     *
     * 为什么单独开一个：[requestReload] 传 `null` 只会挑**一个** —— 先看有没有 STALE 的、
     * 挑第一个；一个都没有就挑第 0 个。而首页右上角那个按钮的意图是
     * 「改完参数，让所有相关进程都重读一遍」；只重载其中一个，剩下的仍按旧配置跑，
     * 症状正是「我按了重载，那一项还是老样子」—— 和没生效长得一模一样。
     *
     * @return 实际下发重载请求的进程数。`0` = 框架没连上，或当前一个目标都没有
     *         （这两种都不能当成成功，界面上要说清楚）。
     */
    fun requestReloadAll(): Int {
        if (service == null) {
            _reloadReport.value = ReloadReport(0, 0, 0)
            return 0
        }
        val total = rawTargets.size
        if (total == 0) {
            _reloadReport.value = ReloadReport(0, 0, 0)
            return 0
        }
        // 先放一个「都还没回话」的结果：回调是异步的。
        // 不在点击那一刻就写「成功」—— 那样界面会先闪一句成功、再被改成失败，
        // 而用户只会记得先看到的那句。
        _reloadReport.value = ReloadReport(total, 0, 0)
        val lock = Any()
        var ok = 0
        var done = 0
        for (target in rawTargets) {
            val posted = hotReload(target) { success ->
                val snap = synchronized(lock) {
                    if (success) ok++
                    done++
                    ReloadReport(total, ok, done - ok)
                }
                mainHandler.post { _reloadReport.value = snap }
            }
            if (!posted) {
                val snap = synchronized(lock) {
                    done++
                    ReloadReport(total, ok, done - ok)
                }
                mainHandler.post { _reloadReport.value = snap }
            }
        }
        return total
    }

    /**
     * 等 [requestReloadAll] 的结果回来（最多 [timeoutMs]）。
     *
     * 为什么需要「等」：`hotReloadModule` 的回调是异步的，页面若在点击那一刻就写结果，
     * 只能写成「已请求」；而用户要的是「到底生效了没」。超时也是结果 ——
     * 超时按「未回话」计入失败，不猜成功。
     */
    suspend fun reloadAllAwait(timeoutMs: Long = 3_000L): ReloadReport {
        val total = requestReloadAll()
        if (total == 0) return ReloadReport(0, 0, 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val r = _reloadReport.value
            if (r != null && r.total == total && r.pending == 0) return r
            kotlinx.coroutines.delay(100)
        }
        val r = _reloadReport.value ?: ReloadReport(total, 0, 0)
        return r.copy(failed = r.failed + r.pending)
    }

    /** 框架标成「还在跑旧代码」的进程名。root 重启优先处理这一批。 */
    fun staleProcessNames(): List<String> =
        rawTargets.filter { it.state == HookedTarget.State.STALE }.map { it.processName }

    /** 当前所有加载了本模块的进程名。 */
    fun runningProcessNames(): List<String> = rawTargets.map { it.processName }

    /**
     * @param onResult 结果回来时回调（`true` = SUCCEEDED）。**在 binder 线程上调用**，
     *                 所以调用方要自己切回主线程再改 UI 状态。
     */
    private fun hotReload(target: HookedTarget, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val svc = service ?: return false
        return try {
            svc.hotReloadModule(
                target,
                null,
                object : XposedService.HotReloadCallback {
                    override fun onHotReloadResult(t: HookedTarget, result: HotReloadResult) {
                        val ok = result.status() == HotReloadResult.Status.SUCCEEDED
                        Log.i(TAG, "hotReload " + t.processName + " -> " + result.status())
                        onResult?.invoke(ok)
                        refresh()
                    }
                },
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "hotReload failed", t)
            false
        }
    }

    /**
     * 请求把缺的作用域补上。
     *
     * 框架会弹确认框 —— **用户点了才生效**。模块代码不能给自己偷偷扩权，
     * 这是规范 §8「最小作用域」原则的一部分。
     *
     * `OnScopeEventListener` 两个方法都有 default 实现，所以它在 Kotlin 里
     * **不是** SAM，必须写成匿名对象，不能用 lambda。
     */
    fun requestScopes(packages: List<String>, onResult: (Boolean, String?) -> Unit) {
        val svc = service ?: run {
            onResult(false, "no service")
            return
        }
        try {
            svc.requestScope(
                packages,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        refresh()
                        val missing = packages.filter { it !in approved }
                        onResult(missing.isEmpty(), if (missing.isEmpty()) null else "部分授权未通过：" + missing.joinToString())
                    }

                    override fun onScopeRequestFailed(message: String) {
                        onResult(false, message)
                    }
                },
            )
        } catch (t: Throwable) {
            onResult(false, t.javaClass.simpleName)
        }
    }
}
