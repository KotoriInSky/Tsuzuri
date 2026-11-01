package io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland

import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.libxposed.api.XposedInterface

/**
 * 口令岛「打开」按钮的长按：弹系统「选择打开方式」（用户 2026-10-22 定）。
 *
 * 岛的按钮是官方模板的 `textButton`，只能带一个点击动作，没有长按 —— 所以 hook 画按钮的
 * `ModuleTextButton4ViewHolder.bind(Template, StatusBarNotification)`，绑定后给「打开」那枚
 * （两枚按钮时是 `action2`）挂长按监听。只认我们自己发的岛（SystemUI 包 + [PasscodeIslandPresenter] 记录的 id），
 * 别人的焦点通知一律把长按清掉，不改它们的行为。
 *
 * 这个类在 MIUISystemUIPlugin 里，插件是运行中动态加载的，SystemUI 主 classpath 没有。
 * 插件的类加载器由 `PluginInstance$PluginFactory.createClassLoader()` 创建，并缓存在
 * `com.miui.systemui.plugin.PluginInstanceInjector.sClassLoaders`（按插件包名）——
 * 安装时先查缓存（热重载时插件早已加载），查不到再 hook 创建方法等它出现。
 *
 * 任何一步失败都只是「没有长按」：点击「打开」不受影响。
 */
internal object PasscodeIslandLongPress {

    private const val TAG = "FP-Passcode"
    private const val PLUGIN_PKG = "miui.systemui.plugin"
    private const val INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"
    private const val FACTORY = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
    private const val HOLDER = "miui.systemui.notification.focus.moduleV3.ModuleTextButton4ViewHolder"

    private val handles = ArrayList<XposedInterface.HookHandle>(2)
    private var holderHooked = false
    private var hookContext: HookContext? = null

    fun install(ctx: HookContext) {
        hookContext = ctx
        pluginLoaderFromCache(ctx.classLoader)?.let { hookHolder(ctx, it); return }
        try {
            val m = Class.forName(FACTORY, false, ctx.classLoader).getDeclaredMethod("createClassLoader")
            ctx.module.hook(m).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (!holderHooked) pluginLoaderFromCache(ctx.classLoader)?.let { hookHolder(ctx, it) }
                } catch (_: Throwable) {
                }
                result
            }.also { handles.add(it) }
            ctx.log(Log.INFO, "口令岛长按：插件尚未加载，等它创建类加载器")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "口令岛长按：找不到插件加载入口，不启用长按", t)
        }
    }

    fun uninstall() {
        for (h in handles) runCatching { h.unhook() }
        handles.clear()
        holderHooked = false
        hookContext = null
    }

    private fun pluginLoaderFromCache(host: ClassLoader): ClassLoader? = try {
        val map = Class.forName(INJECTOR, false, host).getDeclaredField("sClassLoaders")
            .apply { isAccessible = true }.get(null) as? Map<*, *>
        map?.get(PLUGIN_PKG) as? ClassLoader
    } catch (_: Throwable) {
        null
    }

    private fun hookHolder(ctx: HookContext, plugin: ClassLoader) {
        if (holderHooked) return
        try {
            val cls = Class.forName(HOLDER, false, plugin)
            val bind = cls.declaredMethods.first {
                it.name == "bind" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == StatusBarNotification::class.java
            }
            val action2 = cls.getDeclaredField("action2").apply { isAccessible = true }
            ctx.module.hook(bind).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = action2.get(chain.thisObject) as? View
                    val sbn = chain.getArg(1) as? StatusBarNotification
                    if (view != null) attach(view, sbn)
                } catch (_: Throwable) {
                    // SystemUI 的绑定路径上不允许异常逃逸
                }
                result
            }.also { handles.add(it) }
            holderHooked = true
            ctx.log(Log.INFO, "口令岛长按已装上：$HOLDER#bind")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "口令岛长按：按钮类签名对不上，不启用长按", t)
        }
    }

    private fun attach(view: View, sbn: StatusBarNotification?) {
        val id = sbn?.id
        val ours = sbn != null && id != null && sbn.packageName == "com.android.systemui" &&
            PasscodeIslandPresenter.ownsIsland(id) && enabled()
        if (!ours) {
            // ViewHolder 会被别的焦点通知复用：不是我们的岛就撤掉，免得长按串到别人的按钮上
            view.setOnLongClickListener(null)
            view.isLongClickable = false
            return
        }
        view.setOnLongClickListener { v ->
            val opened = PasscodeIslandPresenter.openWithChooser(v.context, id!!)
            if (opened) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            opened
        }
    }

    private fun enabled(): Boolean =
        hookContext?.prefs?.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "long_press_chooser"), true) ?: false
}
