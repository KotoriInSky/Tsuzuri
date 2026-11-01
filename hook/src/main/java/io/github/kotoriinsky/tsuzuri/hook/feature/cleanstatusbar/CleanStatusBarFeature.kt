package io.github.kotoriinsky.tsuzuri.hook.feature.cleanstatusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.CleanStatusBar
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv

/**
 * 截图 / 录屏完美状态栏（SystemUI 侧）。
 *
 * 三件事：
 *  1. **演示模式期间藏通知图标**：hook `DemoModeController.enterDemoMode/exitDemoMode` 得知进出，
 *     用模块独占的 Binder token 调 `IStatusBarService.disable(0x20000, token, pkg)`；
 *     退出 / 关闭 / 卸载时只把**这个 token** 的记录写回 0，不碰锁屏、锁定任务等其他来源。
 *  2. **录屏自动进入**：hook SystemUI 自己的 MediaProjection 回调（`MediaProjectionManagerRepository`
 *     里那个匿名 `Callback` 的 `onStart/onStop`）—— 任何走 MediaProjection 的录屏都会经过；
 *     只有「是模块自动开的」演示模式，录屏结束时才退出。
 *  3. **快捷开关**：界面侧 TileService 发 [CleanStatusBar.ACTION]，这里按发送方 uid 校验是本模块才受理。
 *
 * 进出演示模式都走 SystemUI 自己的 `com.android.systemui.demo` 广播（与 `adb shell am broadcast` 同一入口）；
 * `sysui_demo_allowed=0` 时系统不受理，模块不替用户改这个全局设置。
 */
internal object CleanStatusBarFeature : Feature(Catalog.require(Catalog.CLEAN_STATUSBAR)) {

    private const val DEMO_CONTROLLER = "com.android.systemui.demomode.DemoModeController"
    private const val PROJECTION_CALLBACK =
        "com.android.systemui.mediaprojection.data.repository.MediaProjectionManagerRepository\$callbackEventsFlow\$1\$callback\$1"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val MODULE_PACKAGE = "io.github.kotoriinsky.tsuzuri"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == PasscodeIsland.SYSTEM_UI_PACKAGE

    private const val OWNER_TILE = "tile"
    private const val OWNER_RECORDING = "recording"
    private const val OWNER_SCREENSHOT = "screenshot"

    private val main = Handler(Looper.getMainLooper())
    private val token: IBinder = Binder()

