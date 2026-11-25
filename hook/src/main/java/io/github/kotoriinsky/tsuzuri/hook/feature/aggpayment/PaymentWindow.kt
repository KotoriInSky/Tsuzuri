package io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.AggPayment
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanShortcuts
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanTarget
import io.github.libxposed.api.XposedInterface

/**
 * 聚合支付的 Flyme 形态：**呼出即用小窗打开最近用的那项，小窗底部挂一条入口条，与小窗是一块**。
 *
 * ## 这里怎么做
 *
 * HyperOS 的小窗由 WM Shell 管，WM Shell 就跑在 SystemUI 进程里，task leash 在本进程可得：
 *
 *  1. hook `MultiTaskingTaskRepository` 的 task 出现 / 变化 / 消失，拿到仓库实例；
 *  2. 当前要显示的包的**最上层小窗** → `MiuiFreeformModeTaskInfo` 的 leash、逻辑 bounds、缩放、圆角；
 *  3. 入口条用 `SurfaceControlViewHost` 画，surface 挂成 leash 的子层，`setScale(1/scale)` 抵消
 *     leash 的缩放（屏上尺寸固定）。作为子层，进场 / 关闭 / 拖动 / 缩放动画与小窗同帧，圆角裁剪也一起。
 *
 * ## 为什么在小窗底边以内（不像 Flyme 挂在窗外）
 *
 * task leash 的 windowCrop + 圆角会裁掉子层（真机 SF：摆在底边外时该层 geomLayerBounds 为空；
 * 伸进 2px 时只剩那 2px）。MIUI 自己的小窗阴影能画到窗外，是因为它的 SurfaceFlinger 按图层名
 * `Miui Shadow stack of Task` 放行（libsurfaceflinger.so 里硬编码），而且那类图层不吃 leash 的缩放 ——
 * 冒用这个名字既脆弱又跟不上缩放动画，所以不用。条就放在窗内底部、四周留边、圆角与小窗同心，
 * 压在内容上方：付款码、扫码框等主要信息都在小窗上部（用户确认）。
 *
 * ## 切换与生命周期
 *
 *  - 点条目：新条目按**当前小窗**的位置与缩放打开，新小窗出现后入口条挪过去、再关掉旧小窗；
 *  - 入口条一直挂到小窗关闭（该包最上层小窗消失 / 退出小窗形态）为止。
 *
 * 跳板（支付宝 1002 会再起两个 Task）的位置与清理由 system_server 侧的
 * `FreeformTrampolinePlacement` 负责；这里只认「这个包最上层的那个小窗」。
 *
 * ## 「最近用的那项」
 *
 * 与 Flyme 一致：呼出打开第 0 项；**只有在入口条上点了某项才把它挪到最前**。
 */
internal object PaymentWindow {

    private const val REPO = "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository"
    private const val FREEFORM_ANIM = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation"

    /** 切换后这段时间内，本次切换涉及的小窗开 / 关都不播 MIUI 动画（跳板链 + 关旧窗都在这里面）。 */
    private const val SWITCH_WINDOW_MS = 2_500L

    // WindowManager.TRANSIT_*（隐藏常量）
    private const val TRANSIT_OPEN = 1
    private const val TRANSIT_CLOSE = 2
    private const val TRANSIT_TO_FRONT = 3
    private const val TRANSIT_TO_BACK = 4

    private const val MODULE_PACKAGE = "io.github.kotoriinsky.tsuzuri"

    private const val PREFS = "tsuzuri_agg_payment"
    private const val PREF_ORDER = "order"

    /** 跳板链在这段时间内还没落地时，找不到小窗不算结束。 */
    private const val SETTLE_MS = 3_000L

    /**
     * 入口条逐事件诊断（默认关）。排「拖动 / 改大小 / 切换时入口条跳变」用：
     * `adb shell setprop log.tag.FP-AggBar DEBUG` 后重载 SystemUI（热重载即可），
     * 每个 task 事件、每次查到的几何、apply 的决定与 main.post 的排队耗时各一行。
     */
    private const val DIAG_TAG = "FP-AggBar"

    @Volatile
    private var diag = false

    private fun d(msg: String) {
        if (diag) Log.d(DIAG_TAG, msg)
    }

