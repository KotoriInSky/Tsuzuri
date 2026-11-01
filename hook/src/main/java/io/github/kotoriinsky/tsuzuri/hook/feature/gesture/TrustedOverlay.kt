package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

import android.util.Log
import android.view.WindowManager

/**
 * 把 SystemUI 里「只画、不吃触摸」的悬浮窗标成**受信任的悬浮窗**（`PRIVATE_FLAG_TRUSTED_OVERLAY`）。
 *
 * ## 为什么要
 *
 * Android 12 起，`FLAG_NOT_TOUCHABLE` 的普通悬浮窗（`TYPE_APPLICATION_OVERLAY`）会被系统把
 * `alpha` 压到「最大遮挡不透明度」0.8（防止透明窗口盖在别的应用上骗点击）。真机实测
 * （2026-10-29，Xiaomi 17 Pro）：扇形小窗的窗口 `dumpsys window` 里是 `alpha=0.8`，
 * 而我们从没设过 —— 用户看到的「图标不透明度拉到 100% 还是有点透」就是它，
 * 整张图标只有 80%，桌面图标的轮廓能透过来。侧滑索引的面板与把手是同一种窗口。
 *
 * 受信任的悬浮窗不受这条限制。它需要 `INTERNAL_SYSTEM_WINDOW` 权限，SystemUI 本来就有。
 *
 * ## 怎么标
 *
 * `LayoutParams.setTrustedOverlay()` 是隐藏接口；SystemUI 是平台签名，不受隐藏接口限制。
 * 反射失败（ROM 改了 / 方法不在）就什么都不做：窗口照常建，只是回到 0.8 的老样子。
 */
internal object TrustedOverlay {

    private const val TAG = "FP-TrustedOverlay"

    @Volatile
    private var warned = false

    /** 标记成功返回 true。只改 [lp]，addView 之前调。 */
    fun mark(lp: WindowManager.LayoutParams): Boolean = try {
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(lp)
        true
    } catch (t: Throwable) {
        if (!warned) {
            warned = true
            Log.w(TAG, "标记受信任悬浮窗失败，窗口不透明度会被系统压到 0.8", t)
        }
        false
    }
}
