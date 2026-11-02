package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小窗里**同应用跳转**时不再错位 —— 扇形小窗的 system_server 那一半。
 *
 * ## 现象（2026-11-02 真机，Xiaomi 17 Pro OS4.0.4.0）
 *
 * 扇形里点「支付宝 付款码」（shortcut `1002`），小窗贴着屏幕右边出来。logcat：
 *
 * ```
 * Task #2215 ShortcutsLauncherActivity   mLaunchBounds=Rect(183, 289 - 1403, 2241)   ← 我们给的，居中
 * MiuiFreeformServiceImpl: addLaunchFreeformActivityOptionIfNeed:: ... getLaunchBounds()Rect(347, 432 - 1567, 2384)
 * Task #2216 SchemeLauncherActivity      Rect(347, 432 - 1567, 2384)
 * Task #2217 OnsitepayActivity（付款码）  Rect(347, 432 - 1567, 2384)                 ← 右边缘 347+1220×0.7=1201 / 屏宽 1220
 * ```
 *
 * 快捷方式只是个跳板（ShortcutsLauncherActivity 马上 finish），真正的页面在支付宝自己
 * 从小窗里再起的新 Task 里。
 *
 * ## 根因（`research/decompiled/miui-services/.../MiuiFreeformServiceImpl.java:436`）
 *
 * 从小窗里启动**同包**的新 Task 时，MIUI 用
 * `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true)` 重新算一个默认位置。
 * 这时跳板小窗还在屏上，默认位置会**避开它**，于是错开了 (+164, +143)。
 * 真正显示的页面继承的就是这个错开的位置。
 *
 * 同一个方法里 MIUI 已经有「目标 Task 本身就是小窗 → 沿用它的 bounds 与 scale」的分支
 * （:441 `keep bounds`），这里补的是跳板这一种：
 *
 * **来源是刚开（[YOUNG_MS] 内）的同包小窗时，新 Task 直接用来源小窗的 bounds 与 freeformScale。**
 *
 * ## 为什么在 system_server 而不是事后在 SystemUI 挪回去
 *
 * 事后挪（WCT.setBounds）会让用户看到小窗先在错位处出现、再跳一下。
 * 在这里改 options，新 Task 一出生就在原位，没有跳动。
 *
 * ## 为什么用「刚开」而不是「是我们开的」做闸门
 *
 * 跳板的 START 是目标应用自己发的（callingPackage = 支付宝），system_server 这边
 * 不经 Binder 就认不出「源头是 Tsuzuri」。而跳板在 1 秒内就会起下一个 Task，
 * 用户自己开着一个小窗、几秒后再从里面开第二个窗口时，来源早已不「刚开」，
 * MIUI 原本的错开逻辑照常生效。所以这条规则对任何来源的跳板都成立，不只是扇形。
 *
 * ## 跳板 Task 的清理（同日第二轮真机）
 *
 * 位置对齐之后，跳板 Task（支付宝 `SchemeLauncherActivity`）正好压在付款码小窗底下。
 * 它自己不画内容，屏上是 MIUI 的 Task 底色（深色模式下是黑的）；支付宝约 2 秒后才
 * 自己 finish 它，MIUI 再给它播关闭动画。用户 2 秒内关掉付款码小窗，就会先露出一块黑底、
 * 再看它缩小消失（logcat 13:11：Task 2254 在付款码关闭后 0.5 秒才 finish → startCloseAnimation）。
 *
 * 在 SystemUI 里藏它的 leash 不管用：shell 每次起 transition 都会 `show(leash)`。
 * 所以在这里收尾：跳板 Task 还在、栈顶仍是当初那个跳板 Activity、且前台是同包的另一个 Task
 * 时，`removeTask` 掉它。三条任一不满足就不动。
 *
 * **时机 = 同包新页面画出第一帧**（`ActivityRecord.onFirstWindowDrawn`，真机约 0.2 秒）。
 * 再早不行：新页面还没画，移走跳板会露出一瞬空白。那一刻新页面已经盖住跳板，
 * 跳板的关闭动画在底下播，用户看不见。[CLEANUP_DELAY_MS] 只是兜底（首帧信号没接上时）。
 *
 * ## 崩溃面
 *
 * 每次 Activity 启动都会经过这个方法，所以：先 proceed 拿到 MIUI 的结果，
 * 再在 try 里改；任何异常都吞掉、原样返回 MIUI 的结果。不做 Binder 调用、不做 IO。
 * 反射成员第一次用时解析并缓存，解析不到就整条规则停用（只记一次日志）。
 */
