package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FreeformEnhance
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.util.concurrent.ConcurrentHashMap

/**
 * 小窗增强套件（SystemUI / WM Shell 侧，[Catalog.FREEFORM_ENHANCE]）。
 *
 * 包含四项默认开启、可单独开关的子能力（总开关默认关闭，关态 0 hook）：
 *  1. **即用即走（关闭不留后台卡片）**：追踪进入小窗前是否已存在于最近任务；
 *     关闭退出动画结束后对符合策略的任务调用 `ActivityTaskManager.getService().removeTask(taskId)`。
 *  2. **主副窗一键互换**：在小窗顶部「…」原生胶囊菜单追加扩展行，将当前小窗切全屏、底层全屏应用切小窗。
 *  3. **单窗静音**：仅静音该小窗所属 UID 的音频播放流（通过 `AudioPlaybackConfiguration.getPlayerProxy().setVolume`），
 *     注册按需 [AudioManager.AudioPlaybackCallback] 锁住新流；关闭/全屏/卸载时自动恢复 `1.0f`。
 *  4. **单窗半透明**：切换该小窗 SurfaceControl 的 alpha（默认 55%，可调 25%~85%），
 *     任务重排或缩放后保持透明度；关闭/全屏/卸载时自动恢复 `1.0f`。
 */
object FreeformEnhanceFeature : Feature(Catalog.require(Catalog.FREEFORM_ENHANCE)) {

    private const val TAG = "FP-FreeformEnh"

    private const val REPO_CLS = "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository"
    private const val ANIM_CLS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation"
    private const val DOT_CLS = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot"
    private const val CLICK_CLS = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiCaptionClickListener"
    private const val CONTAINER_CLS = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiCaptionContainerView"
    private const val BLUR_HELPER_CLS = "com.android.wm.shell.multitasking.common.MultiTaskingBlurHelper"

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val ACTIVITY_TYPE_STANDARD = 1

    /** 关闭动画播完后再移除最近任务卡片，避免掐断原生缩小淡出动画。 */
    private const val REMOVE_TASK_DELAY_MS = 380L

    /** 主副互换时，先将小窗升全屏再把底层全屏降小窗的节拍差，避免两条 Transition 抢锁。 */
    private const val SWAP_STEP_DELAY_MS = 160L

    private const val EXT_ROW_TAG = "fp_freeform_ext_row"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var repoRef: Any? = null

    @Volatile
    private var appContext: Context? = null

    /** 打开小窗前就已经存在于最近任务（或曾以全屏运行过）的 taskId 集合。 */
    private val preExistingTasks = ConcurrentHashMap.newKeySet<Int>()

    /** 记录每个进入小窗的 taskId 是否属于「进入前已在后台」快照。 */
    private val taskWasInRecents = ConcurrentHashMap<Int, Boolean>()

    /** 当前被本模块临时静音的 UID -> 对应 taskId 集合（同一 App 多小窗可共存）。 */
    private val mutedUids = ConcurrentHashMap<Int, MutableSet<Int>>()

    /** 当前处于半透明态的小窗 taskId -> 目标 alpha。 */
    private val dimmedTasks = ConcurrentHashMap<Int, Float>()

