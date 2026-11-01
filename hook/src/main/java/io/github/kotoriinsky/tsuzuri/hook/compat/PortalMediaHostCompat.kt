package io.github.kotoriinsky.tsuzuri.hook.compat

import android.graphics.Bitmap
import android.content.Context
import android.graphics.Rect
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import java.lang.reflect.Method
import io.github.kotoriinsky.tsuzuri.core.PortalImageGeometry
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract

/** 所有厂商私有签名集中在此；安装失败时保持宿主功能。 */
class PortalMediaHostCompat(loader: ClassLoader, server: Boolean) {
    private var supportedVersion: Boolean? = null
    fun supports(context: Context): Boolean = supportedVersion ?: runCatching {
        // 本次截图与展示时序仅在 5.1.5 / 50015 取证；上下限一致，升级后重新核验。
        context.packageManager.getPackageInfo("com.miui.contentextension", 0).longVersionCode in 50015L..50015L
    }.getOrDefault(false).also { supportedVersion = it }
    data class Page(val pkg: String, val version: Int, val activity: String)
    private val token = Class.forName("miui.contentcatcher.sdk.Token", false, loader)
    private val pkg = token.getMethod("getPkgName")
    private val version = token.getMethod("getVersionCode")
    private val activity = token.getMethod("getActivityName")
    val pageConfig: Method? = if (server) Class.forName("com.miui.contentcatcher.ContentCatcherService", false, loader)
        .getDeclaredMethod("getPageConfig", token).apply { isAccessible = true } else null
    private val result = if (!server) Class.forName("miui.contentcatcher.sdk.ClientCatcherResult", false, loader) else null
    private val getParams = result?.getMethod("getParams", String::class.java)
    private val resultToken = result?.getMethod("getInjectorToken")
    private val content = if (server) Class.forName("miui.contentcatcher.sdk.Content", false, loader) else null
    val contentEvent: Method? = if (server) Class.forName("com.miui.contentcatcher.ContentCatcherService", false, loader)
        .getDeclaredMethod("onContentCatched", content).apply { isAccessible = true } else null
    private val contentToken = content?.getMethod("getInjectorToken")
    private val contentResults = content?.getField("mResultMap")
    private val contentCatcher = content?.let { runCatching { it.getField("mCatcherToken") }.getOrNull() }
    private val service = if (!server) Class.forName("com.miui.contentextension.services.TextContentExtensionService", false, loader) else null
    private val card = if (!server) Class.forName("com.miui.contentextension.text.cardview.TaplusRecognitionExpandedImageCard", false, loader) else null
    val pickImage: Method? = service?.getDeclaredMethod("onPickImage", result)?.apply { isAccessible = true }
    val pickText: Method? = service?.getDeclaredMethod("onPickText", result)?.apply { isAccessible = true }
    val cancel: Method? = service?.getMethod("cancelTask")
    val refresh: Method? = card?.getDeclaredMethod("refresh")?.apply { isAccessible = true }
    val save: Method? = card?.getDeclaredMethod("doSaveImage", View::class.java)?.apply { isAccessible = true }
    private val setBitmap = service?.getMethod("setBitmap", Bitmap::class.java)
    private val getBitmap = service?.getMethod("getBitmap")
    val show: Method? = service?.getMethod("showMainFloatView", Boolean::class.javaPrimitiveType)
    private val floatView = if (!server) Class.forName("com.miui.contentextension.text.floatview.TaplusMainFloatView", false, loader) else null
    val prepare: Method? = floatView?.getDeclaredMethod("prepareScreen")?.apply { isAccessible = true }
    val focus: Method? = floatView?.getDeclaredMethod("focusScreenCaptureView")?.apply { isAccessible = true }
    private val screenshot = floatView?.getDeclaredField("mScreenShot")?.apply { isAccessible = true }
    private val picked = floatView?.getDeclaredField("mPickedRect")?.apply { isAccessible = true }
    private val fullScreenshot = if (!server) Class.forName("com.miui.contentextension.utils.DisplayCaptureUtils", false, loader)
        .getMethod("getFullScreenshot", Context::class.java) else null
    private val firstTouch = service?.getMethod("getFirstTouchPoint")
    private val expanded = if (!server) Class.forName("com.miui.contentextension.text.cardview.TaplusRecognitionExpandedCard", false, loader) else null
    val selectionRefresh: Method? = expanded?.getMethod("doRefreshImage", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    fun page(value: Any) = Page(pkg.invoke(value) as String, version.invoke(value) as Int, activity.invoke(value) as String)
    fun imageBundle(value: Any): Bundle? = (getParams?.invoke(value, "image_pick") as? Map<*, *>)?.get("image_pick_mode") as? Bundle
    fun resultPage(value: Any): Page? = resultToken?.invoke(value)?.let(::page)
    fun contentPage(value: Any): Page? = contentToken?.invoke(value)?.let(::page)
    fun contentBundles(value: Any): List<Bundle> = (contentResults?.get(value) as? Map<*, *>)
        ?.entries?.filter { (key, _) -> key is String && key.endsWith("#image_pick_mode") }
        ?.mapNotNull { it.value as? Bundle }.orEmpty()
    /** 只标记 ROM 明确命中的网页图片，不能把普通 ImageView 回退猜成网页截图。 */
    fun markWebImage(value: Any, bundles: List<Bundle>) {
        if (contentCatcher?.get(value) == "WebViewCatcher") bundles.forEach {
            it.putString(PortalMediaContract.CAPTURE_ORIGIN, "WebViewCatcher")
        }
    }
    fun updateBitmap(bitmap: Bitmap?) { setBitmap?.invoke(null, bitmap) }
    fun currentBitmap(): Bitmap? = getBitmap?.invoke(null) as? Bitmap
    /** 宿主截图从 (0,0) 起，仅裁去底部导航栏；目标范围取本次结果的快照。 */
    fun align(owner: Any, target: Rect?): Rect? {
        val bitmap = screenshot?.get(owner) as? Bitmap ?: return null
        if (bitmap.isRecycled) return null
        target ?: return null
        val visible = PortalImageGeometry.visible(PortalImageGeometry.Bounds(target.left, target.top, target.right, target.bottom),
            bitmap.width, bitmap.height) ?: return null
        return Rect(visible.left, visible.top, visible.right, visible.bottom).also { picked?.set(owner, it) }
    }
    fun screenshotCandidate(owner: Any): Bitmap? {
        val bitmap = screenshot?.get(owner) as? Bitmap ?: return null
        if (bitmap.isRecycled) return null
        val rect = picked?.get(owner) as? Rect ?: return null
        return crop(bitmap, rect)
    }
    /** 单指路径不创建宿主截图；只在没有位图时补抓当前可见区域，不展开或滚动。 */
    fun touchRect(owner: Any): Rect? = (firstTouch?.invoke(owner) as? PointF)?.let {
        Rect((it.x - 125).toInt(), (it.y - 125).toInt(), (it.x + 125).toInt(), (it.y + 125).toInt())
    }
    fun visibleCandidate(context: Context, target: Rect?): Bitmap? {
        val rect = target ?: return null
        val bitmap = fullScreenshot?.invoke(null, context) as? Bitmap ?: return null
        try {
            return crop(bitmap, rect)
        } finally { bitmap.recycle() }
    }
    private fun crop(bitmap: Bitmap, rect: Rect): Bitmap? {
        val visible = PortalImageGeometry.visible(PortalImageGeometry.Bounds(rect.left, rect.top, rect.right, rect.bottom),
            bitmap.width, bitmap.height) ?: return null
        if ((visible.right - visible.left).toLong() * (visible.bottom - visible.top) > 40_000_000) return null
        val crop = Bitmap.createBitmap(bitmap, visible.left, visible.top, visible.right - visible.left, visible.bottom - visible.top)
        val owned = crop.copy(Bitmap.Config.ARGB_8888, false)
        if (crop !== bitmap && crop !== owned) crop.recycle()
        return owned
    }
}
