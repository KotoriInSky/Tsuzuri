package io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContextSuggestion
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.ContextCapsuleCompat
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 每个场景独立安装、卸载；只处理自己生成的胶囊。 */
class AppSuggestion(private val scene: String) : Feature(Catalog.require(scene)) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Intent>()
    private var receiver: BroadcastReceiver? = null
    private var audioCallback: AudioDeviceCallback? = null
    private var bridge: ContextCapsuleCompat? = null
    private var hook: HookContext? = null
    private val prefix get() = "${ContextSuggestion.CUE_PREFIX}$scene."

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == "com.xiaomi.aicr:cognitionService"

    override fun install(ctx: HookContext): InstallReport {
        if (installedHooks > 0) return InstallReport(installedHooks)
        val compat = runCatching { ContextCapsuleCompat.create(ctx.classLoader) }.getOrElse {
            ctx.log(Log.WARN, "场景建议兼容检查失败：${it.javaClass.simpleName}")
            return InstallReport(0, notes = listOf("小爱版本或符号不兼容"))
        }
        bridge = compat
        hook = ctx
        track(ctx.module.hook(compat.foregroundMethod).intercept { chain ->
            val accepted = chain.proceed()
            if (accepted == true || !runCatching { compat.id(chain.args.firstOrNull())?.startsWith(prefix) == true }.getOrDefault(false)) return@intercept accepted
            runCatching {
                val host = compat.context
                val locked = (host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                val home = host.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
                if (!locked && home != null && SystemUiEnv.foregroundPackage() == home) true else accepted
            }.getOrDefault(accepted)
        })
        track(ctx.module.hook(compat.clickMethod).intercept { chain ->
            val id = chain.args.firstOrNull() as? String ?: return@intercept chain.proceed()
            val target = pending.remove(id) ?: return@intercept chain.proceed()
            runCatching {
                compat.dismiss(id)
                compat.context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { ctx.log(Log.WARN, "场景建议打开失败：${it.javaClass.simpleName}") }
            null
        })
        if (scene == ContextSuggestion.INSTALL) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                        main.post { dismissPackage(pkg) }
                        return
                    }
                    if (intent.action != Intent.ACTION_PACKAGE_ADDED || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    main.postDelayed({ runCatching { suggest(pkg) }.onFailure {
                        ctx.log(Log.WARN, "新应用建议处理失败：${it.javaClass.simpleName}")
                    } }, 1500)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            runCatching { compat.context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }.getOrElse {
                uninstall()
                return InstallReport(0, notes = listOf("安装事件接收器注册失败：${it.javaClass.simpleName}"))
            }
        }
        if (scene == ContextSuggestion.MUSIC) {
            val constructor = runCatching { compat.musicConstructor() }.getOrElse {
                uninstall()
                return InstallReport(0, notes = listOf("音乐事件入口缺失"))
            }
            track(ctx.module.hook(compat.longClickMethod).intercept { chain ->
                val id = runCatching { compat.longClickCueId(chain.thisObject) }.getOrNull()
                if (id == null || !id.startsWith(prefix)) return@intercept chain.proceed()
                runCatching {
                    compat.dismiss(id)
                    pending.remove(id)
                    showListenPicker(compat.context)
                }.onFailure { ctx.log(Log.WARN, "音乐建议列表打开失败：${it.javaClass.simpleName}") }
                true
            })
            track(ctx.module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val event = compat.musicEvent(chain.thisObject) ?: return@runCatching
                    main.post { runCatching {
                        if (!event.first) return@runCatching dismissAll()
                        val pm = compat.context.packageManager
                        val pkg = ContextSuggestion.pickListenApp(listenSessions(compat.context), event.second) {
                            pm.getLaunchIntentForPackage(it) != null
                        }
                        ctx.log(Log.INFO, "音乐建议候选 candidate=${pkg != null} whitelisted=${pkg in ContextSuggestion.LISTEN_APPS}")
                        if (pkg != null && pending.values.none { it.`package` == pkg }) suggest(pkg)
                    }.onFailure { ctx.log(Log.WARN, "音乐建议处理失败：${it.javaClass.simpleName}") } }
                }.onFailure { ctx.log(Log.WARN, "音乐建议事件读取失败：${it.javaClass.simpleName}") }
                result
            })
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    val count = addedDevices.count { it.type in HEADSET_TYPES }
                    if (count > 0) ctx.log(Log.INFO, "音乐建议音频设备 added=$count")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    val count = removedDevices.count { it.type in HEADSET_TYPES }
                    if (count > 0) ctx.log(Log.INFO, "音乐建议音频设备 removed=$count")
                }
            }
            runCatching {
                (compat.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(callback, main)
                audioCallback = callback
            }.onFailure { ctx.log(Log.WARN, "音乐建议设备观察失败：${it.javaClass.simpleName}") }
        }
        ctx.log(Log.INFO, "场景建议接入 scene=$scene hooked=$installedHooks receiver=${receiver != null}")
        return InstallReport(installedHooks, notes = listOf("aicr=4.12.26"))
    }

    private fun suggest(pkg: String) {
        val compat = bridge ?: return
        val host = compat.context
        if (!(host.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive ||
            (host.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        if (scene == ContextSuggestion.MUSIC && (host.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive) return
        val foreground = SystemUiEnv.foregroundPackage() ?: return
        if (foreground == pkg || foreground == "com.android.systemui") return
        val launch = host.packageManager.getLaunchIntentForPackage(pkg)
        if (!ContextSuggestion.shouldSuggestInstall(false, launch != null)) return
        val app = runCatching { host.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        val title = ContextSuggestion.openTitle(host.packageManager.getApplicationLabel(app).toString())
        val id = "$prefix${UUID.randomUUID()}"
        dismissAll()
        pending[id] = launch!!
        runCatching { compat.show(id, title, foreground, pkg) }.onFailure {
            pending.remove(id)
            hook?.log(Log.WARN, "场景建议展示失败：${it.javaClass.simpleName}")
        }
        main.postDelayed({ pending.remove(id) }, 12_000)
    }

    /** 长按胶囊：列出已安装的白名单应用，点哪个开哪个。「打开方式」框拉不起时退回应用悬浮层列表。 */
    private fun showListenPicker(host: Context) {
        val pm = host.packageManager
        val apps = ContextSuggestion.LISTEN_APPS.mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            Triple(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), launch)
        }
        if (apps.isEmpty()) return
        if (apps.size < 2) {
            // 只有一个时「打开方式」框也会直接打开它，不如明着打开。
            runCatching { host.startActivity(apps[0].third.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        // 系统「打开方式」框；候选由 IntentResolver 侧的 ListenPickerFilter 筛成白名单。
        val resolver = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(ContextSuggestion.EXTRA_LISTEN_PICK, true)
            .setComponent(ComponentName(RESOLVER_PKG, "$RESOLVER_PKG.ResolverActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { host.startActivity(resolver) }.isSuccess) {
            hook?.log(Log.INFO, "音乐建议打开方式 apps=${apps.size}")
            return
        }
        val themed = ContextThemeWrapper(host, android.R.style.Theme_DeviceDefault_DayNight)
        val size = (32 * host.resources.displayMetrics.density).toInt()
        val pad = (16 * host.resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<String>(themed, android.R.layout.simple_list_item_1, apps.map { it.first }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    val icon = apps[position].second.mutate().apply { setBounds(0, 0, size, size) }
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = pad
                }
        }
        val dialog = AlertDialog.Builder(themed)
            .setTitle(ContextSuggestion.LISTEN_PICKER_TITLE)
            .setAdapter(adapter) { _, which ->
                runCatching { host.startActivity(apps[which].third.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { hook?.log(Log.WARN, "场景建议打开失败：${it.javaClass.simpleName}") }
            }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        hook?.log(Log.INFO, "音乐建议列表 apps=${apps.size}")
    }

    /** 与小爱同样的取法：正在播放或暂停的会话包名，按系统给出的顺序。 */
    private fun listenSessions(host: Context): List<String> = runCatching {
        val sessions = host.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(host, NotificationListenerService::class.java))
        sessions.filter { it.playbackState?.state in LISTEN_STATES }.map { it.packageName }
    }.getOrDefault(emptyList())

    private fun dismissPackage(pkg: String) {
        pending.entries.filter { it.value.`package` == pkg }.forEach { (id, _) ->
            runCatching { bridge?.dismiss(id) }
            pending.remove(id)
        }
    }

    private fun dismissAll() {
        pending.keys.toList().forEach { runCatching { bridge?.dismiss(it) } }
        pending.clear()
    }

    override fun uninstall() {
        main.removeCallbacksAndMessages(null)
        dismissAll()
        receiver?.let { runCatching { bridge?.context?.unregisterReceiver(it) } }
        audioCallback?.let { callback -> runCatching {
            (bridge?.context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(callback)
        } }
        audioCallback = null
        receiver = null
        bridge = null
        hook = null
        super.uninstall()
    }

    companion object {
        private const val RESOLVER_PKG = "com.android.intentresolver"
        private val LISTEN_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)
        private val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
