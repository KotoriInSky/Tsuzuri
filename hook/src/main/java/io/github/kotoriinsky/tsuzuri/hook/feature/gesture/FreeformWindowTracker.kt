package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

import android.app.ActivityManager
import android.graphics.Rect
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI（WM Shell）侧的小窗状态追踪器（引用计数，按特性按需装卸）。
 *
 * 承担两件轻量职责：
 *  1. 给 [SystemUiEnv] 提供当前**非挂边、正常显示态**的小窗屏幕矩形列表，呼出新小窗时
 *     通过 `WindowCascade.avoidFlushOverlap` 避免严丝合缝重合（挂边窗 `isInPinMode` 与迷你窗不计入）；
 *  2. 刚通过 Tsuzuri 呼出小窗后（[TOP_WINDOW_MS] 内），若 WM Shell 复用了旧 Task 而未将其提到
 *     `mFreeformTaskIdsInZOrder` 首位，主动调 `updateFreeformTaskToTop` 保证新呼出的小窗在最顶层。
 */
internal object FreeformWindowTracker {

    private const val TAG = "FP-FreeformTrack"
    private const val REPO_CLASS = "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository"
    private const val WINDOWING_MODE_FREEFORM = 5

    /** 呼出后这段时间内观察到的目标小窗保证置顶。 */
    private const val TOP_WINDOW_MS = 2_000L

    private val owners = LinkedHashSet<String>()
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()

    @Volatile
    private var repo: Any? = null

    @Volatile
    private var recentLaunchPkg: String? = null

    @Volatile
    private var recentLaunchUntil: Long = 0L

    @Synchronized
    fun acquire(owner: String, ctx: HookContext): Int {
        val wasEmpty = owners.isEmpty()
        owners.add(owner)
        if (!wasEmpty && handles.isNotEmpty()) return handles.size
        return installHooks(ctx)
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        for (h in handles) {
            runCatching { h.unhook() }
        }
        handles.clear()
        repo = null
        recentLaunchPkg = null
        recentLaunchUntil = 0L
    }

    /** 标记即将呼出小窗：之后短时间内落地的小窗保证在 Z 序最顶层。 */
    fun noteLaunch(pkg: String?) {
        recentLaunchPkg = pkg
        recentLaunchUntil = System.currentTimeMillis() + TOP_WINDOW_MS
    }

    /**
     * 当前屏幕上处于**正常显示态（`mode == 0` 且非挂边）**的小窗屏上矩形。
     *
     * 挂边（`mode == 2 / 3` / `isInPinMode()`）与迷你窗（`mode == 1`）一律排除：
     * 挂边窗贴在屏边、迷你窗缩在角落，都不应触发新小窗的防严丝合缝错位。
     */
    fun visibleNormalBounds(): List<ScreenRect> {
        val r = repo ?: return emptyList()
        for (attempt in 0 until 2) {
            val res = runCatching {
                val ids = call(r, "getFreeformTasksInZOrder") as? List<*> ?: return emptyList()
                val out = ArrayList<ScreenRect>(ids.size)
                for (i in 0 until ids.size) {
                    val taskId = (ids.getOrNull(i) as? Int) ?: continue
                    val info = call(r, "getMiuiFreeformTaskInfo", taskId) ?: continue
                    if (isPinnedOrNotNormal(info)) continue
                    screenRectOf(info)?.let { out.add(it) }
                }
                out
            }
            res.getOrNull()?.let { return it }
        }
        return emptyList()
    }

    private fun installHooks(ctx: HookContext): Int {
        val cls = runCatching { ctx.classLoader.loadClass(REPO_CLASS) }.getOrElse {
            ctx.log(Log.WARN, "[$TAG] 找不到 $REPO_CLASS，防严丝合缝错位降级为不介入")
            return 0
        }
        var count = 0
        for (name in listOf("onTaskAppeared", "onOpenTransitionReady", "onTaskInfoChanged", "onTaskVanished")) {
            val m = cls.declaredMethods.firstOrNull { it.name == name } ?: continue
            m.isAccessible = true
            runCatching {
                val h = ctx.module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    try {
                        val self = chain.thisObject
                        if (self != null) {
                            repo = self
                            if (name == "onOpenTransitionReady" || name == "onTaskAppeared") {
                                ensureLaunchedTaskOnTop(self, chain.args.firstOrNull() as? ActivityManager.RunningTaskInfo)
                            }
                        }
                    } catch (_: Throwable) {
                        // 绝不向 WM Shell 抛异常
                    }
                    r
                }
                handles.add(h)
                count++
            }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $name 失败", it) }
        }
        ctx.log(Log.INFO, "[$TAG] 小窗状态追踪已挂载：hooked=$count")
        return count
    }

    private fun ensureLaunchedTaskOnTop(r: Any, ti: ActivityManager.RunningTaskInfo?) {
        if (ti == null) return
        val until = recentLaunchUntil
        if (until == 0L || System.currentTimeMillis() > until) return
        val wmMode = runCatching {
            ti.javaClass.getMethod("getWindowingMode").invoke(ti) as? Int
        }.getOrNull()
        if (wmMode != WINDOWING_MODE_FREEFORM) return
        val expectedPkg = recentLaunchPkg
        val actualPkg = ti.baseActivity?.packageName ?: ti.topActivity?.packageName
        if (expectedPkg != null && actualPkg != null && actualPkg != expectedPkg) return

        val taskId = ti.taskId
        val info = call(r, "getMiuiFreeformTaskInfo", taskId) ?: return
        if (isPinnedOrNotNormal(info)) return
        val ids = call(r, "getFreeformTasksInZOrder") as? List<*> ?: return
        val topId = ids.firstOrNull() as? Int
        if (topId != taskId && ids.contains(taskId)) {
            call(r, "updateFreeformTaskToTop", taskId, "tsuzuri_launch")
            Log.i(TAG, "新呼出小窗 task=$taskId ($actualPkg) 已置顶（原顶层=$topId）")
        }
    }

    private fun isPinnedOrNotNormal(info: Any): Boolean {
        val pinned = call(info, "isInPinMode") as? Boolean ?: false
        if (pinned) return true
        val mode = call(info, "getMode") as? Int ?: 0
        return mode != 0
    }

    private fun screenRectOf(info: Any): ScreenRect? {
        val animating = call(info, "isInAnimating") as? Boolean ?: false
        val rect = (if (animating) call(info, "getScaledDestinationBounds") as? Rect else null)
            ?.takeIf { !it.isEmpty }
            ?: (call(info, "getScaledBounds") as? Rect)?.takeIf { !it.isEmpty }
            ?: scaledFallback(info)
            ?: return null
        return ScreenRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
        )
    }

    private fun scaledFallback(info: Any): Rect? {
        val raw = call(info, "getTaskBounds") as? Rect ?: return null
        if (raw.isEmpty) return null
        val scale = (call(info, "getTaskScale") as? Float)?.takeIf { it > 0.1f && it <= 1.25f }
            ?: (call(info, "getFreeformScale") as? Float)?.takeIf { it > 0.1f && it <= 1.25f }
            ?: 1f
        return Rect(
            raw.left,
            raw.top,
            raw.left + (raw.width() * scale).toInt(),
            raw.top + (raw.height() * scale).toInt(),
        )
    }

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(target, *args)
            }
            c = c.superclass
        }
        return null
    }
}
