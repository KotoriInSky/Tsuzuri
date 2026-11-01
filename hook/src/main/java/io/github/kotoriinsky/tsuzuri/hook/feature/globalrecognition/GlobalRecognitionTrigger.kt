package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.app.ActivityManager
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.GlobalRecognition
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv

/**
 * 全局识别的 SystemUI 一半：受理磁贴、收起控制中心，再叫醒传送门。
 *
 * 为什么要经 SystemUI 转一道：磁贴是在展开的控制中心里点的，直接截图截到的就是控制中心；
 * 收起面板要 `StatusBarManager.collapsePanels()`，只有 SystemUI 这类持有 `EXPAND_STATUS_BAR` 的进程能调。
 *
 * 为什么发给传送门的 `ContentCatcherReceiver`：传送门进程平时不常驻（2026-10-27 真机 `ps` 查不到），
 * 运行时注册的接收器在进程没起来时收不到。这个接收器是清单里 exported、不带权限的，
 * 显式广播会把进程拉起来；宿主收到没有 `content` 的广播什么都不做，所以特性关着时这条广播无副作用。
 * 传送门一侧（[GlobalRecognitionHost]）核对发送方 uid 是 SystemUI 才受理。
 */
internal object GlobalRecognitionTrigger : Feature(Catalog.require(Catalog.GLOBAL_RECOGNITION)) {

    private const val TAG = "FP-GlobalRecog"
    private const val MODULE_PACKAGE = "io.github.kotoriinsky.tsuzuri"
    const val RECEIVER_CLS = "com.miui.contentextension.text.ContentCatcherReceiver"

    override val installId: String get() = "global_recognition:systemui"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == ScopeTarget.SYSTEM_UI.packageName

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var hookCtx: HookContext? = null

    private val wake = Runnable {
        val context = SystemUiEnv.context() ?: return@Runnable
        try {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            val pkg = topPackage(context)
            context.sendBroadcast(
                Intent(GlobalRecognition.ACTION_CAPTURE)
                    .setComponent(ComponentName(ScopeTarget.CONTENT_EXTENSION.packageName, RECEIVER_CLS))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .apply { if (pkg != null) putExtra(GlobalRecognition.EXTRA_PACKAGE, pkg) },
                null,
                options,
            )
            hookCtx?.log(Log.INFO, "[$TAG] 已叫醒传送门截屏 前台=$pkg")
        } catch (t: Throwable) {
            hookCtx?.log(Log.WARN, "[$TAG] 叫醒传送门失败", t)
        }
    }

    override fun install(ctx: HookContext): InstallReport {
        val context = SystemUiEnv.context() ?: return InstallReport(0, notes = listOf("no_context"))
        hookCtx = ctx
        val moduleUid = runCatching { context.packageManager.getPackageUid(MODULE_PACKAGE, 0) }.getOrDefault(-1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    val sender = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
                    if (moduleUid < 0 || sender != moduleUid) {
                        ctx.log(Log.WARN, "[$TAG] 拒绝来源 uid=$sender")
                        return
                    }
                    if (!HookRegistry.prefs().isEnabled(key)) return
                    collapse(c)
                    main.removeCallbacks(wake)
                    main.postDelayed(wake, GlobalRecognition.COLLAPSE_WAIT_MS)
                } catch (t: Throwable) {
                    // SystemUI 里逃出去的异常 = 黑屏
                    ctx.log(Log.WARN, "[$TAG] 处理磁贴请求失败", t)
                }
            }
        }
        return try {
            context.registerReceiver(r, IntentFilter(GlobalRecognition.ACTION_TILE), null, main, Context.RECEIVER_EXPORTED)
            receiver = r
            ctx.log(Log.INFO, "[$TAG] SystemUI 侧已挂上：moduleUid=$moduleUid")
            InstallReport(hooked = 1)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[$TAG] 注册磁贴接收器失败", t)
            InstallReport(0, listOf("receiver ${GlobalRecognition.ACTION_TILE}"))
        }
    }

    /**
     * 主屏上可见的最上层任务的包名。SystemUI 持有 REAL_GET_TASKS，能看到所有应用的任务；
     * 传送门只有 GET_TASKS 看不到，所以由这里查好带过去。背屏（displayId≠0）的任务跳过 ——
     * 那台副屏常年有可见任务，按顺序拿会拿错（同类问题见 §10.1 的控件树抢答）。
     */
    private fun topPackage(context: Context): String? = try {
        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        am.getRunningTasks(8).firstOrNull { t -> t.isVisible && displayOf(t) == 0 }?.topActivity?.packageName
    } catch (t: Throwable) {
        hookCtx?.log(Log.WARN, "[$TAG] 查前台应用失败", t)
        null
    }

    /** `TaskInfo.displayId` 是隐藏字段（SystemUI 可读）；读不到就当主屏。 */
    private fun displayOf(task: ActivityManager.RunningTaskInfo): Int =
        runCatching { android.app.TaskInfo::class.java.getField("displayId").getInt(task) }.getOrDefault(0)

    /** 收起控制中心 / 通知栏；失败只记日志，照样截（最坏截到面板，用户关掉重来）。 */
    private fun collapse(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar") ?: return
            sbm.javaClass.getMethod("collapsePanels").invoke(sbm)
        } catch (t: Throwable) {
            hookCtx?.log(Log.WARN, "[$TAG] 收起控制中心失败", t)
        }
    }

    override fun uninstall() {
        main.removeCallbacks(wake)
        runCatching { receiver?.let { SystemUiEnv.context()?.unregisterReceiver(it) } }
        receiver = null
        hookCtx = null
        super.uninstall()
    }
}
