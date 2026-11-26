package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FreeformEnhance
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 小窗增强套件（SystemUI / WM Shell 侧，[Catalog.FREEFORM_ENHANCE]）。
 *
 * 采用不破坏原生顶栏菜单与单击肌肉记忆的「方案 B」交互：
 *  1. **即用即走（关闭不留后台卡片）**：追踪进入小窗前是否已存在于最近任务；
 *     关闭退出动画结束后对符合策略的任务调用 `ActivityTaskManager.getService().removeTask(taskId)`。
 *  2. **长按迷你小窗弹出 Flyme 风格浮层**：
 *     - 单击迷你小窗完全保留 HyperOS 原生行为（立刻展开为普通小窗）；
 *     - 在迷你小窗上按住不动约 380ms（不拖动），在窗体正上方盖一层精确同尺寸同圆角的半透明暗色浮层，
 *       居中呈现「单窗静音 / 恢复声音」与「与主屏互换」图标按钮，点空白或外部自动收起；
 *     - 静音通过 `AudioPlaybackConfiguration.getPlayerProxy().setVolume` 仅临时压低当前 UID 播放流，
 *       关窗、切全屏或卸载模块时自动恢复 `1.0f`。
 *  3. **双击迷你小窗与主屏互换**：
 *     - 迷你小窗借原生动画升为全屏，同时把原本在底下的标准全屏应用切进小窗（默认自动再缩成迷你小窗）；
 *     - 若底层是桌面或无其他全屏应用，则无感回退为 HyperOS 原生双击最大化全屏。
 */
object FreeformEnhanceFeature : Feature(Catalog.require(Catalog.FREEFORM_ENHANCE)) {

    private const val TAG = "FP-FreeformEnh"

    private const val REPO_CLS = "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository"
    private const val ANIM_CLS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation"
    private const val CLICK_CLS = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiCaptionClickListener"
    private const val GESTURE_CLS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeGestureHandler"
    private const val MINI_HANDLER_CLS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMiniStateHandler"
    private const val TOUCH_RESULT_CLS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTouchResult"
    private const val CONTROLLER_IMPL_CLS = "com.android.wm.shell.dagger.MultiTaskingControllerImpl"

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val ACTIVITY_TYPE_STANDARD = 1
    private const val TOUCH_MINI = 4

    /** 关闭动画播完后再移除最近任务卡片，避免掐断原生缩小淡出动画。 */
    private const val REMOVE_TASK_DELAY_MS = 380L

    /** 长按迷你小窗弹出浮层的触发时长（贴近 Flyme PinWindow 400ms 手感）。 */
    private const val LONG_PRESS_DELAY_MS = 380L

    /** 浮层无操作自动收起超时，避免忘记关闭遮挡画面。 */
    private const val OVERLAY_AUTO_DISMISS_MS = 4500L

    /** 主副互换节拍：先让迷你小窗升全屏起飞，再把原全屏降入小窗并缩回迷你小窗。 */
    private const val SWAP_TO_FREEFORM_DELAY_MS = 180L
    private const val FREEFORM_TO_MINI_DELAY_MS = 360L
    private const val FREEFORM_TO_MINI_RETRY_MS = 260L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var shellHandler: Handler? = null

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var repoRef: Any? = null

    @Volatile
    private var miniHandlerRef: Any? = null

    @Volatile
    private var appContext: Context? = null

    /** 打开小窗前就已经存在于最近任务（或曾以全屏运行过）的 taskId 集合。 */
    private val preExistingTasks = ConcurrentHashMap.newKeySet<Int>()

    /** 记录每个进入小窗的 taskId 是否属于「进入前已在后台」快照。 */
    private val taskWasInRecents = ConcurrentHashMap<Int, Boolean>()

    /** 当前被本模块临时静音的 UID -> 对应 taskId 集合（同一 App 多小窗可共存）。 */
    private val mutedUids = ConcurrentHashMap<Int, MutableSet<Int>>()

    @Volatile
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    @Volatile
    private var audioManagerRef: AudioManager? = null

    // ── 迷你小窗长按状态机（仅在 Shell 主线程访问） ─────────────────────────────

    private var longPressToken = 0
    private var longPressRunnable: Runnable? = null
    private var longPressConsumed = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTouchSlop = 24f
    private var downMiniTask: Any? = null

    /** 防止浮层内点击「互换」主动调用 doubleTap 时被双击 hook 重入。 */
    @Volatile
    private var internalDoubleTapActive = false

    // ── Flyme 风格迷你小窗浮层状态（仅在主线程访问） ─────────────────────────────

