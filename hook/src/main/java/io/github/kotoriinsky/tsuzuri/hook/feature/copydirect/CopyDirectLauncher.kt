package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanShortcuts

/** 胶囊和岛使用相同的打开档位；分享及系统选择器仍按系统方式显示。 */
internal object CopyDirectLauncher {
    fun launch(hook: HookContext, context: Context, target: Intent, shareOnly: Boolean) {
        val preset = HookRegistry.prefs().rawString(Keys.pref(Catalog.PASSCODE_ISLAND, "window_preset"), "flyme")
            ?.takeIf { it in setOf("system", "flyme", "fullscreen") } ?: "flyme"
        val resolved = target.resolveActivity(context.packageManager)
        val packageName = target.`package` ?: resolved?.packageName
        val resolver = packageName == null || packageName in setOf("android", "com.android.intentresolver") ||
            resolved?.className?.contains("ResolverActivity") == true || target.action == Intent.ACTION_CHOOSER
        if (!shareOnly && !resolver && preset != "fullscreen") {
            val options = listOfNotNull(FanShortcuts.miuiOptions(context, packageName!!),
                FanShortcuts.publicOptions(context, withBounds = false))
            for (option in options) {
                SystemUiEnv.forceLaunchNewTask(hook, option)
                SystemUiEnv.applyPasscodePlacement(hook, context, option, preset)
                runCatching { context.startActivity(Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), option.toBundle()) }
                    .onSuccess { hook.log(Log.INFO, "复制直达胶囊打开 requested=freeform preset=$preset package=$packageName"); return }
                    .onFailure { hook.log(Log.WARN, "复制直达小窗降级：${it.javaClass.simpleName}") }
            }
        }
        context.startActivity(Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hook.log(Log.INFO, "复制直达胶囊打开 requested=fullscreen share=$shareOnly package=$packageName")
    }
}
