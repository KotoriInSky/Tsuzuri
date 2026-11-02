package io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment

import android.app.ActivityManager
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
 * 聚合支付的 Flyme 形态：**呼出即用小窗打开最近用的那项，小窗下方挂一条入口条，与小窗是一块**。
 *
 * ## Flyme 怎么做的（docs/101）
 *
 * 入口条是一个独立窗口，system_server 把它的 surface reparent 到小窗 task 的 leash 上
 * （`FlymeWindowModeManager.HandlerWindowState.postWindowToLeash`），于是进场、拖动、关闭
 * 动画里两者共享一个变换。
 *
 * ## 这里怎么做
 *
 * HyperOS 的小窗由 WM Shell 管，WM Shell 就跑在 SystemUI 进程里，task leash 在本进程可得：
 *
 *  1. hook `MultiTaskingTaskRepository` 的 task 出现 / 变化 / 消失，拿到仓库实例；
 *  2. 当前要显示的包的**最上层小窗** → `MiuiFreeformModeTaskInfo` 的 leash、逻辑 bounds、缩放；
 *  3. 入口条用 `SurfaceControlViewHost` 画，把它的 surface 挂成 leash 的子层，摆在小窗底边下方，
 *     `setScale(1/scale)` 抵消 leash 的缩放（屏上尺寸固定）。
 *
 * 子层不受 leash 的 windowCrop 裁剪：MIUI 自己的小窗阴影（`MiuiDecorationShadow`）就是 task leash
 * 的子层，且画在 task 范围外（真机 SF：阴影可见区 [-268,14,1486,2478]，task 只到 [122,404]-[1098,2090]）。
 *
 * 跳板（支付宝 1002 会再起两个 Task）的位置与清理由 system_server 侧的
 * `FreeformTrampolinePlacement` 负责；这里只认「这个包最上层的那个小窗」。
 *
 * ## 「最近用的那项」
 *
 * 与 Flyme 一致：呼出打开第 0 项；**只有在入口条上点了某项才把它挪到最前**。
 */
internal object PaymentWindow {

    private const val TAG = "FP-AggPay"

    private const val REPO = "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository"

    private const val MODULE_PACKAGE = "io.github.kotoriinsky.tsuzuri"

    private const val PREFS = "tsuzuri_agg_payment"
    private const val PREF_ORDER = "order"

    /** 跳板链在这段时间内还没落地时，找不到小窗不算结束。 */
    private const val SETTLE_MS = 3_000L

    private const val BAR_HEIGHT_DP = 64f
    private const val GAP_DP = 10f
    private const val ICON_DP = 34f
    private const val LAYER = 1_000_000

    /** Flyme 的四个标签（SystemUITools `wechat_money` / `wechat_scan` / `alipay_pay` / `alipay_scan`）：
     *  支付宝 1002 自己发布的短标签也是「收付款」，与微信那项并排时分不出来。 */
    private val LABELS = mapOf(
        "com.tencent.mm/launch_type_offline_wallet" to "收付款",
        "com.tencent.mm/launch_type_scan_qrcode" to "扫一扫",
        "com.eg.android.AlipayGphone/1002" to "付款码",
        "com.eg.android.AlipayGphone/1001" to "扫一扫",
    )

    /** 与 MIUI 自己那几条（index 0/1）错开。 */
    private const val INSETS_INDEX = 7

    private val main = Handler(Looper.getMainLooper())

    private class Target(
        val taskId: Int,
        /** `WindowContainerToken`，加 / 撤让位 insets 用。 */
        val token: Any?,
        val leash: SurfaceControl,
        val bounds: Rect,
        val scale: Float,
    )

    private class Session(
        val items: List<PaymentShortcut>,
        val pkg: String,
        val startedAt: Long,
        /** 切换条目时要关掉的旧小窗；新小窗出现后才关。 */
        val closeTaskId: Int?,
    )

    @Volatile
    private var session: Session? = null

    @Volatile
    private var repo: Any? = null

    private var ctxRef: HookContext? = null
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null

    // 以下只在主线程读写
    private var host: SurfaceControlViewHost? = null
    private var barSc: SurfaceControl? = null
    private var attachedLeash: SurfaceControl? = null
    private var attachedTaskId = -1
    private var attachedToken: Any? = null

    /** 让位 insets 的身份：同一个 owner + index 才能撤掉自己加的那一条。 */
    private val insetsOwner = android.os.Binder()
    private var barWidth = 0

    // ── 安装 ──────────────────────────────────────────────────────────

