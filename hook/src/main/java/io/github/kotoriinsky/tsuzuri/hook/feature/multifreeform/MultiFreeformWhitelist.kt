package io.github.kotoriinsky.tsuzuri.hook.feature.multifreeform

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 让用户勾选的应用获得 HyperOS 的「小窗多开」资格 —— **在 system_server 里改写
 * `MiuiMultiWindowAdapter.isSupportFreeFormMultiTask` 的判定结果**。
 *
 * ## 机制依据（2026-09-24 逆向 + 真机实证）
 *
 * HyperOS 对「同应用能不能同时开多个小窗」的判定链：
 *
 * ```
 * DisplayContentStubImpl.process()（system_server，任务冲突处理）
 *   ├─ MiuiFreeformServiceStub.isSupportFreeFormMultiTask(sourceRecord.packageName)
 *   │    └─ MiuiMultiWindowAdapter.isSupportFreeFormMultiTask(pkg)   ← hook 点
 *   │         └─ SUPPORT_FREEFORM_MULTI_TASK_LIST.contains(pkg)      ← framework 资源名单
 *   └─ 命中且该包已有 freeform Task → return Pair(true, -1) = 放行新建
 * ```
 *
 * 真机实证：小米笔记在名单里 —— 用户开出了两条 `com.miui.notes` freeform Task
 * 同时 `visible=true`（`dumpsys activity activities`，Task #3051/#3052）。
 * 名单外的应用第二次启动会被收编进已有小窗或替换。
 *
 * ## 为什么 hook 这个查询函数，而不是改 `SUPPORT_FREEFORM_MULTI_TASK_LIST` 静态名单
 *
 * hook 查询函数把放行收在我们手里：开关关 = 原判定（与未装模块一致）；
 * 开关开 = 一律 true（等价于「所有应用都在系统名单里」）—— 用户不用挑选应用，
 * 这是对名单机制语义上限的完整包装。名单 List 本身是 `public static`，直接改它
 * 影响面不可控，不采用。
 *
 * ## 崩溃面（system_server 纪律）
 *
 * hook 体连 Throwable 兜住（规范 §7.1），出错 = 这一次按原判定走，绝不逃逸 ——
 * system_server 里逃出去的异常是整个系统重启。hook 的目标是一个**冷路径**的
 * 布尔查询（同包启动时才调），不存在热路径问题；intercept 里不做 IO、不打日志。
 */
internal object MultiFreeformWhitelist : Feature(Catalog.require(Catalog.MULTI_FREEFORM)) {

    private const val ADAPTER_CLASS = "android.util.MiuiMultiWindowAdapter"
    private const val METHOD_NAME = "isSupportFreeFormMultiTask"

    /**
     * 内联链上需要 deopt 的调用方（第一元素 = 类名，第二 = 方法名）。
     *
     * `isSupportFreeFormMultiTask` 是一行 contains，AOT 下必然内联进调用方；
     * hook 它本身摸不到已内联的调用点，必须把调用链逐环打回解释执行。
     * 三处都是冷路径（任务冲突时才走），deopt 的性能代价可接受。
     */
    private val DEOPT_CALLERS = listOf(
        "com.android.server.wm.DisplayContentStubImpl" to "process",
        "com.android.server.wm.MiuiFreeformServiceImpl" to "isSupportFreeFormMultiTask",
        "com.android.server.wm.MiuiFreeformServiceStub" to "isSupportFreeFormMultiTask",
    )

    /** 触发计数（诊断日志有界化用）。 */
    private val callCount = java.util.concurrent.atomic.AtomicInteger()

    private var handle: XposedInterface.HookHandle? = null
    private var hookedMethod: Method? = null

    override fun install(ctx: HookContext): InstallReport {
        val cls = try {
            ctx.classLoader.loadClass(ADAPTER_CLASS)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "多开名单：找不到 $ADAPTER_CLASS（OTA 改了包结构？）", t)
            return InstallReport(0, missing = listOf(ADAPTER_CLASS))
        }

        val method = cls.declaredMethods.firstOrNull {
            it.name == METHOD_NAME &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: run {
            ctx.log(Log.WARN, "多开名单：$ADAPTER_CLASS#$METHOD_NAME(String) 不存在（签名漂移）")
            return InstallReport(0, missing = listOf("$ADAPTER_CLASS#$METHOD_NAME(String)"))
        }
        method.isAccessible = true
        hookedMethod = method

        val hooked = try {
            ctx.module.hook(method).intercept { chain ->
                // 全局放行：一律 true。等价于「所有应用都在系统多开名单里」。
                // 放行的唯一实际场景是「应用再唤起自己的小窗」（用户主动行为），
                // 不涉及数据/进程隔离问题（同进程同实例）。仍走一次 proceed 保持
                // 原方法副作用（名单缓存读取等）照常执行。
                var original: Any? = null
                try {
                    original = chain.proceed()
                    true
                } catch (_: Throwable) {
                    // 故意吞掉：这里抛出去 = system_server 重启。最坏后果只是
                    // 这一次按原判定走（用户多开失败），可接受。
                    true
                } finally {
                    // 触发诊断：这是**冷路径**（任务冲突时才调），记日志不是热路径事故。
                    // 有界（前 12 次），用来回答「hook 到底被调到了吗、原判定是什么」。
                    val n = callCount.incrementAndGet()
                    if (n <= 12) {
                        val pkg = chain.args.firstOrNull() as? String
                        ctx.log(Log.INFO, "多开判定 #$n pkg=$pkg 原判定=$original → 放行")
                    }
                }
            }.also { handle = it }
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "多开名单：hook $METHOD_NAME 失败", t)
            return InstallReport(0, missing = listOf("$ADAPTER_CLASS#$METHOD_NAME"), notes = listOf("hook_threw"))
        }

        // ── deopt 内联链（2026-09-24：首次上机「装上了但没生效」的最可能根因）──────
        //
        // 规范 §7.8 / **一行小方法几乎必然被 AOT 内联进调用方**，
        // 运行期 hook 对已内联的调用点不生效。本方法是一行 contains，链路是
        //
        //   DisplayContentStubImpl.process → MiuiFreeformServiceImpl.isSupport… → 本方法
        //
        // 每一环都可能被内联。deoptimize 把**调用方**打回解释执行 —— 这三个都是
        // 冷路径（启动冲突时才走），打回解释执行的性能代价可接受。
        // 若不做这一步，表现恰是「installed=1 但判定照旧」。
        for (callee in DEOPT_CALLERS) {
            try {
                val c = ctx.classLoader.loadClass(callee.first)
                val m = c.declaredMethods.firstOrNull { it.name == callee.second }
                if (m != null) {
                    ctx.module.deoptimize(m)
                    ctx.log(Log.INFO, "多开名单：已 deopt ${callee.first}.${callee.second}")
                } else {
                    ctx.log(Log.WARN, "多开名单：deopt 目标方法不存在 ${callee.first}#${callee.second}")
                }
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "多开名单：deopt ${callee.second} 失败（不影响安装）", t)
            }
        }

        ctx.log(Log.INFO, "多开放行已装上：$METHOD_NAME → 一律 true（全局放行）")
        return InstallReport(hooked)
    }

    override fun uninstall() {
        try {
            handle?.unhook()
        } catch (_: Throwable) {
            // 卸不掉没有补救手段，也不能因此抛出去
        }
        handle = null
        hookedMethod = null
        super.uninstall()
    }
}