    private var activeOverlayView: View? = null
    private var activeOverlayWm: WindowManager? = null
    private var activeOverlayTaskId: Int = -1
    private var overlayDismissRunnable: Runnable? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean): Boolean =
        !isSystemServer && processName == ScopeTarget.SYSTEM_UI.packageName

    override fun install(ctx: HookContext): InstallReport {
        hostClassLoader = ctx.classLoader
        val missing = ArrayList<String>()
        seedPreExistingTasks()

        val repoHooks = hookTaskRepository(ctx)
        if (repoHooks == 0) missing += REPO_CLS

        val exitHooks = hookFreeformExits(ctx)
        if (exitHooks == 0) missing += ANIM_CLS

        val miniHooks = hookMiniGestures(ctx)
        if (miniHooks == 0) missing += "$GESTURE_CLS/$MINI_HANDLER_CLS"

        val total = installedHooks
        if (total == 0) {
            return InstallReport(0, missing, notes = listOf("未命中任何小窗宿主类"))
        }
        ctx.log(
            Log.INFO,
            "[$TAG] 小窗增强已挂上：hooked=$total (repo=$repoHooks exit=$exitHooks mini=$miniHooks) missing=$missing",
        )
        return InstallReport(
            hooked = total,
            missing = missing,
            notes = listOf("repo=$repoHooks", "exit=$exitHooks", "mini=$miniHooks"),
        )
    }

    override fun uninstall() {
        dismissOverlayAsync()
        cancelPendingLongPress()
        restoreAllMutedUids()
        unregisterPlaybackCallback()
        preExistingTasks.clear()
        taskWasInRecents.clear()
        repoRef = null
        miniHandlerRef = null
        shellHandler = null
        hostClassLoader = null
        appContext = null
        super.uninstall()
    }

    // ── Hook 1：任务仓库（区分「临时小窗」vs「已有后台」+ 自动还原静音） ────────

    private fun hookTaskRepository(ctx: HookContext): Int {
        val cls = runCatching { ctx.classLoader.loadClass(REPO_CLS) }.getOrNull() ?: return 0
        var count = 0
        for (name in listOf("onTaskAppeared", "onTaskInfoChanged", "onTaskVanished")) {
            val m = cls.declaredMethods.firstOrNull { it.name == name } ?: continue
            m.isAccessible = true
            runCatching {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        val self = chain.thisObject
                        val ti = chain.args.firstOrNull() as? ActivityManager.RunningTaskInfo
                        if (self != null) repoRef = self
                        if (name == "onTaskAppeared" && ti != null) {
                            recordTaskOrigin(ti)
                        }
                        val res = chain.proceed()
                        try {
                            if (ti != null) {
                                when (name) {
                                    "onTaskInfoChanged" -> onTaskInfoChanged(ti)
                                    "onTaskVanished" -> onTaskEnded(ti.taskId)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        res
                    },
                )
                count++
            }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $REPO_CLS.$name 失败", it) }
        }
        return count
    }

    private fun recordTaskOrigin(ti: ActivityManager.RunningTaskInfo) {
        val id = ti.taskId
        val wm = windowingModeOf(ti)
        if (wm != WINDOWING_MODE_FREEFORM) {
            preExistingTasks.add(id)
            return
        }
        if (!taskWasInRecents.containsKey(id)) {
            val existed = id in preExistingTasks
            taskWasInRecents[id] = existed
            Log.d(TAG, "追踪小窗 task=$id pkg=${packageOf(ti)} existedBefore=$existed")
        }
    }

    private fun onTaskInfoChanged(ti: ActivityManager.RunningTaskInfo) {
        val id = ti.taskId
        val wm = windowingModeOf(ti)
        if (wm == WINDOWING_MODE_FULLSCREEN) {
            // 小窗转为全屏后成为正式任务，同时自动还原临时静音并收起浮层
            preExistingTasks.add(id)
            taskWasInRecents[id] = true
            restoreTaskState(id)
            if (activeOverlayTaskId == id) dismissOverlayAsync()
        }
    }

    private fun onTaskEnded(taskId: Int) {
        restoreTaskState(taskId)
        if (activeOverlayTaskId == taskId) dismissOverlayAsync()
        taskWasInRecents.remove(taskId)
        preExistingTasks.remove(taskId)
    }

    // ── Hook 2：小窗关闭出口（即用即走 + 还原静音） ───────────────────────────

    private fun hookFreeformExits(ctx: HookContext): Int {
        var count = 0
        val animCls = runCatching { ctx.classLoader.loadClass(ANIM_CLS) }.getOrNull()
        if (animCls != null) {
            for (name in listOf("startExitFreeformShellTransition", "exitFreeformWithoutAnimation")) {
                val m = animCls.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 2
                } ?: continue
                m.isAccessible = true
                runCatching {
                    track(
                        ctx.module.hook(m).intercept { chain ->
                            val ffInfo = chain.args.firstOrNull()
                            val taskId = ffInfo?.let { call(it, "getTaskId") as? Int } ?: -1
                            val pkg = ffInfo?.let { call(it, "getPackageName") as? String }
                            val result = chain.proceed()
                            if (taskId > 0) {
                                handleFreeformClosed(taskId, pkg, source = name)
                            }
                            result
                        },
                    )
                    count++
                }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $ANIM_CLS.$name 失败", it) }
            }
        }

        val clickCls = runCatching { ctx.classLoader.loadClass(CLICK_CLS) }.getOrNull()
        if (clickCls != null) {
            val closeM = clickCls.declaredMethods.firstOrNull {
                it.name == "handleCloseClicked" && it.parameterTypes.size == 1
            }
            if (closeM != null) {
                closeM.isAccessible = true
                runCatching {
                    track(
                        ctx.module.hook(closeM).intercept { chain ->
                            val decor = chain.args.firstOrNull()
                            val ti = decor?.let { readField(it, "mRunningTaskInfo") as? ActivityManager.RunningTaskInfo }
                            val wasFreeform = ti != null && windowingModeOf(ti) == WINDOWING_MODE_FREEFORM
                            val taskId = ti?.taskId ?: -1
                            val pkg = ti?.let { packageOf(it) }
                            val result = chain.proceed()
                            if (wasFreeform && taskId > 0) {
                                handleFreeformClosed(taskId, pkg, source = "caption_close")
                            }
                            result
                        },
                    )
                    count++
                }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $CLICK_CLS.handleCloseClicked 失败", it) }
            }

            // 切全屏时立即还原静音，并标记为非临时任务
            val fullM = clickCls.declaredMethods.firstOrNull {
                it.name == "handleFullscreenClicked" && it.parameterTypes.size == 1
            }
            if (fullM != null) {
                fullM.isAccessible = true
                runCatching {
                    track(
                        ctx.module.hook(fullM).intercept { chain ->
                            val decor = chain.args.firstOrNull()
                            val ti = decor?.let { readField(it, "mRunningTaskInfo") as? ActivityManager.RunningTaskInfo }
                            if (ti != null) {
                                preExistingTasks.add(ti.taskId)
                                taskWasInRecents[ti.taskId] = true
                                restoreTaskState(ti.taskId)
                                if (activeOverlayTaskId == ti.taskId) dismissOverlayAsync()
                            }
                            chain.proceed()
                        },
                    )
                    count++
                }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $CLICK_CLS.handleFullscreenClicked 失败", it) }
            }
        }
        return count
    }

    private fun handleFreeformClosed(taskId: Int, pkg: String?, source: String) {
        restoreTaskState(taskId)
        if (activeOverlayTaskId == taskId) dismissOverlayAsync()
        val prefs = HookRegistry.prefs()
        if (!prefs.isEnabled(Catalog.FREEFORM_ENHANCE)) return
        val cfg = FreeformEnhance.from(prefs)
        val existedBefore = taskWasInRecents[taskId] ?: (taskId in preExistingTasks)
        if (!FreeformEnhance.shouldRemoveOnClose(cfg, pkg, existedBefore)) {
            Log.d(TAG, "即用即走跳过：task=$taskId pkg=$pkg existedBefore=$existedBefore scope=${cfg.ephemeralScope}")
            return
        }
        main.postDelayed({
            try {
                if (isTaskStillActiveFreeform(taskId) || isTaskTopFullscreen(taskId)) {
                    Log.d(TAG, "即用即走取消：task=$taskId 已恢复活跃")
                    return@postDelayed
                }
                val removed = removeTaskFromRecents(taskId)
                if (removed) {
                    taskWasInRecents.remove(taskId)
                    preExistingTasks.remove(taskId)
                    Log.i(TAG, "小窗即用即走：已清除后台卡片 task=$taskId pkg=$pkg source=$source")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "移除小窗最近任务失败 task=$taskId：$t")
            }
        }, REMOVE_TASK_DELAY_MS)
    }

    private fun isTaskStillActiveFreeform(taskId: Int): Boolean {
        val repo = repoRef ?: return false
        val ids = call(repo, "getFreeformTasksInZOrder") as? List<*> ?: return false
        return ids.any { (it as? Int) == taskId }
    }

    private fun isTaskTopFullscreen(taskId: Int): Boolean {
        val top = findBottomFullscreenTask(displayId = 0) ?: return false
        return top.taskId == taskId
    }

    private fun removeTaskFromRecents(taskId: Int): Boolean {
        val atmOk = runCatching {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val svc = atmCls.getMethod("getService").invoke(null) ?: return@runCatching false
            val m = svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (m.invoke(svc, taskId) as? Boolean) ?: true
        }.getOrDefault(false)
        if (atmOk) return true

        return runCatching {
            val amCls = Class.forName("android.app.ActivityManager")
            val svc = amCls.getMethod("getService").invoke(null) ?: return@runCatching false
            val m = svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (m.invoke(svc, taskId) as? Boolean) ?: true
        }.getOrDefault(false)
    }

    private fun seedPreExistingTasks() {
        runCatching {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val svc = atmCls.getMethod("getService").invoke(null) ?: return
            val m = svc.javaClass.declaredMethods.firstOrNull {
                it.name == "getRecentTasks" && it.parameterTypes.size == 3
            } ?: return
            m.isAccessible = true
            val parceled = m.invoke(svc, 40, 0, -2) ?: return
            val list = call(parceled, "getList") as? List<*> ?: return
            for (item in list) {
                val id = (item as? ActivityManager.RecentTaskInfo)?.taskId ?: continue
                if (id > 0) preExistingTasks.add(id)
            }
        }
    }

    // ── Hook 3：迷你小窗手势增强（长按 Flyme 浮层 + 双击主副互换） ──────────────

    private fun hookMiniGestures(ctx: HookContext): Int {
        var count = 0

        // 3a. 迷你小窗长按识别：hook MiuiFreeformModeGestureHandler.handleMotionEvents
        val gestureCls = runCatching { ctx.classLoader.loadClass(GESTURE_CLS) }.getOrNull()
        val handleMotion = gestureCls?.declaredMethods?.firstOrNull {
            it.name == "handleMotionEvents" && it.parameterTypes.size == 2 &&
                MotionEvent::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (handleMotion != null) {
            handleMotion.isAccessible = true
            runCatching {
                track(
                    ctx.module.hook(handleMotion).intercept { chain ->
                        captureShellHandler()
                        val handler = chain.thisObject
                        val event = chain.args[0] as? MotionEvent
                        if (handler == null || event == null) return@intercept chain.proceed()

                        (readField(handler, "mContext") as? Context)?.let { c ->
                            if (appContext == null) appContext = c.applicationContext ?: c
                        }

                        val prefs = HookRegistry.prefs()
                        if (!prefs.isEnabled(Catalog.FREEFORM_ENHANCE)) {
                            cancelPendingLongPress()
                            longPressConsumed = false
                            return@intercept chain.proceed()
                        }
                        val cfg = FreeformEnhance.from(prefs)
                        if (!cfg.miniLongPressMenu) {
                            cancelPendingLongPress()
                            longPressConsumed = false
                            return@intercept chain.proceed()
                        }

                        val action = event.actionMasked
                        // 长按已经触发：吞掉后续 MOVE/UP/CANCEL，防止松手误触原生单击展开
                        if (longPressConsumed && action != MotionEvent.ACTION_DOWN) {
                            val task = downMiniTask
                            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                longPressConsumed = false
                                downMiniTask = null
                                runCatching {
                                    writeField(handler, "mGestureDispatching", false)
                                }
                            }
                            return@intercept newTouchResult(ctx.classLoader, 1, TOUCH_MINI, task)
                                ?: chain.proceed()
                        }

                        when (action) {
                            MotionEvent.ACTION_DOWN -> {
                                cancelPendingLongPress()
                                longPressConsumed = false
                                downMiniTask = null
                                val res = chain.proceed()
                                tryArmMiniLongPress(handler, event)
                                return@intercept res
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (longPressRunnable != null) {
                                    val touchState = readField(handler, "mMiuiFreeformModeTouchState")
                                    val dragging = (touchState?.let { call(it, "isDragging") as? Boolean }) == true
                                    val moved = hypot(
                                        (event.rawX - downRawX).toDouble(),
                                        (event.rawY - downRawY).toDouble(),
                                    )
                                    if (dragging || moved > downTouchSlop) {
                                        cancelPendingLongPress()
                                    }
                                }
                                return@intercept chain.proceed()
                            }
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL,
                            -> {
                                cancelPendingLongPress()
                                return@intercept chain.proceed()
                            }
                            else -> return@intercept chain.proceed()
                        }
                    },
                )
                count++
            }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $GESTURE_CLS.handleMotionEvents 失败", it) }
        }

        // 3b. 双击迷你小窗与主屏互换：hook MiuiFreeformModeMiniStateHandler.doubleTap
        val miniCls = runCatching { ctx.classLoader.loadClass(MINI_HANDLER_CLS) }.getOrNull()
        val doubleTapM = miniCls?.declaredMethods?.firstOrNull {
            it.name == "doubleTap" && it.parameterTypes.size == 1
        }
        if (doubleTapM != null) {
            doubleTapM.isAccessible = true
            runCatching {
                track(
                    ctx.module.hook(doubleTapM).intercept { chain ->
                        captureShellHandler()
                        val self = chain.thisObject
                        if (self != null) {
                            miniHandlerRef = self
                            (readField(self, "mContext") as? Context)?.let { c ->
                                if (appContext == null) appContext = c.applicationContext ?: c
                            }
                        }
                        val ffTaskInfo = chain.args.firstOrNull()
                        val runningTask = ffTaskInfo?.let {
                            call(it, "getTaskInfo") as? ActivityManager.RunningTaskInfo
                        }
                        if (runningTask != null) {
                            // 双击变全屏：立即还原静音、收起浮层并标记为非临时任务
                            preExistingTasks.add(runningTask.taskId)
                            taskWasInRecents[runningTask.taskId] = true
                            restoreTaskState(runningTask.taskId)
                            if (activeOverlayTaskId == runningTask.taskId) dismissOverlayAsync()
                        }

                        val prefs = HookRegistry.prefs()
                        if (internalDoubleTapActive ||
                            runningTask == null ||
                            !prefs.isEnabled(Catalog.FREEFORM_ENHANCE)
                        ) {
                            return@intercept chain.proceed()
                        }
                        val cfg = FreeformEnhance.from(prefs)
                        if (!cfg.miniDoubleTapSwap) {
                            return@intercept chain.proceed()
                        }

                        val displayId = (call(runningTask, "getDisplayId") as? Int) ?: 0
                        val bottomFull = findBottomFullscreenTask(displayId)
                            ?.takeIf { it.taskId != runningTask.taskId }
                        if (bottomFull == null) {
                            // 底层为桌面或没有其他全屏应用：保持 HyperOS 原生双击最大化全屏
                            return@intercept chain.proceed()
                        }

                        // 原底层全屏任务本来就在后台，保留最近任务卡片
                        preExistingTasks.add(bottomFull.taskId)
                        taskWasInRecents[bottomFull.taskId] = true

                        // 第一步直接走原生 doubleTap（最丝滑的迷你小窗→全屏动画）
                        val res = chain.proceed()
                        // 第二步把原全屏应用切入小窗并按配置自动缩成迷你小窗
                        scheduleDemoteFullscreenAfterSwap(bottomFull, cfg.swapToMini)
                        Log.i(
                            TAG,
                            "双击迷你小窗互换：mini=${runningTask.taskId}→全屏，full=${bottomFull.taskId}→${if (cfg.swapToMini) "迷你小窗" else "小窗"}",
                        )
                        res
                    },
                )
                count++
            }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $MINI_HANDLER_CLS.doubleTap 失败", it) }
        }

        // 3c. 单击迷你小窗展开时：同步收起浮层（不影响原生单击行为）
        val singleTapM = miniCls?.declaredMethods?.firstOrNull {
            it.name == "singleTap" && it.parameterTypes.size == 1
        }
        if (singleTapM != null) {
            singleTapM.isAccessible = true
            runCatching {
                track(
                    ctx.module.hook(singleTapM).intercept { chain ->
                        if (chain.thisObject != null) miniHandlerRef = chain.thisObject
                        dismissOverlayAsync()
                        chain.proceed()
                    },
                )
                count++
            }.onFailure { ctx.log(Log.WARN, "[$TAG] hook $MINI_HANDLER_CLS.singleTap 失败", it) }
        }

        return count
    }

    private fun tryArmMiniLongPress(gestureHandler: Any, event: MotionEvent) {
        val touchedMode = (readField(gestureHandler, "mTouchedMode") as? Int) ?: 0
        val activeTask = readField(gestureHandler, "mActiveMiuiFreeformTask") ?: return
        val isMini = (call(activeTask, "isMiniState") as? Boolean) == true
        val touchState = readField(gestureHandler, "mMiuiFreeformModeTouchState") ?: return
        val allow = (call(touchState, "isAllowGesture") as? Boolean) == true
        if (touchedMode != TOUCH_MINI || !isMini || !allow) return

        val ctx = (readField(gestureHandler, "mContext") as? Context) ?: appContext
        downTouchSlop = ctx?.let { ViewConfiguration.get(it).scaledTouchSlop.toFloat() } ?: 24f
        downRawX = event.rawX
        downRawY = event.rawY
        downMiniTask = activeTask

        val token = ++longPressToken
        val sh = shellHandler ?: main
        val r = Runnable {
            if (token != longPressToken) return@Runnable
            longPressRunnable = null
            onMiniLongPressFired(gestureHandler, touchState, activeTask)
        }
        longPressRunnable = r
        sh.postDelayed(r, LONG_PRESS_DELAY_MS)
    }

    private fun cancelPendingLongPress() {
        longPressToken++
        val r = longPressRunnable ?: return
        longPressRunnable = null
        shellHandler?.removeCallbacks(r)
        main.removeCallbacks(r)
    }

    private fun onMiniLongPressFired(gestureHandler: Any, touchState: Any, miniTaskInfo: Any) {
        val stillMini = (call(miniTaskInfo, "isMiniState") as? Boolean) == true
        if (!stillMini) return
        val dragging = (call(touchState, "isDragging") as? Boolean) == true
        if (dragging) return

        longPressConsumed = true
        runCatching { call(touchState, "setAllowGesture", false) }
        runCatching {
            val h = readField(touchState, "mHandler") as? Handler
            h?.removeMessages(1)
        }

        val runningTask = (call(miniTaskInfo, "getTaskInfo") as? ActivityManager.RunningTaskInfo) ?: return
        val bounds = readMiniScreenBounds(miniTaskInfo) ?: return
        val cornerRadius = readMiniCornerRadius(miniTaskInfo)
        val pkg = (call(miniTaskInfo, "getPackageName") as? String) ?: packageOf(runningTask)
        val displayId = (call(runningTask, "getDisplayId") as? Int) ?: 0
        val bottomFull = findBottomFullscreenTask(displayId)?.takeIf { it.taskId != runningTask.taskId }
        val ctx = (readField(gestureHandler, "mContext") as? Context) ?: appContext ?: return

        main.post {
            showMiniActionOverlay(
                context = ctx,
                miniTaskInfo = miniTaskInfo,
                runningTask = runningTask,
                pkg = pkg,
                bounds = bounds,
                cornerRadiusPx = cornerRadius,
                bottomFull = bottomFull,
            )
        }
    }

    private fun readMiniScreenBounds(miniTaskInfo: Any): Rect? {
        val scaled = runCatching { call(miniTaskInfo, "getScaledBounds") as? Rect }.getOrNull()
        if (scaled != null && !scaled.isEmpty && scaled.width() > 20 && scaled.height() > 20) {
            return Rect(scaled)
        }
        val raw = runCatching { call(miniTaskInfo, "getBounds") as? Rect }.getOrNull() ?: return null
        val scale = (runCatching { call(miniTaskInfo, "getFreeformScale") as? Float }.getOrNull()) ?: 1f
        if (raw.isEmpty) return null
        val w = (raw.width() * scale).roundToInt()
        val h = (raw.height() * scale).roundToInt()
        return if (w > 20 && h > 20) Rect(raw.left, raw.top, raw.left + w, raw.top + h) else null
    }

    private fun readMiniCornerRadius(miniTaskInfo: Any): Float {
        val r = (runCatching { call(miniTaskInfo, "getCornerRadius") as? Float }.getOrNull()) ?: 0f
        return if (r.isFinite() && r > 4f) r else 0f
    }

    // ── Flyme 风格迷你小窗浮层（贴合窗体、同圆角、横竖自适应） ──────────────────

    private fun showMiniActionOverlay(
        context: Context,
        miniTaskInfo: Any,
        runningTask: ActivityManager.RunningTaskInfo,
        pkg: String?,
        bounds: Rect,
        cornerRadiusPx: Float,
        bottomFull: ActivityManager.RunningTaskInfo?,
    ) {
        dismissOverlayImmediate()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val taskId = runningTask.taskId
        val uid = resolveUid(context, pkg)
        val muted = uid >= 0 && isTaskMuted(uid, taskId)
        val radius = if (cornerRadiusPx > 4f) cornerRadiusPx else 22f * density

        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.argb(156, 16, 18, 22))
                setStroke((0.8f * density).roundToInt().coerceAtLeast(1), Color.argb(48, 255, 255, 255))
            }
            clipToOutline = true
            isClickable = true
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
        }

        val horizontal = bounds.width() >= bounds.height()
        val minSide = min(bounds.width(), bounds.height())
        val btnCount = if (bottomFull != null) 2 else 1
        // 根据迷你小窗实际尺寸自适应按钮直径与间距，保证绝不溢出或裁切
        val maxSlot = if (btnCount == 2) {
            val major = if (horizontal) bounds.width() else bounds.height()
            min(minSide * 0.62f, major * 0.36f)
        } else {
            minSide * 0.56f
        }
        val btnSize = maxSlot.roundToInt().coerceIn((34 * density).roundToInt(), (48 * density).roundToInt())
        val gap = (minSide * 0.14f).roundToInt().coerceIn((10 * density).roundToInt(), (22 * density).roundToInt())

        val row = LinearLayout(context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // 按钮 1：静音 / 取消静音
        val muteBtn = buildOverlayCircleButton(
            context = context,
            size = btnSize,
            bgColor = if (muted) Color.argb(235, 229, 72, 77) else Color.argb(66, 255, 255, 255),
            icon = MiniActionDrawable(
                kind = if (muted) MiniIcon.MUTED else MiniIcon.UNMUTED,
                color = Color.WHITE,
                side = (btnSize * 0.52f).roundToInt(),
                density = density,
            ),
            contentDesc = if (muted) "取消静音" else "静音",
        ) {
            dismissOverlayImmediate()
            if (uid >= 0) {
                val nowMuted = toggleMute(context, uid, taskId, pkg)
                Toast.makeText(
                    context,
                    if (nowMuted) "已静音当前小窗" else "已恢复声音",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        row.addView(muteBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        // 按钮 2：与主屏互换（仅底层有普通全屏应用时显示）
        if (bottomFull != null) {
            val swapBtn = buildOverlayCircleButton(
                context = context,
                size = btnSize,
                bgColor = Color.argb(66, 255, 255, 255),
                icon = MiniActionDrawable(
                    kind = MiniIcon.SWAP,
                    color = Color.WHITE,
                    side = (btnSize * 0.52f).roundToInt(),
                    density = density,
                ),
                contentDesc = "与主屏互换",
            ) {
                dismissOverlayImmediate()
                val cfg = FreeformEnhance.from(HookRegistry.prefs())
                triggerSwapFromOverlay(miniTaskInfo, runningTask, bottomFull, cfg.swapToMini)
            }
            val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                if (horizontal) marginStart = gap else topMargin = gap
            }
            row.addView(swapBtn, lp)
        }

        root.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // 点遮罩空白处或外部区域立即收起
        root.setOnClickListener { dismissOverlayImmediate() }
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                dismissOverlayImmediate()
                true
            } else {
                false
            }
        }

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            title = "TsuzuriMiniActionOverlay"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        runCatching {
            wm.addView(root, params)
            activeOverlayView = root
            activeOverlayWm = wm
            activeOverlayTaskId = taskId
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            val autoDismiss = Runnable { dismissOverlayImmediate() }
            overlayDismissRunnable = autoDismiss
            main.postDelayed(autoDismiss, OVERLAY_AUTO_DISMISS_MS)
            Log.d(TAG, "弹出迷你小窗浮层：task=$taskId rect=$bounds muted=$muted hasSwap=${bottomFull != null}")
        }.onFailure {
            Log.w(TAG, "添加迷你小窗浮层失败：$it")
        }
    }

    private fun buildOverlayCircleButton(
        context: Context,
        size: Int,
        bgColor: Int,
        icon: Drawable,
        contentDesc: String,
        onClick: () -> Unit,
    ): FrameLayout {
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            contentDescription = contentDesc
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        val iv = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER
        }
        val iconSide = (size * 0.56f).roundToInt()
        circle.addView(iv, FrameLayout.LayoutParams(iconSide, iconSide, Gravity.CENTER))
        return circle
    }

    private fun dismissOverlayAsync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissOverlayImmediate()
        } else {
            main.post { dismissOverlayImmediate() }
        }
    }

    private fun dismissOverlayImmediate() {
        overlayDismissRunnable?.let { main.removeCallbacks(it) }
        overlayDismissRunnable = null
        val view = activeOverlayView ?: return
        val wm = activeOverlayWm
        activeOverlayView = null
        activeOverlayWm = null
        activeOverlayTaskId = -1
        runCatching { view.animate().cancel() }
        runCatching { wm?.removeViewImmediate(view) }
    }

    // ── 主副窗互换实现（迷你小窗 → 全屏，原全屏 → 迷你小窗/小窗） ───────────────

    private fun triggerSwapFromOverlay(
        miniTaskInfo: Any,
        miniRunningTask: ActivityManager.RunningTaskInfo,
        bottomFull: ActivityManager.RunningTaskInfo,
        swapToMini: Boolean,
    ) {
        // 双方都标记为非临时任务，且解除原小窗静音
        preExistingTasks.add(miniRunningTask.taskId)
        taskWasInRecents[miniRunningTask.taskId] = true
        restoreTaskState(miniRunningTask.taskId)
        preExistingTasks.add(bottomFull.taskId)
        taskWasInRecents[bottomFull.taskId] = true

        postOnShell {
            val ctrlImpl = getMultiTaskingControllerImpl()
            val miniHandler = miniHandlerRef ?: ctrlImpl?.let { call(it, "getMiuiFreeformModeMiniStateHandler") }
            if (miniHandler != null) {
                internalDoubleTapActive = true
                try {
                    call(miniHandler, "doubleTap", miniTaskInfo)
                } catch (t: Throwable) {
                    Log.w(TAG, "浮层触发迷你窗升全屏失败：$t")
                } finally {
                    internalDoubleTapActive = false
                }
            } else {
                val starter = ctrlImpl?.let { call(it, "getMulWinSwitchStarter") }
                if (starter != null) {
                    runCatching { call(starter, "switchFreeformToFullscreen", miniRunningTask) }
                }
            }
            scheduleDemoteFullscreenAfterSwap(bottomFull, swapToMini)
            Log.i(
                TAG,
                "浮层触发主副互换：mini=${miniRunningTask.taskId}→全屏，full=${bottomFull.taskId}→${if (swapToMini) "迷你小窗" else "小窗"}",
            )
        }
    }

    private fun scheduleDemoteFullscreenAfterSwap(
        bottomFull: ActivityManager.RunningTaskInfo,
        swapToMini: Boolean,
    ) {
        postOnShellDelayed(SWAP_TO_FREEFORM_DELAY_MS) {
            val ctrlImpl = getMultiTaskingControllerImpl() ?: return@postOnShellDelayed
            val starter = call(ctrlImpl, "getMulWinSwitchStarter") ?: return@postOnShellDelayed
            val organizer = readField(starter, "mShellTaskOrganizer") ?: return@postOnShellDelayed
            runCatching {
                call(starter, "switchFullscreenToFreeform", organizer, bottomFull)
            }.onFailure {
                Log.w(TAG, "互换第二步（全屏→小窗）失败 task=${bottomFull.taskId}：$it")
                return@postOnShellDelayed
            }

            if (swapToMini) {
                scheduleShrinkToMini(bottomFull.taskId, FREEFORM_TO_MINI_DELAY_MS, retriesLeft = 1)
            }
        }
    }

    private fun scheduleShrinkToMini(taskId: Int, delayMs: Long, retriesLeft: Int) {
        postOnShellDelayed(delayMs) {
            val ctrlImpl = getMultiTaskingControllerImpl() ?: return@postOnShellDelayed
            val ffController = call(ctrlImpl, "getMiuiFreeformModeController") ?: return@postOnShellDelayed
            val repo = repoRef ?: call(ctrlImpl, "getMultiTaskingTaskRepository")
            val info = repo?.let { call(it, "getMiuiFreeformTaskInfo", taskId) }
            val alreadyMini = (info?.let { call(it, "isMiniState") as? Boolean }) == true
            if (alreadyMini) return@postOnShellDelayed

            runCatching { call(ffController, "fromFreeformToMini", taskId) }
                .onFailure { Log.w(TAG, "互换第三步（小窗→迷你小窗）失败 task=$taskId：$it") }

            if (retriesLeft > 0) {
                postOnShellDelayed(FREEFORM_TO_MINI_RETRY_MS) {
                    val infoAfter = repo?.let { call(it, "getMiuiFreeformTaskInfo", taskId) }
                    val isMiniAfter = (infoAfter?.let { call(it, "isMiniState") as? Boolean }) == true
                    val isNormalAfter = (infoAfter?.let { call(it, "isNormalState") as? Boolean }) == true
                    if (!isMiniAfter && isNormalAfter) {
                        runCatching { call(ffController, "fromFreeformToMini", taskId) }
                    }
                }
            }
        }
    }

    private fun getMultiTaskingControllerImpl(): Any? {
        val cl = hostClassLoader ?: return null
        return runCatching {
            val cls = cl.loadClass(CONTROLLER_IMPL_CLS)
            cls.getMethod("getInstance").invoke(null)
        }.getOrNull()
    }

    private fun findBottomFullscreenTask(displayId: Int): ActivityManager.RunningTaskInfo? = runCatching {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val inst = atmCls.getMethod("getInstance").invoke(null) ?: return@runCatching null
        val m = atmCls.declaredMethods.firstOrNull {
            it.name == "getTopVisibleTaskOnDisplay" && it.parameterTypes.size == 3
        } ?: return@runCatching null
        m.isAccessible = true
        val ti = m.invoke(inst, intArrayOf(WINDOWING_MODE_FULLSCREEN), null, displayId)
            as? ActivityManager.RunningTaskInfo
        if (ti != null && activityTypeOf(ti) == ACTIVITY_TYPE_STANDARD) ti else null
    }.getOrNull()

    // ── 单窗静音（按 UID 临时压低播放流，不写系统持久化表） ─────────────────────

    private fun isTaskMuted(uid: Int, taskId: Int): Boolean =
        mutedUids[uid]?.contains(taskId) == true

    /** 返回切换后的静音状态（true = 已静音）。 */
    private fun toggleMute(context: Context, uid: Int, taskId: Int, pkg: String?): Boolean {
        val currentlyMuted = isTaskMuted(uid, taskId)
        return if (currentlyMuted) {
            unmuteTask(uid, taskId)
            Log.i(TAG, "取消单窗静音：task=$taskId uid=$uid pkg=$pkg")
            false
        } else {
            val set = mutedUids.computeIfAbsent(uid) { ConcurrentHashMap.newKeySet() }
            set.add(taskId)
            ensurePlaybackCallback(context)
            applyUidVolume(context, uid, 0f)
            Log.i(TAG, "开启单窗静音：task=$taskId uid=$uid pkg=$pkg")
            true
        }
    }

    private fun unmuteTask(uid: Int, taskId: Int) {
        val set = mutedUids[uid] ?: return
        set.remove(taskId)
        if (set.isEmpty()) {
            mutedUids.remove(uid)
            appContext?.let { applyUidVolume(it, uid, 1.0f) }
        }
        if (mutedUids.isEmpty()) unregisterPlaybackCallback()
    }

    private fun ensurePlaybackCallback(context: Context) {
        if (playbackCallback != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManagerRef = am
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                if (mutedUids.isEmpty() || configs.isNullOrEmpty()) return
                for (apc in configs) {
                    val uid = runCatching { call(apc, "getClientUid") as? Int }.getOrNull() ?: continue
                    if (mutedUids.containsKey(uid)) {
                        setPlaybackVolume(apc, 0f)
                    }
                }
            }
        }
        runCatching {
            am.registerAudioPlaybackCallback(cb, main)
            playbackCallback = cb
        }.onFailure { Log.w(TAG, "注册 AudioPlaybackCallback 失败：$it") }
    }

    private fun unregisterPlaybackCallback() {
        val cb = playbackCallback ?: return
        playbackCallback = null
        runCatching { audioManagerRef?.unregisterAudioPlaybackCallback(cb) }
    }

    private fun applyUidVolume(context: Context, uid: Int, volume: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull() ?: return
        for (apc in configs) {
            val clientUid = runCatching { call(apc, "getClientUid") as? Int }.getOrNull() ?: continue
            if (clientUid == uid) {
                setPlaybackVolume(apc, volume)
            }
        }
    }

    private fun setPlaybackVolume(apc: AudioPlaybackConfiguration, volume: Float) {
        runCatching {
            val proxy = call(apc, "getPlayerProxy") ?: return
            val m = proxy.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
            m.invoke(proxy, volume)
        }.onFailure { Log.w(TAG, "设置播放流音量失败 volume=$volume：$it") }
    }

    private fun restoreTaskState(taskId: Int) {
        val uidsToCheck = mutedUids.entries.filter { taskId in it.value }.map { it.key }
        for (uid in uidsToCheck) {
            unmuteTask(uid, taskId)
        }
    }

    private fun restoreAllMutedUids() {
        val uids = mutedUids.keys.toList()
        mutedUids.clear()
        val ctx = appContext ?: return
        for (uid in uids) {
            applyUidVolume(ctx, uid, 1.0f)
        }
    }

    // ── 自绘精简矢量图标（Flyme 浮层风格） ───────────────────────────────────

    private enum class MiniIcon { UNMUTED, MUTED, SWAP }

    private class MiniActionDrawable(
        private val kind: MiniIcon,
        color: Int,
        private val side: Int,
        density: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            when (kind) {
                MiniIcon.UNMUTED -> {
                    val speaker = Path().apply {
                        moveTo(w * 0.16f, h * 0.37f); lineTo(w * 0.34f, h * 0.37f)
                        lineTo(w * 0.56f, h * 0.19f); lineTo(w * 0.56f, h * 0.81f)
                        lineTo(w * 0.34f, h * 0.63f); lineTo(w * 0.16f, h * 0.63f); close()
                    }
                    canvas.drawPath(speaker, paint)
                    // 两条声波弧线
                    canvas.drawArc(w * 0.54f, h * 0.34f, w * 0.74f, h * 0.66f, -55f, 110f, false, paint)
                    canvas.drawArc(w * 0.60f, h * 0.22f, w * 0.88f, h * 0.78f, -55f, 110f, false, paint)
                }
                MiniIcon.MUTED -> {
                    val p = Path().apply {
                        moveTo(w * 0.16f, h * 0.37f); lineTo(w * 0.34f, h * 0.37f)
                        lineTo(w * 0.56f, h * 0.19f); lineTo(w * 0.56f, h * 0.81f)
                        lineTo(w * 0.34f, h * 0.63f); lineTo(w * 0.16f, h * 0.63f); close()
                        moveTo(w * 0.67f, h * 0.37f); lineTo(w * 0.88f, h * 0.63f)
                        moveTo(w * 0.88f, h * 0.37f); lineTo(w * 0.67f, h * 0.63f)
                    }
                    canvas.drawPath(p, paint)
                }
                MiniIcon.SWAP -> {
                    // 双向对调箭头：迷你小窗 ↔ 全屏主应用
                    val p = Path().apply {
                        moveTo(w * 0.22f, h * 0.34f); lineTo(w * 0.80f, h * 0.34f)
                        moveTo(w * 0.62f, h * 0.16f); lineTo(w * 0.80f, h * 0.34f); lineTo(w * 0.62f, h * 0.52f)
                        moveTo(w * 0.78f, h * 0.66f); lineTo(w * 0.20f, h * 0.66f)
                        moveTo(w * 0.38f, h * 0.48f); lineTo(w * 0.20f, h * 0.66f); lineTo(w * 0.38f, h * 0.84f)
                    }
                    canvas.drawPath(p, paint)
                }
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = side
        override fun getIntrinsicHeight(): Int = side
    }

    // ── 线程与反射工具 ────────────────────────────────────────────────────────

    private fun captureShellHandler() {
        if (shellHandler == null) {
            val looper = Looper.myLooper()
            if (looper != null) shellHandler = Handler(looper)
        }
    }

    private fun postOnShell(action: () -> Unit) {
        val h = shellHandler ?: main
        if (Looper.myLooper() == h.looper) action() else h.post(action)
    }

    private fun postOnShellDelayed(delayMs: Long, action: () -> Unit) {
        (shellHandler ?: main).postDelayed(action, delayMs)
    }

    private fun newTouchResult(cl: ClassLoader, status: Int, touchedMode: Int, taskInfo: Any?): Any? = runCatching {
        val cls = cl.loadClass(TOUCH_RESULT_CLS)
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 } ?: return@runCatching null
        ctor.isAccessible = true
        ctor.newInstance(status, touchedMode, taskInfo)
    }.getOrNull()

    private fun windowingModeOf(ti: ActivityManager.RunningTaskInfo): Int =
        (runCatching { ti.javaClass.getMethod("getWindowingMode").invoke(ti) as? Int }.getOrNull()) ?: 0

    private fun activityTypeOf(ti: ActivityManager.RunningTaskInfo): Int =
        (runCatching { ti.javaClass.getMethod("getActivityType").invoke(ti) as? Int }.getOrNull()) ?: 0

    private fun packageOf(ti: ActivityManager.RunningTaskInfo): String? =
        ti.baseActivity?.packageName ?: ti.topActivity?.packageName

    private fun resolveUid(context: Context, pkg: String?): Int {
        if (pkg.isNullOrBlank()) return -1
        return runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
    }

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f.get(target)
            }
            c = c.superclass
        }
        return null
    }

    private fun writeField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                f.set(target, value)
                return
            }
            c = c.superclass
        }
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