internal object FreeformTrampolinePlacement : Feature(Catalog.require(Catalog.CORNER_SMALL_WINDOW)) {

    private const val SERVICE_IMPL = "com.android.server.wm.MiuiFreeformServiceImpl"
    private const val METHOD = "addLaunchFreeformActivityOptionIfNeed"
    private const val DESKTOP_UTILS = "android.util.MiuiDesktopModeUtils"
    private const val WINDOWING_MODE_FREEFORM = 5

    /** 来源小窗「刚开」的上限。真机跳板链在 0.1~0.2 秒内完成，3 秒留足余量。 */
    private const val YOUNG_MS = 3_000L

    /** 兜底：首帧信号没接上时，0.7 秒后新页面一定已经盖住跳板了。 */
    private const val CLEANUP_DELAY_MS = 700L

    /** 生效日志只打前几次（这是每次 Activity 启动都会过的路径）。 */
    private const val LOG_LIMIT = 12

    override val installId: String get() = "corner_small_window:trampoline"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

    /** 扇形小窗或聚合支付任一开着就装：两者都靠快捷方式跳板开小窗。 */
    override fun wanted(prefs: Prefs): Boolean = active(prefs)

    private fun active(prefs: Prefs) =
        prefs.isEnabled(Catalog.CORNER_SMALL_WINDOW) || prefs.isEnabled(Catalog.AGG_PAYMENT)

    private val hits = AtomicInteger()

    @Volatile
    private var members: Members? = null

    @Volatile
    private var broken = false

    private val cleanups = AtomicInteger()

    private val cleaner: Handler by lazy {
        Handler(HandlerThread("fp-trampoline").apply { start() }.looper)
    }

    /** 等着被清理的跳板，按包名分组。首帧或兜底谁先到谁处理，处理完就摘掉。 */
    private class Pending(val task: Any, val source: Any, val pkg: String)

    private val pending = ConcurrentHashMap<String, MutableList<Pending>>()

    override fun install(ctx: HookContext): InstallReport {
        val cls = try {
            ctx.classLoader.loadClass(SERVICE_IMPL)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "小窗跳板：找不到 $SERVICE_IMPL", t)
            return InstallReport(0, missing = listOf(SERVICE_IMPL))
        }
        val method = cls.declaredMethods.firstOrNull { it.name == METHOD && it.parameterTypes.size == 8 }
            ?: return InstallReport(0, missing = listOf("$SERVICE_IMPL#$METHOD/8"))
        method.isAccessible = true

        val firstDrawn = installFirstDrawn(ctx)

