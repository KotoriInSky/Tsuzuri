package io.github.kotoriinsky.tsuzuri.hook.compat

import android.graphics.Rect
import android.os.Bundle
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract

/** 当前 ROM 的网页图片结果只带 WebView 容器范围，不带 DOM 图片边界。 */
object PortalImageSelectionCompat {
    fun webImage(bundle: Bundle): Boolean =
        bundle.getString(PortalMediaContract.CAPTURE_ORIGIN) == "WebViewCatcher" || bundle.containsKey("imgUrl")

    /** 原文件和底图仍完整保留；仅让选框与截图备选采用宿主的触点范围。 */
    fun target(bundle: Bundle): Rect? {
        if (webImage(bundle)) {
            bundle.remove("view_rect")
            return null
        }
        return bundle.getParcelable("view_rect", Rect::class.java)?.let(::Rect)
    }
}