    /** @return 挂上的 hook 数。缺仓库类时返回 0：小窗照样能开，只是没有入口条。 */
    fun install(ctx: HookContext, context: Context, track: (XposedInterface.HookHandle) -> Unit, show: () -> Unit): Int {
        ctxRef = ctx
        app = context
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
                        val s = session
                        val info = chain.args.firstOrNull() as? ActivityManager.RunningTaskInfo
                        if (s != null && info != null && packageOf(info) == s.pkg) refresh(s)
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "聚合支付：处理 $name 失败", t)
                    }
                    r
                })
                hooked++
            }.onFailure { ctx.log(Log.WARN, "聚合支付：hook $name 失败", it) }
        }
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

    // ── 呼出 / 切换 ───────────────────────────────────────────────────

    /** 主线程。打开最近用的那项。@return false = 没有条目，调用方退回面板。 */
    fun open(items: List<PaymentShortcut>): Boolean {
        val c = app ?: return false
        if (items.isEmpty()) return false
        val ordered = ordered(c, items)
        start(ordered, ordered.first(), closeTaskId = null)
        return true
    }

    private fun switchTo(item: PaymentShortcut) {
        val c = app ?: return
        val s = session ?: return
        remember(c, item)
        val prev = attachedTaskId.takeIf { it >= 0 }
        start(ordered(c, s.items), item, closeTaskId = prev)
    }

    private fun start(items: List<PaymentShortcut>, item: PaymentShortcut, closeTaskId: Int?) {
        val ctx = ctxRef ?: return
        val c = app ?: return
        session = Session(items, item.packageName, System.currentTimeMillis(), closeTaskId)
        rebuildBar(items)
        val level = FanShortcuts.launch(
            ctx, c,
            FanTarget(label = item.label, packageName = item.packageName, icon = item.icon, shortcutId = item.id),
        )
        ctx.log(Log.INFO, "聚合支付：打开 ${item.key} → ${FanShortcuts.describeLevel(level)}")
        session?.let { refresh(it) }
    }

    // ── 找小窗（shell 线程或主线程都可能调） ─────────────────────────

    private fun refresh(s: Session) {
        val target = runCatching { findTarget(s.pkg) }.getOrNull()
        main.post { apply(s, target) }
    }

    /** 这个包最上层、正常显示（mode 0）的小窗。 */
    private fun findTarget(pkg: String): Target? {
        val r = repo ?: return null
        @Suppress("UNCHECKED_CAST")
        val ids = (call(r, "getFreeformTasksInZOrder") as? List<Any?>)?.toList() ?: return null
        for (id in ids) {
            val taskId = id as? Int ?: continue
            val info = call(r, "getMiuiFreeformTaskInfo", taskId) ?: continue
            if (call(info, "getPackageName") != pkg) continue
            if ((call(info, "getMode") as? Int ?: 0) != 0) return null
            val leash = call(info, "getLeash") as? SurfaceControl ?: return null
            val bounds = call(info, "getTaskBounds") as? Rect ?: return null
            // getFreeformScale 读的是动画目标，进场动画开始前还是 1.0（真机首次挂上时读到 1.0）；
            // getTaskScale 是 MIUI 给这个小窗定的缩放（日志里的 taskScale=0.7）
            val scale = (call(info, "getTaskScale") as? Float)?.takeIf { it > 0f && it < 1f }
                ?: call(info, "getFreeformScale") as? Float ?: return null
            if (!leash.isValid || bounds.isEmpty || scale <= 0f) return null
            val token = (call(info, "getTaskInfo") as? ActivityManager.RunningTaskInfo)?.let { ti ->
                runCatching { ti.javaClass.getField("token").get(ti) }.getOrNull()
            }
            return Target(taskId, token, leash, Rect(bounds), scale)
        }
        return null
    }

    // ── 入口条（主线程） ──────────────────────────────────────────────

    private fun apply(s: Session, target: Target?) {
        if (session !== s) return
        val ctx = ctxRef ?: return
        if (target == null) {
            if (System.currentTimeMillis() - s.startedAt > SETTLE_MS) {
                ctx.log(Log.INFO, "聚合支付：${s.pkg} 的小窗没了，收起入口条")
                session = null
                detach()
            }
            return
        }
        val c = app ?: return
        val sc = barSc ?: return
        val dm = c.resources.displayMetrics
        val width = (target.bounds.width() * target.scale).toInt()
        if (width != barWidth) {
            host?.relayout(width, dp(c, BAR_HEIGHT_DP))
            barWidth = width
        }
        val t = SurfaceControl.Transaction()
        if (attachedLeash !== target.leash) {
            t.reparent(sc, target.leash)
            attachedLeash = target.leash
        }
        val inv = 1f / target.scale
        t.setLayer(sc, LAYER)
            // 放在小窗底边以内：task leash 的 windowCrop 会裁掉范围外的子层
            // （真机 SF：摆在底边外时该层 geomLayerBounds 为空）。内容区由 [reserve] 的 insets 让出来
            .setPosition(sc, 0f, target.bounds.height() - (dp(c, BAR_HEIGHT_DP) + dp(c, GAP_DP)) * inv)
            .setScale(sc, inv, inv)
            .setVisibility(sc, true)
        runCatching { t.javaClass.getMethod("setTrustedOverlay", SurfaceControl::class.java, Boolean::class.javaPrimitiveType).invoke(t, sc, true) }
        t.apply()

        if (attachedTaskId != target.taskId) {
            reserve(ctx, attachedToken, add = false, 0)
            reserve(ctx, target.token, add = true, ((dp(c, BAR_HEIGHT_DP) + dp(c, GAP_DP)) * inv).toInt())
            attachedToken = target.token
            ctx.log(
                Log.INFO,
                "聚合支付：入口条挂到 task ${target.taskId}（bounds=${target.bounds} scale=${target.scale} " +
                    "屏宽=${width}px density=${dm.density}）",
            )
            attachedTaskId = target.taskId
        }
        val close = s.closeTaskId
        if (close != null && close != target.taskId) {
            removeTask(ctx, close)
            session = Session(s.items, s.pkg, s.startedAt, null)
        }
    }

    private fun rebuildBar(items: List<PaymentShortcut>) {
        val c = app ?: return
        val ctx = ctxRef ?: return
        val bar = buildBar(c, items)
        val h = host
        if (h != null) {
            h.setView(bar, barWidth.coerceAtLeast(1), dp(c, BAR_HEIGHT_DP))
            return
        }
        runCatching {
            val display = c.getSystemService(DisplayManager::class.java).getDisplay(0)
            val nh = SurfaceControlViewHost(c, display, null as IBinder?)
            barWidth = c.resources.displayMetrics.widthPixels / 2
            nh.setView(bar, barWidth, dp(c, BAR_HEIGHT_DP))
            host = nh
            barSc = nh.surfacePackage?.surfaceControl
            attachedLeash = null
            attachedTaskId = -1
        }.onFailure { ctx.log(Log.WARN, "聚合支付：入口条创建失败", it) }
    }

    private fun detach() {
        ctxRef?.let { reserve(it, attachedToken, add = false, 0) }
        attachedToken = null
        val sc = barSc
        if (sc != null && sc.isValid) {
            runCatching { SurfaceControl.Transaction().reparent(sc, null).apply() }
        }
        runCatching { host?.release() }
        host = null
        barSc = null
        attachedLeash = null
        attachedTaskId = -1
        barWidth = 0
    }

    private fun buildBar(c: Context, items: List<PaymentShortcut>): View {
        val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 24f).toFloat()
                setColor(if (dark) Color.rgb(0x2B, 0x2B, 0x2E) else Color.WHITE)
            }
        }
        for (item in items) {
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { switchTo(item) }
            }
            cell.addView(ImageView(c).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(dp(c, ICON_DP), dp(c, ICON_DP)))
            cell.addView(TextView(c).apply {
                text = LABELS[item.key] ?: item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (dark) Color.rgb(0xDD, 0xDD, 0xDD) else Color.rgb(0x33, 0x33, 0x33))
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(c, 4f)
            })
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

    // ── 让位 ──────────────────────────────────────────────────────────

    /**
     * 在小窗 task 底部声明一条 captionBar insets，App 的内容排在入口条上方 ——
     * 与 MIUI 自己的底部小横条同一个做法（`MiuiDecorationBase.DecorationInsets`：
     * `addInsetsSource(token, owner, index, captionBar(), Insets.of(0,0,0,h), …)`）。
     * [height] 是 task 逻辑像素（未乘 freeformScale）。
     */
    private fun reserve(ctx: HookContext, token: Any?, add: Boolean, height: Int) {
        if (token == null) return
        runCatching {
            val wctCls = Class.forName("android.window.WindowContainerTransaction")
            val wct = wctCls.getConstructor().newInstance()
            val type = android.view.WindowInsets.Type.captionBar()
            if (add) {
                val m = wctCls.methods.first {
                    it.name == "addInsetsSource" && it.parameterTypes.size == 7 &&
                        it.parameterTypes[4] == android.graphics.Insets::class.java
                }
                m.invoke(wct, token, insetsOwner, INSETS_INDEX, type, android.graphics.Insets.of(0, 0, 0, height), null, 0)
            } else {
                val m = wctCls.methods.first { it.name == "removeInsetsSource" && it.parameterTypes.size == 4 }
                m.invoke(wct, token, insetsOwner, INSETS_INDEX, type)
            }
            val organizer = Class.forName("android.window.WindowOrganizer").getConstructor().newInstance()
            organizer.javaClass.getMethod("applyTransaction", wctCls).invoke(organizer, wct)
        }.onFailure { ctx.log(Log.WARN, "聚合支付：${if (add) "加" else "撤"}让位 insets 失败", it) }
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun packageOf(info: ActivityManager.RunningTaskInfo): String? =
        info.baseActivity?.packageName ?: info.topActivity?.packageName

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