        track(ctx.module.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                if (!broken && result is ActivityOptions && active(HookRegistry.prefs())) {
                    adjust(ctx, cls, result, chain.args)
                }
            } catch (t: Throwable) {
                // 吞掉：这里抛出去等于 system_server 重启。最坏后果是这一次照 MIUI 原样错开。
                if (hits.get() < LOG_LIMIT) ctx.log(Log.WARN, "小窗跳板：处理异常，按 MIUI 原结果放行", t)
            }
            result
        })
        return InstallReport(
            1 + firstDrawn,
            notes = if (firstDrawn == 0) listOf("首帧钩子缺失，跳板只靠 ${CLEANUP_DELAY_MS}ms 兜底清理") else emptyList(),
        )
    }

    /** 同包另一个页面画出第一帧 → 立刻清它底下的跳板。这是每个 Activity 首帧都会过的路径，没登记就直接返回。 */
    private fun installFirstDrawn(ctx: HookContext): Int {
        val method = runCatching {
            ctx.classLoader.loadClass("com.android.server.wm.ActivityRecord").declaredMethods
                .firstOrNull { it.name == "onFirstWindowDrawn" && it.parameterTypes.size == 1 }
        }.getOrNull() ?: run {
            ctx.log(Log.WARN, "小窗跳板：没找到 ActivityRecord#onFirstWindowDrawn，只用兜底清理")
            return 0
        }
        method.isAccessible = true
        track(ctx.module.hook(method).intercept { chain ->
            val result = chain.proceed()
            if (pending.isNotEmpty()) {
                try {
                    val m = members
                    val drawn = chain.thisObject
                    if (m != null && drawn != null) {
                        val list = pending[m.packageName.get(drawn) as? String]
                        if (list != null) {
                            val task = m.getTask.invoke(drawn)
                            val due = synchronized(list) {
                                list.filter { it.task !== task }.also { list.removeAll(it) }
                            }
                            for (p in due) cleaner.post { cleanup(ctx, m, p, "首帧") }
                        }
                    }
                } catch (_: Throwable) {
                    // 吞掉：首帧路径上抛出去等于 system_server 重启；没清掉还有兜底
                }
            }
            result
        })
        return 1
    }

    /**
     * 参数顺序见 MIUI 源码：(root, callingPackage, options, intent, sourceRecord, targetTask, caller, realCallingPid)。
     */
    private fun adjust(ctx: HookContext, cls: Class<*>, result: ActivityOptions, args: List<Any?>) {
        val m = members ?: resolve(ctx, cls) ?: return
        if (m.getLaunchWindowingMode.invoke(result) as? Int != WINDOWING_MODE_FREEFORM) return
        val current = result.launchBounds ?: return
        if (m.isDesktopActive?.invoke(null) == true) return

        // 目标 Task 本身已是小窗时 MIUI 自己沿用了它的 bounds（:441），不用我们管
        val targetTask = args.getOrNull(5)
        if (targetTask != null && m.getWindowingMode.invoke(targetTask) == WINDOWING_MODE_FREEFORM) return

        val startPackage = (args.getOrNull(3) as? Intent)?.let { it.component?.packageName ?: it.`package` } ?: return
        val source = sourceActivity(m, cls, args) ?: return
        val sourceTask = m.getTask.invoke(source) ?: return
        if (m.getWindowingMode.invoke(sourceTask) != WINDOWING_MODE_FREEFORM) return
        if (m.packageName.get(source) != startPackage) return

        val root = m.getRootActivity.invoke(sourceTask) ?: return
        val age = System.currentTimeMillis() - m.createTime.getLong(root)
        if (age !in 0..YOUNG_MS) return

        // 不论 bounds 要不要改都排一次清理：第二跳（Scheme → 付款码）MIUI 自己就沿用了位置，
        // 但留在底下的正是那个会露黑底的 Scheme 跳板
        val p = Pending(sourceTask, source, startPackage)
        val list = pending.getOrPut(startPackage) { mutableListOf() }
        synchronized(list) { list.add(p) }
        cleaner.postDelayed({
            val due = synchronized(list) { list.remove(p) }
            if (due) cleanup(ctx, m, p, "兜底")
        }, CLEANUP_DELAY_MS)

        val bounds = m.getBounds.invoke(sourceTask) as? Rect ?: return
        if (bounds.isEmpty || bounds == current) return
        val rootTaskId = m.getRootTaskId.invoke(sourceTask) as Int
        val scale = m.freeformScaleOf(sourceTask, rootTaskId)

        result.setLaunchBounds(Rect(bounds))
        if (scale != null && scale > 0f) {
            m.getInjector.invoke(result)?.let { m.setFreeformScale.invoke(it, scale) }
        }
        val n = hits.incrementAndGet()
        if (n <= LOG_LIMIT) {
            ctx.log(
                Log.INFO,
                "小窗跳板 #$n：$startPackage 从刚开 ${age}ms 的小窗起新 Task，" +
                    "bounds $current → $bounds scale=$scale（不再错开）",
            )
        }
    }

    /** 见类注释「跳板 Task 的清理」。在 WM 全局锁里判定，锁外 removeTask。 */
    private fun cleanup(ctx: HookContext, m: Members, p: Pending, trigger: String) {
        val task = p.task
        val source = p.source
        val pkg = p.pkg
        try {
            if (!active(HookRegistry.prefs())) return
            val atm = m.atmService.get(task) ?: return
            val lock = m.globalLockOf(atm) ?: return
            val taskId = synchronized(lock) {
                if (m.isAttached.invoke(task) != true) return
                if (m.getWindowingMode.invoke(task) != WINDOWING_MODE_FREEFORM) return
                if (m.getTopNonFinishing.invoke(task) !== source) return
                val focused = m.focusedRootTaskOf(atm) ?: return
                if (focused === task || m.basePackageOf(focused) != pkg) return
                m.taskId.getInt(task)
            }
            m.removeTaskOf(atm, taskId)
            val n = cleanups.incrementAndGet()
            if (n <= LOG_LIMIT) ctx.log(Log.INFO, "小窗跳板清理 #$n（$trigger）：$pkg 的跳板 Task $taskId 已被新页面盖住，提前移除")
        } catch (t: Throwable) {
            if (cleanups.get() < LOG_LIMIT) ctx.log(Log.WARN, "小窗跳板清理失败，留给应用自己 finish", t)
        }
    }

    /** 与 MIUI 同一套找法：有 sourceRecord 用它，没有就看调用方进程里最后一个小窗 Activity。 */
    private fun sourceActivity(m: Members, cls: Class<*>, args: List<Any?>): Any? {
        args.getOrNull(4)?.let { return it }
        val root = args.getOrNull(0) ?: return null
        val pid = args.getOrNull(7) as? Int ?: return null
        val app = m.getCallerApp.invoke(null, root, args.getOrNull(6), pid) ?: return null
        @Suppress("UNCHECKED_CAST")
        val active = m.getActivities.invoke(app) as? List<Any?>
        @Suppress("UNCHECKED_CAST")
        val inactive = m.getInactiveActivities?.invoke(app) as? List<Any?>
        return m.lastFreeform.invoke(null, active) ?: inactive?.let { m.lastFreeform.invoke(null, it) }
    }

    /** 第一次用到时解析全部反射成员。缺任何一个必需成员 → 整条规则停用。 */
    private fun resolve(ctx: HookContext, cls: Class<*>): Members? = synchronized(this) {
        members?.let { return it }
        if (broken) return null
        val loader = cls.classLoader
        return try {
            val activityRecord = Class.forName("com.android.server.wm.ActivityRecord", false, loader)
            val task = Class.forName("com.android.server.wm.Task", false, loader)
            val wpc = Class.forName("com.android.server.wm.WindowProcessController", false, loader)
            val injector = ActivityOptions::class.java.getMethod("getActivityOptionsInjector")
            Members(
                getLaunchWindowingMode = ActivityOptions::class.java.getMethod("getLaunchWindowingMode"),
                getInjector = injector,
                setFreeformScale = injector.returnType.getMethod("setFreeformScale", Float::class.javaPrimitiveType),
                isDesktopActive = runCatching {
                    Class.forName(DESKTOP_UTILS, false, loader).getMethod("isDesktopActive")
                }.getOrNull(),
                getTask = findMethod(activityRecord, "getTask"),
                packageName = findField(activityRecord, "packageName"),
                createTime = findField(activityRecord, "createTime"),
                getWindowingMode = findMethod(task, "getWindowingMode"),
                getRootActivity = findMethod(task, "getRootActivity"),
                getBounds = findMethod(task, "getBounds"),
                getRootTaskId = findMethod(task, "getRootTaskId"),
                getTopNonFinishing = findMethod(task, "getTopNonFinishingActivity"),
                isAttached = findMethod(task, "isAttached"),
                taskId = findField(task, "mTaskId"),
                atmService = findField(task, "mAtmService"),
                getCallerApp = cls.declaredMethods.first { it.name == "getCallerApp" && it.parameterTypes.size == 3 }
                    .apply { isAccessible = true },
                lastFreeform = cls.declaredMethods.first { it.name == "getLastFreeFormActivityRecord" }
                    .apply { isAccessible = true },
                getActivities = findMethod(wpc, "getActivities"),
                getInactiveActivities = runCatching { findMethod(wpc, "getInactiveActivities") }.getOrNull(),
            ).also {
                members = it
                ctx.log(Log.INFO, "小窗跳板：反射成员已就绪（桌面模式判定=${it.isDesktopActive != null}）")
            }
        } catch (t: Throwable) {
            broken = true
            ctx.log(Log.WARN, "小窗跳板：反射成员解析失败，本规则停用（跳板小窗照 MIUI 原样错开）", t)
            null
        }
    }

    /** 沿父类找无参方法（system_server 里不少是包内可见，`getMethod` 找不到）。 */
    private fun findMethod(start: Class<*>, name: String, vararg params: Class<*>): Method {
        var c: Class<*>? = start
        while (c != null) {
            runCatching { return c.getDeclaredMethod(name, *params).apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchMethodException("${start.name}#$name")
    }

    private fun findField(start: Class<*>, name: String): Field {
        var c: Class<*>? = start
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchFieldException("${start.name}#$name")
    }

    override fun uninstall() {
        super.uninstall()
        cleaner.removeCallbacksAndMessages(null)
        pending.clear()
        cleanups.set(0)
        hits.set(0)
    }

    private class Members(
        val getLaunchWindowingMode: Method,
        val getInjector: Method,
        val setFreeformScale: Method,
        val isDesktopActive: Method?,
        val getTask: Method,
        val packageName: Field,
        val createTime: Field,
        val getWindowingMode: Method,
        val getRootActivity: Method,
        val getBounds: Method,
        val getRootTaskId: Method,
        val getTopNonFinishing: Method,
        val isAttached: Method,
        val taskId: Field,
        val atmService: Field,
        val getCallerApp: Method,
        val lastFreeform: Method,
        val getActivities: Method,
        val getInactiveActivities: Method?,
    ) {
        @Volatile
        private var scaleOf: Method? = null

        @Volatile
        private var ffms: Field? = null

        fun globalLockOf(atm: Any): Any? = findField(atm.javaClass, "mGlobalLock").get(atm)

        fun focusedRootTaskOf(atm: Any): Any? = findMethod(atm.javaClass, "getTopDisplayFocusedRootTask").invoke(atm)

        fun basePackageOf(task: Any): String? = findMethod(task.javaClass, "getBasePackageName").invoke(task) as? String

        fun removeTaskOf(atm: Any, taskId: Int) {
            findMethod(atm.javaClass, "removeTask", Int::class.javaPrimitiveType!!).invoke(atm, taskId)
        }

        /** 与 MIUI 自己的 keep-bounds 分支同一个来源：`mAtmService.mMiuiFreeFormManagerService.getFreeformScale(rootTaskId)`。 */
        fun freeformScaleOf(task: Any, rootTaskId: Int): Float? = runCatching {
            val atm = atmService.get(task) ?: return null
            val f = ffms ?: findField(atm.javaClass, "mMiuiFreeFormManagerService").also { ffms = it }
            val service = f.get(atm) ?: return null
            val m = scaleOf ?: findMethod(service.javaClass, "getFreeformScale", Int::class.javaPrimitiveType!!)
                .also { scaleOf = it }
            m.invoke(service, rootTaskId) as? Float
        }.getOrNull()
    }
}