    private const val BAR_HEIGHT_DP = 64f
    /** 条与小窗左右、底边的间距；条的圆角 = 小窗圆角 − 这个值（同心）。 */
    private const val MARGIN_DP = 8f
    private const val ICON_DP = 34f
    private const val LAYER = 1_000_000

    /** 等 MIUI 动画播完：每隔多久看一次、最多等多久（超时就直接显示）。 */
    private const val ANIM_POLL_MS = 50L
    private const val ANIM_WAIT_MAX_MS = 1_500L
    private const val FADE_MS = 150L

    /**
     * Flyme 的四个标签（SystemUITools `wechat_money` / `wechat_scan` / `alipay_pay` / `alipay_scan`）：
     * 支付宝 1002 自己发布的短标签也是「收付款」，与微信那项并排时分不出来。
     */
    private val LABELS = mapOf(
        "com.tencent.mm/launch_type_offline_wallet" to "收付款",
        "com.tencent.mm/launch_type_scan_qrcode" to "扫一扫",
        "com.eg.android.AlipayGphone/1002" to "付款码",
        "com.eg.android.AlipayGphone/1001" to "扫一扫",
    )

    private val main = Handler(Looper.getMainLooper())

    private class Target(
        val taskId: Int,
        val leash: SurfaceControl,
        /** task 逻辑 bounds（未乘缩放）。 */
        val bounds: Rect,
        val scale: Float,
        /** 小窗屏上圆角（px），取不到是 0。 */
        val cornerRadius: Float,
        /** MIUI 的进场 / 恢复 / 缩放动画还在播。 */
        val animating: Boolean,
    )

    private class Session(
        val items: List<PaymentShortcut>,
        val current: PaymentShortcut,
        val startedAt: Long,
        /** 切换条目时要关掉的旧小窗；新小窗出现后才关。 */
        val closeTaskId: Int?,
    ) {
        val pkg: String get() = current.packageName
    }

    @Volatile
    private var session: Session? = null

    /** 正在进行的切换：新条目的包、要关的旧小窗、沿用的位置。在 shell 线程读。 */
    private class Switch(val pkg: String, val oldTaskId: Int?, val bounds: Rect, val scale: Float, val radius: Float, val until: Long)

    @Volatile
    private var switching: Switch? = null

    @Volatile
    private var repo: Any? = null

    private var ctxRef: HookContext? = null
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null

    // 以下只在主线程读写
    private var host: SurfaceControlViewHost? = null
    private var barSc: SurfaceControl? = null
    private var barBackground: GradientDrawable? = null
    private var attached: Target? = null
    private var barWidth = 0
    private var barRadius = 0f
    private var barShown = false
    private var waitSince = 0L
    private var fade: android.animation.ValueAnimator? = null

    // ── 安装 ──────────────────────────────────────────────────────────

