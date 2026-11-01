package io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion

import android.app.BroadcastOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContextSuggestion
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.ContextCapsuleCompat
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import java.util.UUID

internal object ScreenOnSuggestion : Feature(Catalog.require(Catalog.CONTEXT_SCREEN_ON)) {
    const val ACTION_DIM_TOUCH = "io.github.kotoriinsky.tsuzuri.action.CONTEXT_DIM_TOUCH"
    const val ACTION_SCREEN_STATUS = "io.github.kotoriinsky.tsuzuri.action.CONTEXT_SCREEN_STATUS"
    const val ACTION_SCREEN_STOP = "io.github.kotoriinsky.tsuzuri.action.CONTEXT_SCREEN_STOP"
    const val EXTRA_ACTIVE = "active"
    private val main = Handler(Looper.getMainLooper())
    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile
    private var bridge: ContextCapsuleCompat? = null
    private var hook: HookContext? = null
    private var cue: String? = null
    @Volatile private var source: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == "com.xiaomi.aicr:cognitionService"

    override fun install(ctx: HookContext): InstallReport {
        if (installedHooks > 0) return InstallReport(installedHooks)
        val compat = runCatching { ContextCapsuleCompat.create(ctx.classLoader) }.getOrElse {
            return InstallReport(0, notes = listOf("小爱版本或符号不兼容"))
        }
        bridge = compat
        hook = ctx
        val systemUiUid = runCatching { compat.context.packageManager.getApplicationInfo("com.android.systemui", 0).uid }.getOrDefault(-1)
        monitorThread = HandlerThread("TsuzuriScreenMonitor").apply { start() }
        monitorHandler = Handler(monitorThread!!.looper)
        track(ctx.module.hook(compat.clickMethod).intercept { chain ->
            val id = chain.args.firstOrNull() as? String
            if (id == null || id != cue) return@intercept chain.proceed()
            main.post { runCatching { activate(id) }.onFailure { hook?.log(Log.WARN, "常亮操作失败：${it.javaClass.simpleName}") } }
            null
        })
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        ACTION_DIM_TOUCH -> if (sentFromUid == 1000) suggest()
                        ACTION_SCREEN_STOP -> if (ContextSuggestion.acceptScreenOnStop(sentFromUid, systemUiUid, wakeLock?.isHeld == true)) release()
                        Intent.ACTION_SCREEN_OFF -> release()
                    }
                }.onFailure { ctx.log(Log.WARN, "常亮事件处理失败：${it.javaClass.simpleName}") }
            }
        }
        runCatching {
            compat.context.registerReceiver(receiver, IntentFilter().apply {
                addAction(ACTION_DIM_TOUCH)
                addAction(ACTION_SCREEN_STOP)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, "android.permission.DUMP", main, Context.RECEIVER_EXPORTED)
        }.getOrElse {
            uninstall()
            return InstallReport(0, notes = listOf("常亮事件接收器注册失败：${it.javaClass.simpleName}"))
        }
        return InstallReport(installedHooks, notes = listOf("常亮胶囊接收器=1"))
    }

    private fun suggest() {
        val compat = bridge ?: return
        if (wakeLock?.isHeld == true) return
        val host = compat.context
        val power = host.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        val foreground = SystemUiEnv.foregroundPackage() ?: return
        val home = host.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        if (foreground == home || foreground in setOf("com.android.systemui", "com.xiaomi.subscreencenter")) return
        cue?.let { runCatching { compat.dismiss(it) } }
        val id = "${ContextSuggestion.CUE_PREFIX}${ContextSuggestion.SCREEN_ON}.${UUID.randomUUID()}"
        cue = id
        source = foreground
        runCatching { compat.show(id, "临时开启屏幕常亮", foreground, null, ScreenOnStatus.ICON_DRAWABLE) }.onFailure {
            cue = null
            source = null
            hook?.log(Log.WARN, "常亮胶囊展示失败：${it.javaClass.simpleName}")
        }
        main.postDelayed({ if (cue == id) { cue = null; source = null } }, 12_000)
    }

    private fun activate(id: String) {
        val compat = bridge ?: return
        val expected = source ?: return
        if (cue != id) return
        val host = compat.context
        val power = host.getSystemService(Context.POWER_SERVICE) as PowerManager
        val locked = (host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (!ContextSuggestion.keepScreenOn(expected, SystemUiEnv.foregroundPackage(), power.isInteractive, locked)) return
        runCatching {
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Tsuzuri:TemporaryScreenOn")
            lock.acquire()
            wakeLock = lock
            runCatching { compat.dismiss(id) }
            cue = null
            status(true)
            monitorHandler?.post(monitor)
        }.onFailure { hook?.log(Log.WARN, "临时常亮启用失败：${it.javaClass.simpleName}") }
    }

    private val monitor = object : Runnable {
        override fun run() {
            val expected = source
            val keep = runCatching {
                val compat = bridge ?: return@runCatching false
                if (expected == null) return@runCatching false
                val power = compat.context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val locked = (compat.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                ContextSuggestion.keepScreenOn(expected, SystemUiEnv.foregroundPackage(), power.isInteractive, locked)
            }.getOrDefault(false)
            if (keep) monitorHandler?.postDelayed(this, 500)
            else main.post { if (source == expected) release() }
        }
    }

    private fun status(active: Boolean) {
        val context = bridge?.context ?: return
        runCatching {
            context.sendBroadcast(Intent(ACTION_SCREEN_STATUS).setPackage("com.android.systemui").putExtra(EXTRA_ACTIVE, active),
                null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }.onFailure { hook?.log(Log.WARN, "常亮状态发送失败：${it.javaClass.simpleName}") }
    }

    private fun release() {
        monitorHandler?.removeCallbacks(monitor)
        cue?.let { runCatching { bridge?.dismiss(it) } }
        cue = null
        source = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        status(false)
    }

    override fun uninstall() {
        main.removeCallbacksAndMessages(null)
        release()
        receiver?.let { runCatching { bridge?.context?.unregisterReceiver(it) } }
        receiver = null
        monitorThread?.quitSafely()
        monitorThread = null
        monitorHandler = null
        bridge = null
        hook = null
        super.uninstall()
    }
}
