package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.content.Context
import android.graphics.drawable.Drawable
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import io.github.kotoriinsky.tsuzuri.hook.compat.CopyDirectCompat
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.IconPackResolver

/** 后台解析并缓存整页图标，主线程只绑定结果；复用已有 appfilter 与形状合成能力。 */
internal class CopyDirectAppIcons {
    private data class Icon(val name: String, val drawable: Drawable?, val themed: Boolean)
    private var cachedPack: String? = null
    private val cache = HashMap<String, Icon>()
    private val nativeIcons = HashMap<String, Drawable?>()

    fun resolve(context: Context, rows: List<CopyDirectCompat.AppRow>, packageName: String?): List<CopyDirectCompat.AppAppearance> {
        if (cachedPack != packageName) {
            cachedPack = packageName
            cache.clear()
        }
        val pack = IconPackResolver.packFor(context, packageName)
        return rows.map { row ->
            if (!nativeIcons.containsKey(row.packageName)) nativeIcons[row.packageName] = row.nativeIcon
            val icon = cache.getOrPut(row.packageName) {
                val pm = context.packageManager
                val name = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getApplicationLabel(pm.getApplicationInfo(row.packageName, 0)).toString()
                }.getOrElse { CopyDirect.fullName(row.packageName, row.name) }
                // 原生 yz → lo1 得到的图标已在行上，沿用它作为图标包的回退来源。
                val fallback = nativeIcons[row.packageName]?.constantState?.newDrawable(context.resources)
                    ?: runCatching { pm.getApplicationIcon(row.packageName) }.getOrElse { pm.defaultActivityIcon }
                val themed = pack?.drawable(row.packageName, null, fallback)
                Icon(name, themed ?: fallback, themed != null && themed !== fallback)
            }
            val browserRole = row.key.endsWith("#${CopyDirect.DEFAULT_BROWSER}")
            CopyDirectCompat.AppAppearance(row, if (browserRole) "默认浏览器" else icon.name,
                icon.drawable?.constantState?.newDrawable(context.resources) ?: icon.drawable, icon.themed,
                if (!browserRole) null else if (row.packageName == CopyDirect.DEFAULT_BROWSER) "未设置，打开时选择应用" else icon.name)
        }
    }
}
