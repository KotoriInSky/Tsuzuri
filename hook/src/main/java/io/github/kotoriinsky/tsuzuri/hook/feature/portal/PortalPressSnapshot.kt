package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContentCapabilities
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import kotlin.math.hypot

/**
 * 长按识图的截图定格在**长按开始**的那一帧（`portal_image_region.press_snapshot`，默认关）。
 *
 * ## 现象与根因
 *
 * 传送门的全屏截图在 `TaplusMainFloatView.prepareScreen → captureScreenshot()` 里取
 * （`research/decompiled/contentextension/.../floatview/TaplusMainFloatView.java:299,382`），
 * 而这个 View 要等来源 App 里的抓取器完成长按、`onContentReceived → onStartPick` 之后才建。
 * 到那时来源 App 往往已经响应了自己的长按（弹出菜单、压暗、放大预览），
 * 所以**识别用的图、保存的图、选区所在的画面**都不是用户按下时看到的那一屏。
 *
 * Flyme 的两段式长按（≈250ms 开始抓取、800ms 弹卡）在 App 自己的长按
 * 生效之前就取好了图。这里照这个时序做。
 *
 * ## 做法：两个进程各一半，属于 [Catalog.SCREEN_IMAGE]（与识图选区同一类）
 *
 *  - **SystemUI（[PortalPressSnapshotTrigger]）**：复用 [SwipeEventHub] 的全局输入监视通道，
 *    **只观察不消费**。手指按下后 [HOLD_MS] 内没有移出 touchSlop、没有第二根手指，
 *    就给 contentextension 发一条定向广播（坐标 + 时刻）。广播在后台线程发，输入线程零 IPC。
 *  - **contentextension（[PortalPressSnapshotHost]）**：收到就立刻用宿主自己的
 *    `DisplayCaptureUtils.getFullScreenshot` 截一张存着；等 `captureScreenshot()` 真正执行时，
 *    如果缓存够新（[FRESH_MS]）且宿主的第一触点离缓存触点不超过 [MATCH_DP]，
 *    就让那次 `getFullScreenshot` 直接返回缓存 —— 宿主后面的导航栏裁剪、选区计算
 *    （包括 [PortalImageRegion] 的横带）全部原样作用在这张定格图上。
 *
 * 选区跟着一起定格：选区由第一触点算，触点本来就是按下点；截图换成按下时的那一帧之后，
 * 选区落在的就是按下时的画面。
 *
 * ## 降级
 *
 * 任何一步失败（没收到广播、截图失败、触点对不上、超时）都**退回宿主原截图**，
 * 不改变其它行为。缓存只用一次，用完或过期即回收。
 *
 * ## 代价
 *
 * 开关打开时，每次「静止按住超过 [HOLD_MS]」都会在 contentextension 里截一次屏
 * （不论最后有没有触发识图）。所以默认关，且只在 SystemUI 侧确认开关打开后才发信号。
 */
internal object PortalPressSnapshot {

    const val TAG = "FP-PressSnap"
    const val SUB = "press_snapshot"

    const val ACTION = "io.github.kotoriinsky.tsuzuri.PORTAL_PRESS_SNAPSHOT"
    const val EXTRA_X = "x"
    const val EXTRA_Y = "y"

    /** 只接受持有它的发送方（SystemUI 有，第三方应用拿不到）。 */
    const val SENDER_PERMISSION = "android.permission.STATUS_BAR_SERVICE"

    /**
     * 按住多久算「长按开始」：早于来源 App 自己的长按（系统默认 400ms），也早于传送门弹卡。
     * 2026-10-25 按用户手感 180 → 120 → 50 → **200ms 定稿**：本机 `long_press_timeout = 300`，
     * 200ms 仍留约 100ms 余量；而 50ms 比多数点按还短，几乎每次触摸都会截一次屏。
     */
    const val HOLD_MS = 200L

    /** 定格图的有效期：传送门弹卡通常在 1s 内，留出余量。 */
    const val FRESH_MS = 3000L