    @Volatile
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    @Volatile
    private var audioManagerRef: AudioManager? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean): Boolean =
        !isSystemServer && processName == ScopeTarget.SYSTEM_UI.packageName

    override fun install(ctx: HookContext): InstallReport {
        val missing = ArrayList<String>()
        seedPreExistingTasks()

        val repoHooks = hookTaskRepository(ctx)
        if (repoHooks == 0) missing += REPO_CLS

        val exitHooks = hookFreeformExits(ctx)
        if (exitHooks == 0) missing += ANIM_CLS

        val menuHooks = hookCaptionMenu(ctx)
        if (menuHooks == 0) missing += DOT_CLS

        val total = installedHooks
        if (total == 0) {
            return InstallReport(0, missing, notes = listOf("未命中任何小窗宿主类"))
        }
        ctx.log(
            Log.INFO,
            "[$TAG] 小窗增强已挂上：hooked=$total (repo=$repoHooks exit=$exitHooks menu=$menuHooks) missing=$missing",
        )
        return InstallReport(
            hooked = total,
            missing = missing,
            notes = listOf("repo=$repoHooks", "exit=$exitHooks", "menu=$menuHooks"),
        )
    }

    override fun uninstall() {
        restoreAllMutedUids()
        restoreAllDimmedTasks()
        unregisterPlaybackCallback()
        preExistingTasks.clear()
        taskWasInRecents.clear()
        repoRef = null
        appContext = null
        super.uninstall()
    }

    // ── Hook 1：任务仓库（区分「临时小窗」vs「已有后台」+ 半透明保活） ─────────

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
                            // 必须在 proceed 前判断：此时该小窗刚出现，查已有记录决定是否属于「已有后台」
                            recordTaskOrigin(ti)
                        }
                        val res = chain.proceed()
                        try {
                            if (ti != null) {
                                when (name) {
                                    "onTaskAppeared" -> reapplyDimIfNeeded(ti.taskId)
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
            // 小窗转为全屏后成为正式任务，同时自动还原临时静音与半透明
            preExistingTasks.add(id)
            taskWasInRecents[id] = true
            restoreTaskState(id)
        } else if (wm == WINDOWING_MODE_FREEFORM) {
            reapplyDimIfNeeded(id)
        }
    }

    private fun onTaskEnded(taskId: Int) {
        restoreTaskState(taskId)
        taskWasInRecents.remove(taskId)
        preExistingTasks.remove(taskId)
    }

    // ── Hook 2：小窗关闭出口（即用即走 + 还原状态） ───────────────────────────

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

            // 切全屏时立即还原静音与半透明，并标记为非临时任务
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
                // 二次核实：若该任务此刻重新回到小窗或已升全屏前台，绝不误删
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
        // 优先走 ActivityTaskManager.getService().removeTask(taskId)（SystemUI 拥有 system uid）
        val atmOk = runCatching {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val svc = atmCls.getMethod("getService").invoke(null) ?: return@runCatching false
            val m = svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (m.invoke(svc, taskId) as? Boolean) ?: true
        }.getOrDefault(false)
        if (atmOk) return true

        // 兜底：ActivityManager.getService().removeTask(taskId)
        return runCatching {
            val amCls = Class.forName("android.app.ActivityManager")
            val svc = amCls.getMethod("getService").invoke(null) ?: return@runCatching false
            val m = svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (m.invoke(svc, taskId) as? Boolean) ?: true
        }.getOrDefault(false)
    }

    /** 安装瞬间扫一次当前最近任务，把已在后台的 taskId 记入 [preExistingTasks]。 */
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

    // ── Hook 3：小窗顶部「…」原生菜单追加扩展行（主副互换 / 静音 / 半透明） ────

    private fun hookCaptionMenu(ctx: HookContext): Int {
        val dotCls = runCatching { ctx.classLoader.loadClass(DOT_CLS) }.getOrNull() ?: return 0
        val addWindow = dotCls.declaredMethods.firstOrNull {
            it.name == "addWindow" && it.parameterTypes.size == 8 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return 0
        addWindow.isAccessible = true
        return runCatching {
            track(
                ctx.module.hook(addWindow).intercept { chain ->
                    val dot = chain.thisObject
                    val view = chain.args[0] as? ViewGroup
                    val wmMode = dot?.let { readField(it, "mWindowingMode") as? Int } ?: 0
                    val prefs = HookRegistry.prefs()
                    if (dot == null || view == null || wmMode != WINDOWING_MODE_FREEFORM || !prefs.isEnabled(Catalog.FREEFORM_ENHANCE)) {
                        return@intercept chain.proceed()
                    }
                    val cfg = FreeformEnhance.from(prefs)
                    if (!cfg.needsCaptionRow) return@intercept chain.proceed()

                    val modifiedArgs: Array<Any?> = chain.args.toTypedArray()
                    try {
                        val addedHeight = injectExtensionRow(dot, view, cfg, ctx)
                        if (addedHeight > 0) {
                            val origH = (modifiedArgs[4] as? Int) ?: 0
                            modifiedArgs[4] = origH + addedHeight
                            // 传 hasExtend=true：让 SurfaceControl 按多行圆角渲染且不画单行硬边框，
                            // 同时确保第一排按钮容器保留圆角背景
                            modifiedArgs[7] = true
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "注入小窗顶部扩展行失败（原菜单不受影响）：$t")
                    }
                    chain.proceed(modifiedArgs)
                },
            )
            1
        }.getOrElse {
            ctx.log(Log.WARN, "[$TAG] hook $DOT_CLS.addWindow 失败", it)
            0
        }
    }

    /**
     * 在 `MiuiCaptionContainerView` 底部追加一行「互换 / 静音 / 半透明」胶囊条。
     * @return 追加的总高度（像素），未添加返回 0。
     */
    private fun injectExtensionRow(
        dot: Any,
        container: ViewGroup,
        cfg: FreeformEnhance.Config,
        ctx: HookContext,
    ): Int {
        if (container.findViewWithTag<View>(EXT_ROW_TAG) != null) return 0
        val context = container.context ?: return 0
        appContext = context.applicationContext ?: context
        val ti = readField(dot, "mRunningTaskInfo") as? ActivityManager.RunningTaskInfo ?: return 0
        val taskId = ti.taskId
        val pkg = packageOf(ti)
        val uid = resolveUid(context, pkg)

        val d = context.resources.displayMetrics.density
        val isNight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isNight) 0xE626272B.toInt() else 0xF0FFFFFF.toInt()
        val inkColor = if (isNight) 0xF2FFFFFF.toInt() else 0xE6111111.toInt()
        val activeColor = 0xFF3482FF.toInt()
        val activeBg = if (isNight) 0x333482FF else 0x1F3482FF

        // 原第一排容器在 hasExtend=false 时背景是透明的（背景挂在 container 外壳上）；
        // 改为两排后，把外壳背景清透明，给第一排与扩展排各自挂独立圆角背景，与原生扩展菜单一致。
        val firstRow = call(container, "getCaptionButtonContainer") as? View
        if (firstRow != null && firstRow.background == null) {
            container.background = null
            applyCapsuleBg(context, firstRow, bgColor, 24 * d, ctx.classLoader)
        }

        val displayId = (call(ti, "getDisplayId") as? Int) ?: 0
        val bottomFullscreen = if (cfg.swapFullscreen) findBottomFullscreenTask(displayId) else null
        val canSwap = bottomFullscreen != null && bottomFullscreen.taskId != taskId

        val rowHeightPx = (40 * d).toInt()
        val topMarginPx = (6 * d).toInt()

        val row = LinearLayout(context).apply {
            tag = EXT_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val ph = (8 * d).toInt()
            val pv = (4 * d).toInt()
            setPadding(ph, pv, ph, pv)
            applyCapsuleBg(context, this, bgColor, 20 * d, ctx.classLoader)
        }

        fun makeActionChip(
            label: String,
            icon: ExtIcon,
            active: Boolean,
            enabled: Boolean = true,
            onClick: () -> Unit,
        ): View {
            val fg = when {
                !enabled -> (inkColor and 0x00FFFFFF) or 0x55000000
                active -> activeColor
                else -> inkColor
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isEnabled = enabled
                isClickable = enabled
                val ch = (10 * d).toInt()
                val cv = (5 * d).toInt()
                setPadding(ch, cv, ch, cv)
                if (active) {
                    background = GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setColor(activeBg)
                    }
                }
                val side = (16 * d).toInt()
                addView(
                    ImageView(context).apply {
                        setImageDrawable(ExtStrokeDrawable(icon, fg, side, d))
                    },
                    LinearLayout.LayoutParams(side, side).apply { marginEnd = (4 * d).toInt() },
                )
                addView(
                    TextView(context).apply {
                        text = label
                        setTextColor(fg)
                        textSize = 12f
                        isSingleLine = true
                    },
                )
                if (enabled) {
                    setOnClickListener {
                        try {
                            onClick()
                        } catch (t: Throwable) {
                            Log.w(TAG, "扩展按钮「$label」执行异常：$t")
                        }
                    }
                }
            }
        }

        var items = 0
        if (cfg.swapFullscreen) {
            row.addView(
                makeActionChip("互换", ExtIcon.SWAP, active = false, enabled = canSwap) {
                    closeHandleMenu(dot)
                    val targetBottom = bottomFullscreen ?: return@makeActionChip
                    performSwapFullscreen(dot, ti, targetBottom)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            items++
        }

        if (cfg.windowMute && uid >= 10_000) {
            val muted = isTaskMuted(uid, taskId)
            row.addView(
                makeActionChip(if (muted) "已静音" else "静音", ExtIcon.MUTE, active = muted) {
                    toggleMute(context, uid, taskId, pkg)
                    closeHandleMenu(dot)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            items++
        }

        if (cfg.windowOpacity) {
            val dimmed = dimmedTasks.containsKey(taskId)
            row.addView(
                makeActionChip(if (dimmed) "还原" else "半透明", ExtIcon.OPACITY, active = dimmed) {
                    toggleOpacity(taskId, cfg.dimAlpha)
                    closeHandleMenu(dot)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            items++
        }

        if (items == 0) return 0

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx).apply {
            topMargin = topMarginPx
        }
        container.addView(row, lp)
        return rowHeightPx + topMarginPx
    }

    private fun applyCapsuleBg(
        context: Context,
        view: View,
        fallbackColor: Int,
        radiusPx: Float,
        cl: ClassLoader,
    ) {
        val isLight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val blurOk = runCatching {
            val helper = cl.loadClass(BLUR_HELPER_CLS)
            val setBlur = helper.getMethod(
                "setViewBlur",
                Context::class.java,
                Int::class.javaPrimitiveType,
                View::class.java,
                View::class.java,
                Boolean::class.javaPrimitiveType,
            )
            val ok = (setBlur.invoke(null, context, 1, view, view, isLight) as? Boolean) == true
            if (ok) {
                helper.getMethod("setRadius", View::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, view, radiusPx.toInt())
            }
            ok
        }.getOrDefault(false)
        if (!blurOk) {
            view.background = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(fallbackColor)
            }
        }
    }

    private fun closeHandleMenu(dot: Any) {
        runCatching {
            val ctrl = readField(dot, "mHandleMenu")
            if (ctrl != null) {
                call(ctrl, "releaseViewWithAnim")
                return
            }
            call(dot, "closeHandleMenu")
        }
    }

    // ── 能力 A：主副窗一键互换 ────────────────────────────────────────────────

    private fun findBottomFullscreenTask(displayId: Int): ActivityManager.RunningTaskInfo? = runCatching {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val inst = atmCls.getMethod("getInstance").invoke(null) ?: return null
        val m = inst.javaClass.getMethod(
            "getTopVisibleTaskOnDisplay",
            IntArray::class.java,
            IntArray::class.java,
            Array<String>::class.java,
            Array<String>::class.java,
            Int::class.javaPrimitiveType,
        )
        val ti = m.invoke(inst, intArrayOf(WINDOWING_MODE_FULLSCREEN), null, null, null, displayId)
            as? ActivityManager.RunningTaskInfo
        if (ti != null && activityTypeOf(ti) == ACTIVITY_TYPE_STANDARD) ti else null
    }.getOrNull()

    private fun performSwapFullscreen(
        dot: Any,
        freeformTask: ActivityManager.RunningTaskInfo,
        bottomFullscreenTask: ActivityManager.RunningTaskInfo,
    ) {
        val clickListener = readField(dot, "mOnMiuiCaptionClickListener")
        val starter = clickListener?.let { readField(it, "mMulWinSwitchAnimStarter") }
            ?: runCatching {
                val impl = dot.javaClass.classLoader!!.loadClass("com.android.wm.shell.dagger.MultiTaskingControllerImpl")
                val inst = impl.getMethod("getInstance").invoke(null)
                call(inst!!, "getMulWinSwitchStarter")
            }.getOrNull()
        val organizer = readField(dot, "mShellTaskOrganizer")
            ?: clickListener?.let { readField(it, "mShellTaskOrganizer") }

        if (starter == null) {
            Log.w(TAG, "主副互换失败：找不到 MulWinSwitchAnimStarter")
            return
        }

        // 原小窗即将升全屏：解除它的临时静音/半透明，并标记为非临时任务
        preExistingTasks.add(freeformTask.taskId)
        taskWasInRecents[freeformTask.taskId] = true
        restoreTaskState(freeformTask.taskId)
        // 原全屏即将降小窗：它本来就在后台，保留其后台卡片
        preExistingTasks.add(bottomFullscreenTask.taskId)
        taskWasInRecents[bottomFullscreenTask.taskId] = true

        runCatching {
            call(starter, "switchFreeformToFullscreen", freeformTask)
            Log.i(TAG, "主副互换第一步：小窗 task=${freeformTask.taskId} → 全屏")
        }.onFailure { Log.w(TAG, "小窗转全屏失败：$it") }

        if (organizer != null) {
            main.postDelayed({
                runCatching {
                    call(starter, "switchFullscreenToFreeform", organizer, bottomFullscreenTask)
                    Log.i(TAG, "主副互换第二步：全屏 task=${bottomFullscreenTask.taskId} → 小窗")
                }.onFailure { Log.w(TAG, "底层全屏转小窗失败：$it") }
            }, SWAP_STEP_DELAY_MS)
        }
    }

    // ── 能力 B：单窗静音（按 UID 临时压低播放流，不写系统持久化表） ─────────────

    private fun isTaskMuted(uid: Int, taskId: Int): Boolean =
        mutedUids[uid]?.contains(taskId) == true

    private fun toggleMute(context: Context, uid: Int, taskId: Int, pkg: String?) {
        val currentlyMuted = isTaskMuted(uid, taskId)
        if (currentlyMuted) {
            unmuteTask(uid, taskId)
            Log.i(TAG, "取消单窗静音：task=$taskId uid=$uid pkg=$pkg")
        } else {
            val set = mutedUids.computeIfAbsent(uid) { ConcurrentHashMap.newKeySet() }
            set.add(taskId)
            ensurePlaybackCallback(context)
            applyUidVolume(context, uid, 0f)
            Log.i(TAG, "开启单窗静音：task=$taskId uid=$uid pkg=$pkg")
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

    private fun restoreAllMutedUids() {
        val uids = mutedUids.keys.toList()
        mutedUids.clear()
        val ctx = appContext ?: return
        for (uid in uids) {
            applyUidVolume(ctx, uid, 1.0f)
        }
    }

    // ── 能力 C：单窗半透明（直接调节小窗 leash SurfaceControl alpha） ───────────

    private fun toggleOpacity(taskId: Int, targetAlpha: Float) {
        if (dimmedTasks.remove(taskId) != null) {
            applyTaskAlpha(taskId, 1.0f)
            Log.i(TAG, "恢复小窗不透明：task=$taskId")
        } else {
            dimmedTasks[taskId] = targetAlpha
            applyTaskAlpha(taskId, targetAlpha)
            Log.i(TAG, "开启小窗半透明：task=$taskId alpha=$targetAlpha")
        }
    }

    private fun reapplyDimIfNeeded(taskId: Int) {
        val alpha = dimmedTasks[taskId] ?: return
        main.post { applyTaskAlpha(taskId, alpha) }
    }

    private fun applyTaskAlpha(taskId: Int, alpha: Float) {
        val repo = repoRef ?: return
        runCatching {
            val info = call(repo, "getMiuiFreeformTaskInfo", taskId) ?: return
            val leash = call(info, "getLeash") as? SurfaceControl ?: return
            if (!leash.isValid) return
            SurfaceControl.Transaction().use { tx ->
                tx.setAlpha(leash, alpha).apply()
            }
        }.onFailure { Log.w(TAG, "设置小窗透明度失败 task=$taskId alpha=$alpha：$it") }
    }

    private fun restoreTaskState(taskId: Int) {
        if (dimmedTasks.remove(taskId) != null) {
            applyTaskAlpha(taskId, 1.0f)
        }
        val uidsToCheck = mutedUids.entries.filter { taskId in it.value }.map { it.key }
        for (uid in uidsToCheck) {
            unmuteTask(uid, taskId)
        }
    }

    private fun restoreAllDimmedTasks() {
        val ids = dimmedTasks.keys.toList()
        dimmedTasks.clear()
        for (id in ids) {
            applyTaskAlpha(id, 1.0f)
        }
    }

    // ── 自绘细线图标（与系统胶囊菜单视觉统一） ──────────────────────────────

    private enum class ExtIcon { SWAP, MUTE, OPACITY }

    private class ExtStrokeDrawable(
        private val kind: ExtIcon,
        color: Int,
        private val side: Int,
        density: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
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
                ExtIcon.SWAP -> {
                    // 上下双向箭头：主副互换
                    val p = Path().apply {
                        moveTo(w * 0.25f, h * 0.34f); lineTo(w * 0.78f, h * 0.34f)
                        moveTo(w * 0.60f, h * 0.16f); lineTo(w * 0.78f, h * 0.34f); lineTo(w * 0.60f, h * 0.52f)
                        moveTo(w * 0.75f, h * 0.66f); lineTo(w * 0.22f, h * 0.66f)
                        moveTo(w * 0.40f, h * 0.48f); lineTo(w * 0.22f, h * 0.66f); lineTo(w * 0.40f, h * 0.84f)
                    }
                    canvas.drawPath(p, paint)
                }
                ExtIcon.MUTE -> {
                    // 喇叭 + 斜杠
                    val p = Path().apply {
                        moveTo(w * 0.18f, h * 0.38f); lineTo(w * 0.36f, h * 0.38f)
                        lineTo(w * 0.58f, h * 0.20f); lineTo(w * 0.58f, h * 0.80f)
                        lineTo(w * 0.36f, h * 0.62f); lineTo(w * 0.18f, h * 0.62f); close()
                        moveTo(w * 0.68f, h * 0.36f); lineTo(w * 0.88f, h * 0.64f)
                        moveTo(w * 0.88f, h * 0.36f); lineTo(w * 0.68f, h * 0.64f)
                    }
                    canvas.drawPath(p, paint)
                }
                ExtIcon.OPACITY -> {
                    // 半透明水滴/叠圆
                    canvas.drawCircle(w * 0.42f, h * 0.50f, w * 0.28f, paint)
                    val prev = paint.alpha
                    paint.alpha = (prev * 0.45f).toInt()
                    canvas.drawCircle(w * 0.62f, h * 0.50f, w * 0.28f, paint)
                    paint.alpha = prev
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

    // ── 反射工具 ──────────────────────────────────────────────────────────

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
