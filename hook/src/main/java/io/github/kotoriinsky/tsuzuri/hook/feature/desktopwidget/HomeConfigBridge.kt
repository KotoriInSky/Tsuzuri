package io.github.kotoriinsky.tsuzuri.hook.feature.desktopwidget

import android.content.SharedPreferences
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.DesktopWidget
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport

/**
 * 桌面（`com.miui.home`）原生补丁的 `system_server` 配置同步桥。
 *
 * ## 为什么放在 `system_server` 而不是桌面进程
 *
 * `com.miui.home` 是无 ART 的 Dart/Rust 进程，进程内只有纯 C 的 `libfpnative.so`，
 * 拿不到 `XposedInterface` 或 `RemotePreferences`。
 * `system_server` 已由本模块注入且持有 `RemotePreferences` 监听，且开机时序早于
 * `com.miui.home` 启动；在这里把三个桌面小部件开关同步成 3-bit 掩码写入系统属性
 * （`debug.tsuzuri.home.flags` / `sys.tsuzuri.home.flags`），无需 root、热切换秒级生效，
 * 且不写 `persist.*`，唯一事实来源始终是 LSPosed `RemotePreferences`。
 *
 * 不 hook 任何系统类，零侵入；热重载时由 [uninstall] 注销监听，避免重复回调。
 */
object HomeConfigBridge {

    private const val TAG = "FP-HomeCfg"

    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var boundStore: SharedPreferences? = null

    fun install(ctx: HookContext): InstallReport {
        val initialFlags = DesktopWidget.computeFlags(ctx.prefs)
        val published = publish(ctx, initialFlags, reason = "init")

        val store = ctx.store
        if (store != null && listener == null) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (!DesktopWidget.isDesktopWidgetPrefKey(key)) return@OnSharedPreferenceChangeListener
                // 直接向框架重取快照，避免 RemotePreferences 本地缓存延迟或与 dirtyListener 顺序竞争
                val freshStore = runCatching { ctx.module.getRemotePreferences(Keys.GROUP) }.getOrNull() ?: store
                val freshPrefs = Prefs(SpAdapter(freshStore))
                val flags = DesktopWidget.computeFlags(freshPrefs)
                publish(ctx, flags, reason = "change:$key")
            }
            runCatching {
                store.registerOnSharedPreferenceChangeListener(l)
                listener = l
                boundStore = store
            }.onFailure {
                ctx.log(Log.WARN, "$TAG: 注册配置监听失败", it)
            }
        }

        return InstallReport(
            hooked = if (published > 0) published else 1,
            notes = listOf(
                "flags=$initialFlags",
                "props_ok=$published",
                "listening=${listener != null}",
            ),
        )
    }

    fun uninstall() {
        val s = boundStore
        val l = listener
        if (s != null && l != null) {
            runCatching { s.unregisterOnSharedPreferenceChangeListener(l) }
        }
        listener = null
        boundStore = null
    }

    private fun publish(ctx: HookContext, flags: Int, reason: String): Int {
        val value = DesktopWidget.formatFlags(flags)
        var ok = 0
        val setMethod = runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java)
        }.getOrNull()

        if (setMethod == null) {
            ctx.log(Log.WARN, "$TAG: 找不到 SystemProperties.set，无法同步桌面开关 flags=$value")
            return 0
        }

        for (prop in listOf(DesktopWidget.PROP_FLAGS_DEBUG, DesktopWidget.PROP_FLAGS_SYS)) {
            runCatching {
                setMethod.invoke(null, prop, value)
                ok++
            }.onFailure {
                ctx.log(Log.WARN, "$TAG: 写属性失败 $prop=$value (${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName})")
            }
        }
        ctx.log(Log.INFO, "$TAG: 同步桌面配置 reason=$reason flags=$value props_ok=$ok")
        return ok
    }

    private class SpAdapter(private val sp: SharedPreferences) : io.github.kotoriinsky.tsuzuri.core.KeyValueStore {
        override fun contains(key: String): Boolean = runCatching { sp.contains(key) }.getOrDefault(false)
        override fun getBoolean(key: String, defValue: Boolean): Boolean = runCatching { sp.getBoolean(key, defValue) }.getOrDefault(defValue)
        override fun getInt(key: String, defValue: Int): Int = runCatching { sp.getInt(key, defValue) }.getOrDefault(defValue)
        override fun getFloat(key: String, defValue: Float): Float = runCatching { sp.getFloat(key, defValue) }.getOrDefault(defValue)
        override fun getString(key: String, defValue: String?): String? = runCatching { sp.getString(key, defValue) }.getOrDefault(defValue)
        override fun getStringSet(key: String, defValue: Set<String>): Set<String> =
            runCatching { sp.getStringSet(key, defValue) ?: defValue }.getOrDefault(defValue)
    }
}