    /** 宿主第一触点与定格触点的最大距离（dp）。超出 = 不是同一次长按。 */
    const val MATCH_DP = 48f
}

/** SystemUI 一侧：判定「静止按住」并发信号。 */
internal object PortalPressSnapshotTrigger : Feature(Catalog.require(Catalog.SCREEN_IMAGE)) {

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == ScopeTarget.SYSTEM_UI.packageName

    override val installId: String get() = "screen_image:press_snapshot"

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) =
        ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, PortalPressSnapshot.SUB)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var hookCtx: HookContext? = null

    // 以下只在输入线程读写
    private var downX = 0f
    private var downY = 0f
    private var slopSq = 0f
    private var armed = false

    @Volatile
    private var firedX = 0f

    @Volatile
    private var firedY = 0f

    @Volatile
    private var sent = 0

    private val fire = Runnable {
        val ctx = hookCtx ?: return@Runnable
        // 开关在后台线程读，不在输入线程上碰配置
        if (!ContentCapabilities.on(ctx.prefs, key, PortalPressSnapshot.SUB)) return@Runnable
        val context = SystemUiEnv.context() ?: return@Runnable
        try {
            context.sendBroadcast(
                Intent(PortalPressSnapshot.ACTION)
                    .setPackage(ScopeTarget.CONTENT_EXTENSION.packageName)
                    .putExtra(PortalPressSnapshot.EXTRA_X, firedX)
                    .putExtra(PortalPressSnapshot.EXTRA_Y, firedY)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            )
            if (sent++ < 3) ctx.log(Log.INFO, "[${PortalPressSnapshot.TAG}] 静止按住，已通知传送门定格（${firedX.toInt()},${firedY.toInt()}）")
        } catch (t: Throwable) {
            if (sent++ < 3) ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 定格信号发送失败", t)
        }
    }

    private val listener = SwipeEventHub.Listener { e -> onTouch(e); false }

    private fun onTouch(e: MotionEvent) {
        val h = handler ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                firedX = downX
                firedY = downY
                armed = true
                h.removeCallbacks(fire)
                h.postDelayed(fire, PortalPressSnapshot.HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed) return
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dx * dx + dy * dy > slopSq) {
                    armed = false
                    h.removeCallbacks(fire)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 抬手早于 HOLD_MS = 点按，不是长按；多指 = 缩放等手势
                armed = false
                h.removeCallbacks(fire)
            }
        }
    }

    override fun install(ctx: HookContext): InstallReport {
        hookCtx = ctx
        val context = SystemUiEnv.context()
        val slop = context?.let { ViewConfiguration.get(it).scaledTouchSlop.toFloat() } ?: 24f
        slopSq = slop * slop
        val t = HandlerThread("fp-press-snap").apply { start() }
        thread = t
        handler = Handler(t.looper)
        SwipeEventHub.addListener(listener)
        val hub = SwipeEventHub.ensureInstalled(ctx)
        ctx.log(Log.INFO, "[${PortalPressSnapshot.TAG}] SystemUI 侧已挂上：hub=${hub.describe()} slop=$slop")
        return InstallReport(hooked = if (hub.isEffective) 1 else 0, missing = hub.missing)
    }

    override fun uninstall() {
        SwipeEventHub.removeListener(listener)
        handler?.removeCallbacks(fire)
        thread?.quitSafely()
        thread = null
        handler = null
        hookCtx = null
        super.uninstall()
    }
}

/**
 * contentextension 一侧：收信号就截图，`captureScreenshot()` 时换上定格图。
 *
 * ⚠️ **不是独立的 Feature**：`HookRegistry` 每个进程每个键只装一份实现
 * （`already installed, skip`），contentextension 里这个键已经归 [PortalImageRegion]，
 * 单独注册会被静默跳过（2026-10-25 真机踩到）。所以由 [PortalImageRegion.install] 调用。
 */
