package io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion

import android.app.BroadcastOptions
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.DimTouchCompat

internal object DimTouchObserver : Feature(Catalog.require(Catalog.CONTEXT_SCREEN_ON)) {
    private var thread: HandlerThread? = null
    override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

    override fun install(ctx: HookContext): InstallReport {
        if (installedHooks > 0) return InstallReport(installedHooks)
        val compat = runCatching { DimTouchCompat.create(ctx.classLoader) }.getOrElse {
            return InstallReport(0, notes = listOf("电源接口不兼容：${it.javaClass.simpleName}"))
        }
        val worker = HandlerThread("TsuzuriDimTouch").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        track(ctx.module.hook(compat.activity).intercept { chain ->
            val match = runCatching { compat.isDimTouch(chain.args[0], chain.args[2]) }.getOrDefault(false)
            val result = chain.proceed()
            if (match && result == true) {
                val instance = chain.thisObject
                if (instance != null) handler.post {
                    runCatching {
                        val intent = Intent(ScreenOnSuggestion.ACTION_DIM_TOUCH).setPackage("com.xiaomi.aicr")
                        compat.context(instance).sendBroadcast(intent, null,
                            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                    }.onFailure { ctx.log(Log.WARN, "常亮建议事件发送失败：${it.javaClass.simpleName}") }
                }
            }
            result
        })
        return InstallReport(installedHooks, notes = listOf("主屏变暗触摸入口"))
    }

    override fun uninstall() {
        super.uninstall()
        thread?.quitSafely()
        thread = null
    }
}
