package io.github.kotoriinsky.tsuzuri.hook.feature.cleanstatusbar

import android.app.BroadcastOptions
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.CleanStatusBar
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface

/**
 * 完美状态栏的**截图**那一半（system_server 侧）。
 *
 * ## 为什么在 system_server
 *
 * 截图由 `com.miui.screenshot/.TakeScreenshotService` 执行（`cmd overlay lookup android
 * android:string/config_screenshotServiceComponent` 实测），它不在作用域里；而请求一律从 system_server 的
 * `com.android.internal.util.ScreenshotHelper` 发出 —— 组合键、手势、三指下滑（`ShortCutActionsUtils`、
 * `BaseMiuiPhoneWindowManager`）的所有重载最后都汇到 `takeScreenshotInternal(request, handler, consumer, timeoutMs)`。
 *
 * ## 做法
 *
 * 截图请求到达 → 通知 SystemUI 先进入完美状态栏（[CleanStatusBar.REASON_SCREENSHOT]）→
 * [CleanStatusBar.SCREENSHOT_DELAY_MS] 后用原实现（Invoker ORIGIN，跳过本 hook）把请求放行；
 * SystemUI 在 [CleanStatusBar.SCREENSHOT_HOLD_MS] 后退出自己为截图开的那次演示模式；
 * 已经在演示模式里（快捷开关 / 录屏开的）时 SystemUI 不动，只是这边照样等 0.3 秒（system_server 不知道演示状态）。
 *
 * 代价写在设置文案里：截图会晚约 0.3 秒，屏幕上能看到状态栏先变一下。
 * 控制中心里的截图按钮如果走 SystemUI 自己的截图入口，不经过这里（待真机确认）。
 */
internal object CleanStatusBarScreenshot : Feature(Catalog.require(Catalog.CLEAN_STATUSBAR)) {

    private const val HELPER = "com.android.internal.util.ScreenshotHelper"
    private const val SYSTEM_UI = "com.android.systemui"

    override val installId: String get() = "clean_statusbar:screenshot"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

    override fun wanted(prefs: Prefs): Boolean = prefs.isEnabled(key) && autoOn(prefs)

    private fun autoOn(prefs: Prefs) = prefs.rawBoolean(Keys.pref(key, CleanStatusBar.AUTO_SCREENSHOT), true)

    override fun install(ctx: HookContext): InstallReport {
        val m = runCatching {
            ctx.classLoader.loadClass(HELPER).declaredMethods.firstOrNull {
                it.name == "takeScreenshotInternal" && it.parameterTypes.size == 4
            }
        }.getOrNull() ?: return InstallReport(0, missing = listOf("$HELPER#takeScreenshotInternal/4"))
        m.isAccessible = true
        val original = ctx.module.getInvoker(m).setType(XposedInterface.Invoker.Type.ORIGIN)

        track(ctx.module.hook(m).intercept { chain ->
            val go = try {
                val prefs = HookRegistry.prefs()
                prefs.isEnabled(key) && autoOn(prefs)
            } catch (_: Throwable) {
                false
            }
            if (!go) return@intercept chain.proceed()
            val receiver = chain.thisObject
            val args = chain.args.toTypedArray()
            val context = runCatching {
                receiver?.javaClass?.getDeclaredField("mContext")?.apply { isAccessible = true }?.get(receiver) as? android.content.Context
            }.getOrNull() ?: return@intercept chain.proceed()
            val sent = runCatching {
                val i = Intent(CleanStatusBar.ACTION).setPackage(SYSTEM_UI)
                    .putExtra(CleanStatusBar.EXTRA_REASON, CleanStatusBar.REASON_SCREENSHOT)
                    .putExtra(CleanStatusBar.EXTRA_ON, true)
                context.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            }.isSuccess
            if (!sent) return@intercept chain.proceed()
            // 放行的线程：请求方给的 Handler（截图结果的回调也投递在它上面），没有就用主线程
            val handler = (args.getOrNull(1) as? Handler) ?: Handler(Looper.getMainLooper())
            handler.postDelayed({
                runCatching { original.invoke(receiver, *args) }.onFailure {
                    ctx.log(Log.WARN, "完美状态栏：延迟放行截图失败", it)
                }
            }, CleanStatusBar.SCREENSHOT_DELAY_MS)
            ctx.log(Log.INFO, "完美状态栏：截图请求延迟 ${CleanStatusBar.SCREENSHOT_DELAY_MS}ms 放行")
            null
        })
        ctx.log(Log.INFO, "完美状态栏截图：hooked=$installedHooks（$HELPER#takeScreenshotInternal）")
        return InstallReport(installedHooks)
    }
}