    @Volatile private var inDemo = false
    /**
     * 当前这次演示模式是谁开的：`null` = 不是模块开的（用户在系统设置里开的），
     * 否则是 [OWNER_TILE] / [OWNER_RECORDING] / [OWNER_SCREENSHOT]。录屏结束、截图计时到点
     * 都只退出**自己**开的那次，不替用户关掉别人开的演示模式。
     */
    @Volatile private var owner: String? = null
    @Volatile private var iconsDisabled = false
    /** 被我们设成 INVISIBLE 的超级岛窗口根视图（只恢复这些，不碰别人设的可见性）。 */
    private val hiddenIslands = java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.view.View, Boolean>())
    private var receiver: BroadcastReceiver? = null
    /** 子项在演示模式开着时被切换 → 立刻重算（手感参数要改完即生效）。 */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && k.startsWith("${Catalog.CLEAN_STATUSBAR}.")) main.post { refreshIcons() }
    }
    private var hookCtx: HookContext? = null

    override fun install(ctx: HookContext): InstallReport {
        val context = SystemUiEnv.context() ?: return InstallReport(0, notes = listOf("no_context"))
        hookCtx = ctx
        val missing = ArrayList<String>()

        // ① 演示模式进出
        val demo = runCatching { ctx.classLoader.loadClass(DEMO_CONTROLLER) }.getOrNull()
        for (name in listOf("enterDemoMode", "exitDemoMode")) {
            val m = demo?.declaredMethods?.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            if (m == null) { missing.add("$DEMO_CONTROLLER#$name"); continue }
            track(ctx.module.hook(m).intercept { chain ->
                val r = chain.proceed()
                inDemo = name == "enterDemoMode"
                if (!inDemo) owner = null
                main.post { refreshIcons() }
                r
            })
        }

        // ② 录屏开始 / 结束
        val cb = runCatching { ctx.classLoader.loadClass(PROJECTION_CALLBACK) }.getOrNull()
        for (name in listOf("onStart", "onStop")) {
            val m = cb?.declaredMethods?.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
            if (m == null) { missing.add("$PROJECTION_CALLBACK#$name"); continue }
            track(ctx.module.hook(m).intercept { chain ->
                val r = chain.proceed()
                val pkg = runCatching { chain.args[0]?.javaClass?.getMethod("getPackageName")?.invoke(chain.args[0]) as? String }.getOrNull()
                main.post { onProjection(context, name == "onStart", pkg) }
                r
            })
        }

        // ④ 超级岛窗口：完美状态栏期间新建的岛窗口也要立刻藏起来
        val wmg = runCatching { Class.forName("android.view.WindowManagerGlobal") }.getOrNull()
        val addView = wmg?.declaredMethods?.firstOrNull { it.name == "addView" && it.parameterTypes.size >= 2 }
        if (addView == null) missing.add("android.view.WindowManagerGlobal#addView") else {
            track(ctx.module.hook(addView).intercept { chain ->
                val r = chain.proceed()
                try {
                    val view = chain.args[0] as? android.view.View
                    val lp = chain.args[1] as? android.view.WindowManager.LayoutParams
                    if (view != null && lp?.title?.toString() == CleanStatusBar.ISLAND_WINDOW_TITLE) main.post { refreshIsland() }
                } catch (_: Throwable) {
                }
                r
            })
        }

        // ③ 快捷开关
        registerTileReceiver(context)
        runCatching { ctx.store?.registerOnSharedPreferenceChangeListener(prefListener) }

        // SystemUI 起来时演示模式可能已经开着（系统设置里的「演示模式」开关）
        inDemo = runCatching { Settings.Global.getInt(context.contentResolver, "sysui_tuner_demo_on", 0) == 1 }.getOrDefault(false)
        main.post { refreshIcons() }

        ctx.log(Log.INFO, "完美状态栏：hooked=$installedHooks receiver=${receiver != null} inDemo=$inDemo missing=$missing")
        return InstallReport(installedHooks, missing = missing)
    }

    private fun sub(name: String): Boolean = try {
        val prefs = HookRegistry.prefs()
        prefs.isEnabled(key) && prefs.rawBoolean(Keys.pref(key, name), true)
    } catch (_: Throwable) {
        false
    }

    private fun onProjection(context: Context, started: Boolean, pkg: String?) {
        if (!sub(CleanStatusBar.AUTO_RECORDING)) return
        hookCtx?.log(Log.INFO, "完美状态栏：录屏${if (started) "开始" else "结束"} pkg=$pkg inDemo=$inDemo owner=$owner")
        if (started) enter(context, OWNER_RECORDING) else exitIfOwner(context, OWNER_RECORDING)
    }

    /** 不在演示模式时进入，并记下是谁开的；已经在了就什么都不做（不抢别人的）。 */
    private fun enter(context: Context, who: String) {
        if (inDemo || owner != null) return
        sendDemo(context, CleanStatusBar.enterCommands)
        owner = who
    }

    private fun exitIfOwner(context: Context, who: String) {
        if (owner != who) return
        sendDemo(context, CleanStatusBar.exitCommands)
        owner = null
    }

    private fun sendDemo(context: Context, commands: List<Map<String, String>>) {
        for (c in commands) runCatching {
            val i = Intent(CleanStatusBar.DEMO_ACTION).setPackage(SYSTEM_UI)
            c.forEach { (k, v) -> i.putExtra(k, v) }
            context.sendBroadcast(i)
        }
    }

    /** 演示模式开着 + 子项开着 → 写本模块的 disable 记录；否则把它清回 0（只在写过时才清）。 */
    private fun refreshIcons() {
        refreshIsland()
        val want = inDemo && sub(CleanStatusBar.HIDE_NOTIFICATION_ICONS)
        if (want == iconsDisabled) return
        if (setDisable(if (want) CleanStatusBar.DISABLE_NOTIFICATION_ICONS else 0)) iconsDisabled = want
    }

    /** 演示模式开着 + 子项开着 → 藏起超级岛窗口；否则只把我们藏的那些恢复。 */
    private fun refreshIsland() {
        val want = inDemo && sub(CleanStatusBar.HIDE_ISLAND)
        if (!want) {
            for (v in hiddenIslands.toList()) runCatching { v.visibility = android.view.View.VISIBLE }
            hiddenIslands.clear()
            return
        }
        try {
            val g = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val views = g.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(g) as List<*>
            val params = g.javaClass.getDeclaredField("mParams").apply { isAccessible = true }.get(g) as List<*>
            for (i in views.indices) {
                val v = views[i] as? android.view.View ?: continue
                val lp = params.getOrNull(i) as? android.view.WindowManager.LayoutParams ?: continue
                if (lp.title?.toString() != CleanStatusBar.ISLAND_WINDOW_TITLE) continue
                if (v.visibility == android.view.View.VISIBLE) {
                    v.visibility = android.view.View.INVISIBLE
                    hiddenIslands.add(v)
                }
            }
        } catch (t: Throwable) {
            hookCtx?.log(Log.WARN, "完美状态栏：藏超级岛失败", t)
        }
    }

    private fun setDisable(what: Int): Boolean = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, Context.STATUS_BAR_SERVICE) as IBinder
        val service = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        service.javaClass.getMethod("disable", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(service, what, token, SYSTEM_UI)
        hookCtx?.log(Log.INFO, "完美状态栏：通知图标 disable=0x${Integer.toHexString(what)}")
        true
    } catch (t: Throwable) {
        hookCtx?.log(Log.WARN, "完美状态栏：写 disable 失败", t)
        false
    }

    private fun registerTileReceiver(context: Context) {
        val moduleUid = runCatching { context.packageManager.getPackageUid(MODULE_PACKAGE, 0) }.getOrDefault(-1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    // 只受理本模块（快捷开关）与 system_server（截图前）发来的；
                    // 两边都用 BroadcastOptions.setShareIdentityEnabled 交出身份
                    val sender = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
                    val fromSystem = sender == android.os.Process.SYSTEM_UID
                    if (!fromSystem && (moduleUid < 0 || sender != moduleUid)) {
                        hookCtx?.log(Log.WARN, "完美状态栏：拒绝来源 uid=$sender")
                        return
                    }
                    if (!HookRegistry.prefs().isEnabled(key)) return
                    val reason = intent.getStringExtra(CleanStatusBar.EXTRA_REASON)
                    hookCtx?.log(Log.INFO, "完美状态栏：收到请求 uid=$sender reason=$reason on=${intent.getBooleanExtra(CleanStatusBar.EXTRA_ON, false)} inDemo=$inDemo owner=$owner")
                    when {
                        fromSystem && reason == CleanStatusBar.REASON_SCREENSHOT -> {
                            if (!sub(CleanStatusBar.AUTO_SCREENSHOT)) return
                            enter(c, OWNER_SCREENSHOT)
                            main.postDelayed({ exitIfOwner(c, OWNER_SCREENSHOT) }, CleanStatusBar.SCREENSHOT_HOLD_MS)
                        }
                        fromSystem -> return
                        intent.getBooleanExtra(CleanStatusBar.EXTRA_ON, false) -> enter(c, OWNER_TILE)
                        // 快捷开关关 = 用户明确要退出，不管是谁开的
                        inDemo -> { sendDemo(c, CleanStatusBar.exitCommands); owner = null }
                    }
                } catch (t: Throwable) {
                    // SystemUI 里逃出去的异常 = 黑屏
                    hookCtx?.log(Log.WARN, "完美状态栏：处理快捷开关失败", t)
                }
            }
        }
        runCatching {
            context.registerReceiver(r, IntentFilter(CleanStatusBar.ACTION), null, main, Context.RECEIVER_EXPORTED)
            receiver = r
        }.onFailure { hookCtx?.log(Log.WARN, "完美状态栏：注册快捷开关接收器失败", it) }
    }

    override fun uninstall() {
        val context = SystemUiEnv.context()
        runCatching { receiver?.let { context?.unregisterReceiver(it) } }
        runCatching { hookCtx?.store?.unregisterOnSharedPreferenceChangeListener(prefListener) }
        receiver = null
        // 关态必须与未安装一致：清掉本模块的 disable 记录，退出模块自己开的演示模式
        if (iconsDisabled) { setDisable(0); iconsDisabled = false }
        for (v in hiddenIslands.toList()) runCatching { v.visibility = android.view.View.VISIBLE }
        hiddenIslands.clear()
        if (owner != null && context != null) { sendDemo(context, CleanStatusBar.exitCommands); owner = null }
        super.uninstall()
        hookCtx = null
    }
}