internal object PortalPressSnapshotHost {

    private val key = Catalog.SCREEN_IMAGE

    private const val VIEW_CLS = "com.miui.contentextension.text.floatview.TaplusMainFloatView"
    private const val CAPTURE_CLS = "com.miui.contentextension.utils.DisplayCaptureUtils"
    private const val SERVICE_CLS = "com.miui.contentextension.services.TextContentExtensionService"

    private class Snap(val bitmap: Bitmap, val x: Float, val y: Float, val atMs: Long)

    private val lock = Any()
    private var snap: Snap? = null

    /** 本线程正在执行 `captureScreenshot()` 时要换上的那张（一次性）。 */
    private val pending = ThreadLocal<Bitmap?>()

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var captureMethod: java.lang.reflect.Method? = null
    /**
     * `TextContentExtensionService.sFirstTouchPoint`（静态，`getFirstTouchPoint()` 返回的就是它）。
     * 不走 View 的 `mService`：那个字段在父类 `BaseFloatView` 上，`getDeclaredField` 拿不到（2026-10-25 真机）。
     */
    private var firstTouchField: java.lang.reflect.Field? = null

    private var used = 0
    private var skipped = 0

    /** 返回装上的 hook 数；句柄交给调用方的 [track] 管理。 */
    fun install(ctx: HookContext, track: (io.github.libxposed.api.XposedInterface.HookHandle) -> Unit): InstallReport {
        val viewCls = try {
            Class.forName(VIEW_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            return InstallReport(0, listOf(VIEW_CLS))
        }
        val capCls = try {
            Class.forName(CAPTURE_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            return InstallReport(0, listOf(CAPTURE_CLS))
        }
        val capture = viewCls.declaredMethods.firstOrNull { it.name == "captureScreenshot" && it.parameterTypes.isEmpty() }
            ?: return InstallReport(0, listOf("$VIEW_CLS.captureScreenshot"))
        val full = try {
            capCls.getMethod("getFullScreenshot", Context::class.java)
        } catch (t: Throwable) {
            return InstallReport(0, listOf("$CAPTURE_CLS.getFullScreenshot(Context)"))
        }
        captureMethod = full
        firstTouchField = try {
            Class.forName(SERVICE_CLS, false, ctx.classLoader)
                .getDeclaredField("sFirstTouchPoint").apply { isAccessible = true }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 拿不到 sFirstTouchPoint，无法核对触点，定格不生效", t)
            return InstallReport(0, listOf("$SERVICE_CLS.sFirstTouchPoint"))
        }

        val t = HandlerThread("fp-press-snap").apply { start() }
        thread = t
        worker = Handler(t.looper)

        var hooked = 0
        track(
            ctx.module.hook(capture).intercept { chain ->
                try {
                    pending.set(take(ctx, chain.thisObject))
                } catch (e: Throwable) {
                    pending.set(null)
                    ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 取定格图失败（用宿主原截图）", e)
                }
                try {
                    chain.proceed()
                } finally {
                    pending.get()?.let { if (!it.isRecycled) it.recycle() }
                    pending.set(null)
                }
            },
        )
        hooked++
        track(
            ctx.module.hook(full).intercept { chain ->
                val b = pending.get()
                if (b != null && !b.isRecycled) {
                    // 交出去：宿主会 copy 后 recycle 它，所以 ThreadLocal 里清掉，免得 finally 再回收一次
                    pending.set(null)
                    b
                } else {
                    chain.proceed()
                }
            },
        )
        hooked++

        registerReceiver(ctx, attempt = 1)
        ctx.log(Log.INFO, "[${PortalPressSnapshot.TAG}] contentextension 侧已挂上 hooked=$hooked")
        return InstallReport(hooked = hooked)
    }

    /** 判定这次能不能用定格图；能就取走（一次性），返回要交给宿主的位图。 */
    private fun take(ctx: HookContext, view: Any?): Bitmap? {
        if (!ContentCapabilities.on(ctx.prefs, key, PortalPressSnapshot.SUB)) return null
        val s = synchronized(lock) { snap.also { snap = null } } ?: return note(ctx, "没有定格图")
        val age = SystemClock.uptimeMillis() - s.atMs
        if (age > PortalPressSnapshot.FRESH_MS) {
            s.bitmap.recycle()
            return note(ctx, "定格图已过期 ${age}ms")
        }
        val first = firstTouchField?.get(null) as? PointF
        if (first == null) {
            s.bitmap.recycle()
            return note(ctx, "宿主没有第一触点")
        }
        val density = appContext?.resources?.displayMetrics?.density ?: 3f
        val dist = hypot(first.x - s.x, first.y - s.y)
        if (dist > PortalPressSnapshot.MATCH_DP * density) {
            s.bitmap.recycle()
            return note(ctx, "触点对不上：宿主(${first.x.toInt()},${first.y.toInt()}) 定格(${s.x.toInt()},${s.y.toInt()})")
        }
        if (used++ < 5) {
            ctx.log(Log.INFO, "[${PortalPressSnapshot.TAG}] 用定格图：截于宿主截图前 ${age}ms，触点偏差 ${dist.toInt()}px")
        }
        return s.bitmap
    }

    private fun note(ctx: HookContext, why: String): Bitmap? {
        if (skipped++ < 5) ctx.log(Log.INFO, "[${PortalPressSnapshot.TAG}] 不用定格图（$why），用宿主原截图")
        return null
    }

    private fun onSignal(ctx: HookContext, x: Float, y: Float) {
        if (!ContentCapabilities.on(ctx.prefs, key, PortalPressSnapshot.SUB)) return
        val context = appContext ?: return
        val m = captureMethod ?: return
        val at = SystemClock.uptimeMillis()
        val bmp = try {
            // 不在 captureScreenshot 里 → pending 为空 → 这次调用走宿主原逻辑
            m.invoke(null, context) as? Bitmap
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 定格截图失败", t)
            null
        } ?: return
        val old = synchronized(lock) { snap.also { snap = Snap(bmp, x, y, at) } }
        old?.bitmap?.recycle()
        // 过期就回收，不长期占着一整屏的内存
        worker?.postDelayed({
            val stale = synchronized(lock) { snap?.takeIf { it.atMs == at }?.also { snap = null } }
            stale?.bitmap?.recycle()
        }, PortalPressSnapshot.FRESH_MS)
    }

    private fun registerReceiver(ctx: HookContext, attempt: Int) {
        val context = try {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
        val w = worker ?: return
        if (context == null) {
            // 安装时机可能早于 Application 创建：短间隔重试几次
            if (attempt < 10) w.postDelayed({ registerReceiver(ctx, attempt + 1) }, 1000L)
            else ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 拿不到 Application，定格信号收不到")
            return
        }
        appContext = context
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val x = intent.getFloatExtra(PortalPressSnapshot.EXTRA_X, Float.NaN)
                val y = intent.getFloatExtra(PortalPressSnapshot.EXTRA_Y, Float.NaN)
                if (x.isNaN() || y.isNaN()) return
                onSignal(ctx, x, y)
            }
        }
        try {
            // 接收器跑在 worker 线程（截图不占主线程）；只收持有 STATUS_BAR_SERVICE 的发送方
            context.registerReceiver(
                r,
                IntentFilter(PortalPressSnapshot.ACTION),
                PortalPressSnapshot.SENDER_PERMISSION,
                w,
                Context.RECEIVER_EXPORTED,
            )
            receiver = r
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[${PortalPressSnapshot.TAG}] 注册定格接收器失败", t)
        }
    }

    fun release() {
        receiver?.let { r -> runCatching { appContext?.unregisterReceiver(r) } }
        receiver = null
        thread?.quitSafely()
        thread = null
        worker = null
        synchronized(lock) { snap?.bitmap?.recycle(); snap = null }
    }
}
