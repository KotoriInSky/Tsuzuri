package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.graphics.drawable.Drawable
import android.os.Handler
import android.view.View
import android.widget.ImageView
import io.github.kotoriinsky.tsuzuri.hook.compat.CopyDirectCompat
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.IconPackResolver
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 覆盖原生统一图标入口的应用图标；后台解析，View 重用或包切换后旧任务不再绑定。 */
internal class CopyDirectCapsuleIcons(
    private val bridge: CopyDirectCompat,
    private val background: Handler,
    private val main: Handler,
    private val currentPack: () -> String?,
    private val log: (String) -> Unit,
) {
    private data class Request(val loader: Any, val type: String, val value: String)
    private val requests = WeakHashMap<ImageView, Request>()
    private data class Icon(val drawable: Drawable, val themed: Boolean)
    private val cache = HashMap<String, Icon?>()
    private var cachedPack: String? = null

    private companion object {
        const val MODULE_PACKAGE = "io.github.kotoriinsky.tsuzuri"
    }

    /** 原生入口在 UI 线程执行；先由原方法留下默认图标，再解析图标包。 */
    fun bind(loader: Any, type: String?, value: String?, view: ImageView) {
        val packageName = when (type) {
            "application" -> value
            // 模块自己的 pkg_drawable 是专门画的图（如常亮的太阳），不是应用图标：套图标包会换成模块 App 图标
            "pkg_drawable" -> value?.substringBefore(':')?.takeUnless { it == bridge.context.packageName || it == MODULE_PACKAGE }
            else -> null
        }
        if (packageName.isNullOrBlank() || type == null || value == null) { requests.remove(view); return }
        val request = Request(loader, type, value)
        requests[view] = request
        val packName = currentPack() ?: return
        val fallback = view.drawable?.constantState?.newDrawable(bridge.context.resources)
        val reference = WeakReference(view)
        background.post {
            val icon = runCatching {
                if (cachedPack != packName) { cache.clear(); cachedPack = packName }
                cache.getOrPut(packageName) {
                    val pm = bridge.context.packageManager
                    val original = fallback ?: runCatching { pm.getApplicationIcon(packageName) }.getOrElse { pm.defaultActivityIcon }
                    IconPackResolver.packFor(bridge.context, packName)?.drawable(packageName, null, original)?.let {
                        Icon(it, it !== original)
                    }
                }
            }.getOrNull() ?: return@post
            main.post {
                val target = reference.get() ?: return@post
                if (currentPack() != packName || requests[target] !== request) return@post
                target.setImageDrawable(icon.drawable.constantState?.newDrawable(bridge.context.resources) ?: icon.drawable)
                target.visibility = View.VISIBLE
                log("复制直达胶囊图标 themed=${if (icon.themed) 1 else 0} package=$packageName")
            }
        }
    }

    /** 主线程重新走原生入口，切换图标包与关闭特性都立即恢复正确外观。 */
    fun refresh() {
        requests.entries.toList().forEach { (view, request) ->
            runCatching { bridge.iconMethod.invoke(request.loader, request.type, request.value, view) }
        }
    }
}