    /** @return 挂上的 hook 数。缺仓库类时返回 0：小窗照样能开，只是没有入口条。 */
    fun install(ctx: HookContext, context: Context, track: (XposedInterface.HookHandle) -> Unit, show: () -> Unit): Int {
        ctxRef = ctx
        app = context
        diag = runCatching { Log.isLoggable(DIAG_TAG, Log.DEBUG) }.getOrDefault(false)
        if (diag) ctx.log(Log.INFO, "聚合支付：入口条诊断已开（logcat 标签 $DIAG_TAG）")
        registerReceiver(ctx, context, show)

        val cls = runCatching { ctx.classLoader.loadClass(REPO) }.getOrElse {
            ctx.log(Log.WARN, "聚合支付：找不到 $REPO，入口条不可用", it)
            return 0
        }
        var hooked = 0
        for (name in listOf("onTaskAppeared", "onOpenTransitionReady", "onTaskInfoChanged", "onTaskVanished")) {
            val m = cls.declaredMethods.firstOrNull { it.name == name } ?: continue
            m.isAccessible = true
            runCatching {
                track(ctx.module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    try {
                        repo = chain.thisObject
                        // 会话期间任何 task 事件都重找一次：消失的 task 拿不到 baseActivity，
                        // 按包名筛会漏掉跳板消失那一下，入口条就停在被清掉的跳板上
                        session?.let {
                            d("事件 $name thread=${Thread.currentThread().name}")
                            refresh(it, name)
                        }
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "聚合支付：处理 $name 失败", t)
                    }
                    r
                })
                hooked++
            }.onFailure { ctx.log(Log.WARN, "聚合支付：hook $name 失败", it) }
        }
        hooked += installInstantSwitch(ctx, track)
        ctx.log(Log.INFO, "聚合支付：小窗仓库 hook=$hooked")
        return hooked
    }

    fun teardown() {
        session = null
        repo = null
        runCatching { receiver?.let { app?.unregisterReceiver(it) } }
        receiver = null
        main.post { detach() }
    }

    /**
     * 桌面快捷方式入口。快捷方式的跳板用 `setShareIdentityEnabled` 交出身份，这里只受理本模块 uid ——
     * 接收器必须 exported（发送方是另一个 uid），不校验的话任何 App 都能把付款小窗叫出来。
     */
    private fun registerReceiver(ctx: HookContext, context: Context, show: () -> Unit) {
        val moduleUid = runCatching { context.packageManager.getPackageUid(MODULE_PACKAGE, 0) }.getOrDefault(-1)
        if (moduleUid < 0) {
            ctx.log(Log.WARN, "聚合支付：查不到模块 uid，桌面快捷方式入口不装")
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (sentFromUid != moduleUid) {
                    ctx.log(Log.WARN, "聚合支付：拒绝来源 uid=$sentFromUid")
                    return
                }
                show()
            }
        }
        runCatching {
            context.registerReceiver(r, IntentFilter(AggPayment.ACTION_SHOW), null, main, Context.RECEIVER_EXPORTED)
            receiver = r
        }.onFailure { ctx.log(Log.WARN, "聚合支付：注册桌面快捷方式入口失败", it) }
    }

    // ── 呼出 / 切换（主线程） ─────────────────────────────────────────

    /** 打开最近用的那项。@return false = 没有条目，调用方退回面板。 */
    fun open(items: List<PaymentShortcut>): Boolean {
        val c = app ?: return false
        if (items.isEmpty()) return false
        val ordered = ordered(c, items)
        start(ordered, ordered.first(), closeTaskId = null, exact = null)
        return true
    }

    private fun switchTo(item: PaymentShortcut) {
        val c = app ?: return
        val s = session ?: return
        remember(c, item)
        if (item.key == s.current.key) return
        val now = attached
        // 沿用当前小窗的位置与缩放：切换不跳位置
        val exact = now?.let { FanShortcuts.ExactPlacement(Rect(it.bounds), it.scale) }
        switching = now?.let {
            Switch(item.packageName, it.taskId, Rect(it.bounds), it.scale, it.cornerRadius, System.currentTimeMillis() + SWITCH_WINDOW_MS)
        }
        start(ordered(c, s.items), item, closeTaskId = now?.taskId, exact = exact)
    }

    private fun start(items: List<PaymentShortcut>, item: PaymentShortcut, closeTaskId: Int?, exact: FanShortcuts.ExactPlacement?) {
        val ctx = ctxRef ?: return
        val c = app ?: return
        session = Session(items, item, System.currentTimeMillis(), closeTaskId)
        ensureBar(items)
        val level = FanShortcuts.launch(
            ctx, c,
            FanTarget(label = item.label, packageName = item.packageName, icon = item.icon, shortcutId = item.id),
            exact = exact,
        )
        ctx.log(Log.INFO, "聚合支付：打开 ${item.key} → ${FanShortcuts.describeLevel(level)}")
        d("会话开始 ${item.key} closeTask=$closeTaskId exact=${exact?.let { "${it.bounds}@${it.scale}" }}")
        session?.let { refresh(it, "start") }
    }

    // ── 原地快速切换（shell 线程） ────────────────────────────────────

    /**
     * Flyme 的切换是原地换内容。HyperOS 上换条目必然是另一个 Task（另一个 App / 跳板链），
     * 默认会播 MIUI 的小窗打开动画（从小放大）和旧窗关闭动画，看起来是「关一个、弹一个」。
     *
     * 这里接管 `MiuiFreeformModeAnimation.startAnimation`：切换后 [SWITCH_WINDOW_MS] 内，
     * 一次 transition 里**每个** Task 都是这次切换的（新条目的包 / 旧小窗）时，不播动画——
     * 打开的直接摆到旧小窗的位置、缩放、圆角并显示，关闭的直接藏起来，然后结束 transition，
     * 再同步 MIUI 的动画目标（否则之后拖动会按 scale 1.0 算而跳）。任何一项不满足就交还 MIUI。
     */
    private fun installInstantSwitch(ctx: HookContext, track: (XposedInterface.HookHandle) -> Unit): Int {
        val m = runCatching {
            ctx.classLoader.loadClass(FREEFORM_ANIM).declaredMethods.firstOrNull {
                it.name == "startAnimation" && it.parameterTypes.size == 5 &&
                    it.parameterTypes[1].name == "android.window.TransitionInfo"
            }
        }.getOrNull() ?: run {
            ctx.log(Log.WARN, "聚合支付：没找到 $FREEFORM_ANIM#startAnimation，切换会播 MIUI 动画")
            return 0
        }
        m.isAccessible = true
        return runCatching {
            track(ctx.module.hook(m).intercept { chain ->
                val sw = switching
                val handled = if (sw == null) false else try {
                    instant(ctx, sw, chain.args)
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "聚合支付：原地切换处理失败，交还 MIUI 动画", t)
                    false
                }
                if (handled) true else chain.proceed()
            })
            1
        }.getOrElse {
            ctx.log(Log.WARN, "聚合支付：hook startAnimation 失败", it)
            0
        }
    }

    /**
     * @return true = 已处理（不播动画、已结束 transition）。
     * `TransitionInfo` / `Change` 与 Transaction 的部分方法是隐藏 API，一律反射。
     */
    private fun instant(ctx: HookContext, sw: Switch, args: List<Any?>): Boolean {
        if (System.currentTimeMillis() > sw.until) {
            switching = null
            return false
        }
        val info = args.getOrNull(1) ?: return false
        val startT = args.getOrNull(2) as? SurfaceControl.Transaction ?: return false
        val finish = args.getOrNull(4) ?: return false
        @Suppress("UNCHECKED_CAST")
        val changes = (call(info, "getChanges") as? List<Any?>)?.filterNotNull() ?: return false
        if (changes.isEmpty()) return false
        // 每个 change 都得是这次切换的 Task，否则交还 MIUI（别的 App 的小窗动画一律不碰）
        val tasks = changes.map { call(it, "getTaskInfo") as? android.app.ActivityManager.RunningTaskInfo ?: return false }
        for (ti in tasks) {
            val pkg = ti.baseActivity?.packageName ?: ti.topActivity?.packageName
            if (pkg != sw.pkg && ti.taskId != sw.oldTaskId) return false
        }
        val opened = ArrayList<Int>(2)
        for ((i, ch) in changes.withIndex()) {
            val leash = call(ch, "getLeash") as? SurfaceControl ?: return false
            when (call(ch, "getMode") as? Int) {
                TRANSIT_OPEN, TRANSIT_TO_FRONT -> {
                    startT.setPosition(leash, sw.bounds.left.toFloat(), sw.bounds.top.toFloat())
                        .setScale(leash, sw.scale, sw.scale)
                        .setCrop(leash, Rect(0, 0, sw.bounds.width(), sw.bounds.height()))
                        .setAlpha(leash, 1f)
                        .setVisibility(leash, true)
                    if (sw.radius > 0f) {
                        runCatching {
                            startT.javaClass.getMethod("setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType)
                                .invoke(startT, leash, sw.radius / sw.scale)
                        }
                    }
                    opened.add(tasks[i].taskId)
                }
                TRANSIT_CLOSE, TRANSIT_TO_BACK -> startT.setVisibility(leash, false)
                else -> startT.setVisibility(leash, true)
            }
        }
        startT.apply()
        finish.javaClass.methods.first { it.name == "onTransitionFinished" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
            .invoke(finish, null)
        // 同步 MIUI 的动画目标：它本来在打开动画里设，跳过动画就得我们补
        val r = repo
        if (r != null) for (id in opened) {
            runCatching {
                val ti = call(r, "getMiuiFreeformTaskInfo", id) ?: return@runCatching
                val target = call(ti, "getBaseAnimTarget") ?: return@runCatching
                target.javaClass.getMethod(
                    "setBaseAnimTargetParam", Rect::class.java,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                ).invoke(target, Rect(sw.bounds), sw.scale, sw.scale, 0f)
            }
        }
        ctx.log(Log.INFO, "聚合支付：原地切换（不播动画）${tasks.joinToString { it.taskId.toString() }}")
        d("原地切换 thread=${Thread.currentThread().name} changes=" + changes.indices.joinToString {
            "${tasks[it].taskId}:mode=${call(changes[it], "getMode")}"
        } + " 入口条仍挂在 task ${attached?.taskId}")
        return true
    }

    // ── 找小窗（shell 线程或主线程都可能调） ─────────────────────────

    private fun refresh(s: Session, why: String = "poll") {
        val found = runCatching { findTarget(s.pkg) }.getOrDefault(Lookup.Hidden)
        val postedAt = android.os.SystemClock.uptimeMillis()
        main.post { apply(s, found, why, postedAt) }
    }

    /** 找小窗的三种结果：正常显示 / 还在但不是正常小窗（缩成迷你窗、贴边等）/ 这个包没有小窗了。 */
    private sealed interface Lookup {
        class Found(val target: Target) : Lookup
        object Hidden : Lookup
        object Gone : Lookup
    }

    /** 这个包最上层的小窗。 */
    private fun findTarget(pkg: String): Lookup {
        val r = repo ?: return Lookup.Hidden
        @Suppress("UNCHECKED_CAST")
        val ids = (call(r, "getFreeformTasksInZOrder") as? List<Any?>)?.toList() ?: return Lookup.Hidden
        for (id in ids) {
            val taskId = id as? Int ?: continue
            val info = call(r, "getMiuiFreeformTaskInfo", taskId) ?: continue
            if (call(info, "getPackageName") != pkg) continue
            // 缩小成迷你窗 / 贴边时 mode 不是 0：小窗还在，入口条只藏不收，放大回来再显示
            if (diag) d(
                "查到 task $taskId mode=${call(info, "getMode")} bounds=${call(info, "getTaskBounds")} " +
                    "taskScale=${call(info, "getTaskScale")} freeformScale=${call(info, "getFreeformScale")} " +
                    "animating=${call(info, "isInAnimating")} leash=${(call(info, "getLeash") as? SurfaceControl)?.let { System.identityHashCode(it) }}",
            )
            if ((call(info, "getMode") as? Int ?: 0) != 0) return Lookup.Hidden
            val leash = call(info, "getLeash") as? SurfaceControl ?: return Lookup.Hidden
            val bounds = call(info, "getTaskBounds") as? Rect ?: return Lookup.Hidden
            // getFreeformScale 读的是动画目标，进场动画开始前还是 1.0（真机首次挂上时读到 1.0）；
            // getTaskScale 是 MIUI 给这个小窗定的缩放（日志里的 taskScale=0.7）
            val scale = (call(info, "getTaskScale") as? Float)?.takeIf { it > 0f && it < 1f }
                ?: call(info, "getFreeformScale") as? Float ?: return Lookup.Hidden
            if (!leash.isValid || bounds.isEmpty || scale <= 0f) return Lookup.Hidden
            val radius = (call(info, "getCornerRadius") as? Float)?.takeIf { it > 0f } ?: 0f
            val animating = call(info, "isInAnimating") as? Boolean ?: false
            return Lookup.Found(Target(taskId, leash, Rect(bounds), scale, radius, animating))
        }
        return Lookup.Gone
    }

    // ── 入口条（主线程） ──────────────────────────────────────────────

    private fun apply(s: Session, found: Lookup, why: String = "poll", postedAt: Long = 0L) {
        if (session !== s) return
        val ctx = ctxRef ?: return
        val sc = barSc ?: return
        val lag = if (postedAt > 0L) android.os.SystemClock.uptimeMillis() - postedAt else -1L
        val target = when (found) {
            is Lookup.Found -> found.target
            Lookup.Hidden -> {
                d("决定 why=$why 排队=${lag}ms → 藏起（Hidden） 之前显示=$barShown")
                if (barShown) {
                    SurfaceControl.Transaction().setVisibility(sc, false).apply()
                    barShown = false
                }
                return
            }
            Lookup.Gone -> {
                d("决定 why=$why 排队=${lag}ms → 没有该包小窗（Gone） 会话已 ${System.currentTimeMillis() - s.startedAt}ms")
                // 切换途中新小窗还没出来时，入口条留在旧小窗上；超过落地时限仍没有 = 小窗关了
                if (System.currentTimeMillis() - s.startedAt > SETTLE_MS) {
                    ctx.log(Log.INFO, "聚合支付：${s.pkg} 的小窗关了，收起入口条")
                    session = null
                    detach()
                }
                return
            }
        }
        val c = app ?: return
        // 从藏着到显示（刚呼出 / 从迷你窗放大回来）：等 MIUI 的动画播完再淡入，
        // 否则条在放大动画半途突然冒出来，跳变很明显（真机）。动画结束不一定有 task 事件，所以自己轮询
        if (!barShown && target.animating) {
            if (waitSince == 0L) waitSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - waitSince < ANIM_WAIT_MAX_MS) {
                d("决定 why=$why 排队=${lag}ms → 等动画（task ${target.taskId}）")
                main.postDelayed({ session?.takeIf { it === s }?.let { refresh(it) } }, ANIM_POLL_MS)
                return
            }
        }
        waitSince = 0L
        val appearing = !barShown
        // 拖动时 task 事件一串接一串，而 MIUI 每次 update 都 new 一个 leash 对象
        // （MultiTaskingBaseTaskInfo.update：new SurfaceControl(leash, …)）。按对象身份比会以为换了窗，
        // 每次都 reparent + 重设位置，与 MIUI 逐帧移动 leash 不同步 → 入口条一跳一跳（真机）。
        // 改为按图层身份比；几何没变就完全不碰入口条：它是 leash 的子层，拖动本来就跟着走
        val prev = attached
        val sameLeash = prev != null && prev.leash.isValid && sameSurface(prev.leash, target.leash)
        // 只比宽高：条在 leash 局部坐标里的位置与平移无关，拖动时 bounds 的 left/top 变了也不用动
        if (barShown && sameLeash && prev!!.taskId == target.taskId &&
            prev.bounds.width() == target.bounds.width() && prev.bounds.height() == target.bounds.height() &&
            prev.scale == target.scale && prev.cornerRadius == target.cornerRadius && s.closeTaskId == null
        ) {
            d("决定 why=$why 排队=${lag}ms → 不动（几何未变）")
            return
        }
        if (diag) d(
            "决定 why=$why 排队=${lag}ms → 重摆 task ${prev?.taskId}→${target.taskId} sameLeash=$sameLeash " +
                "bounds=${prev?.bounds}→${target.bounds} scale=${prev?.scale}→${target.scale} " +
                "圆角=${prev?.cornerRadius}→${target.cornerRadius} 显示=$barShown 待关=${s.closeTaskId} " +
                "宽 $barWidth→${(target.bounds.width() * target.scale).toInt() - dp(app ?: return, MARGIN_DP) * 2}",
        )
        val margin = dp(c, MARGIN_DP)
        val width = (target.bounds.width() * target.scale).toInt() - margin * 2
        if (width != barWidth && width > 0) {
            host?.relayout(width, dp(c, BAR_HEIGHT_DP))
            barWidth = width
        }
        val radius = (target.cornerRadius - margin).coerceAtLeast(dp(c, 12f).toFloat())
        if (radius != barRadius) {
            barRadius = radius
            barBackground?.cornerRadius = radius
        }

        // leash 的局部坐标是缩放前的逻辑像素：屏上的边距 / 条高都要乘 inv
        val inv = 1f / target.scale
        val t = SurfaceControl.Transaction()
        if (!sameLeash) t.reparent(sc, target.leash)
        t.setLayer(sc, LAYER)
            .setPosition(sc, margin * inv, target.bounds.height() - (dp(c, BAR_HEIGHT_DP) + margin) * inv)
            .setScale(sc, inv, inv)
            .setVisibility(sc, true)
        if (appearing) t.setAlpha(sc, 0f)
        runCatching {
            t.javaClass.getMethod("setTrustedOverlay", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
                .invoke(t, sc, true)
        }
        t.apply()
        barShown = true
        if (appearing) fadeIn(sc)

        if (prev?.taskId != target.taskId) {
            ctx.log(
                Log.INFO,
                "聚合支付：入口条挂到 task ${target.taskId}（bounds=${target.bounds} scale=${target.scale} " +
                    "圆角=${target.cornerRadius}）",
            )
        }
        attached = target

        val close = s.closeTaskId
        if (close != null && close != target.taskId) {
            removeTask(ctx, close)
            session = Session(s.items, s.current, s.startedAt, null)
        }
    }

    private fun fadeIn(sc: SurfaceControl) {
        fade?.cancel()
        fade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener {
                if (sc.isValid) SurfaceControl.Transaction().setAlpha(sc, it.animatedValue as Float).apply()
            }
            start()
        }
    }

    /** 呼出时建 SCVH。会话里切换条目不重建：条一直挂着，顺序等下次呼出再按最近使用排。 */
    private fun ensureBar(items: List<PaymentShortcut>) {
        val c = app ?: return
        val ctx = ctxRef ?: return
        if (host != null) return
        val bar = buildBar(c, items)
        runCatching {
            val display = c.getSystemService(DisplayManager::class.java).getDisplay(0)
            val nh = SurfaceControlViewHost(c, display, null as IBinder?)
            barWidth = c.resources.displayMetrics.widthPixels / 2
            nh.setView(bar, barWidth, dp(c, BAR_HEIGHT_DP))
            host = nh
            barSc = nh.surfacePackage?.surfaceControl
            attached = null
        }.onFailure { ctx.log(Log.WARN, "聚合支付：入口条创建失败", it) }
    }

    private fun detach() {
        fade?.cancel()
        fade = null
        waitSince = 0L
        val sc = barSc
        if (sc != null && sc.isValid) {
            runCatching { SurfaceControl.Transaction().reparent(sc, null).apply() }
        }
        runCatching { host?.release() }
        host = null
        barSc = null
        barBackground = null
        attached = null
        barWidth = 0
        barRadius = 0f
        barShown = false
    }

    private fun buildBar(c: Context, items: List<PaymentShortcut>): View {
        val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = GradientDrawable().apply {
            cornerRadius = if (barRadius > 0f) barRadius else dp(c, 18f).toFloat()
            setColor(if (dark) Color.rgb(0x2B, 0x2B, 0x2E) else Color.WHITE)
        }
        barBackground = bg
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
        }
        for (item in items) {
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { switchTo(item) }
            }
            cell.addView(
                ImageView(c).apply { setImageDrawable(item.icon) },
                LinearLayout.LayoutParams(dp(c, ICON_DP), dp(c, ICON_DP)),
            )
            cell.addView(
                TextView(c).apply {
                    text = LABELS[item.key] ?: item.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (dark) Color.rgb(0xDD, 0xDD, 0xDD) else Color.rgb(0x33, 0x33, 0x33))
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(c, 4f) },
            )
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    // ── 最近用的那项 ──────────────────────────────────────────────────

    private fun ordered(c: Context, items: List<PaymentShortcut>): List<PaymentShortcut> {
        val order = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ORDER, null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        return items.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun remember(c: Context, item: PaymentShortcut) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val order = prefs.getString(PREF_ORDER, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        prefs.edit().putString(PREF_ORDER, (listOf(item.key) + (order - item.key)).joinToString("\n")).apply()
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    /** `SurfaceControl.isSameSurface`（隐藏 API）：两个对象是不是同一个图层。取不到就退回对象身份。 */
    private fun sameSurface(a: SurfaceControl, b: SurfaceControl): Boolean = runCatching {
        SurfaceControl::class.java.getMethod("isSameSurface", SurfaceControl::class.java).invoke(a, b) as Boolean
    }.getOrDefault(a === b)

    private fun removeTask(ctx: HookContext, taskId: Int) {
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
            ctx.log(Log.INFO, "聚合支付：切换后关掉旧小窗 task $taskId")
        }.onFailure { ctx.log(Log.WARN, "聚合支付：关旧小窗 $taskId 失败", it) }
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

    private fun dp(c: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
}
