package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「设备上装了哪些能启动的应用」这件事在设置界面这一侧的取法。
 *
 * 与 hook 侧 `FanTargets.build` 是**两份实现**，不是偷懒：那一边跑在 SystemUI 进程里，
 * 目标是最少开销地凑出扇形的候选清单；这一边跑在模块自己的进程里，要的是**完整**
 * （勾得到任何一个应用）和**可交互**（图标要画出来）。共用的只有规则：
 *
 *  - 取 `ACTION_MAIN` + `CATEGORY_LAUNCHER` 的全部条目；
 *  - **排除桌面自己** —— 把小窗打开桌面没有意义，而且 hook 侧也会把它丢掉，
 *    不排除的话用户会在清单里看到它、勾上，然后发现扇子里没有（最难查的一类「选了没反应」）；
 *  - 按包名去重（一个包可能有多个启动入口）；
 *  - 读不到名字的条目丢掉。
 */

/** 一行可勾选的应用。`label` 是显示名，`packageName` 是写进配置的那个值。 */
data class InstalledApp(val packageName: String, val label: String)

/**
 * 应用清单的三态。**加载中 / 读到了（可能是空的）/ 读不到**必须分开 ——
 * 「还没读完」和「一个都没有」画成同一种样子的话，用户只会以为功能坏了。
 */
sealed interface AppCatalog {
    data object Loading : AppCatalog
    data class Ready(val apps: List<InstalledApp>) : AppCatalog
    data class Unavailable(val reason: String) : AppCatalog
}

/**
 * 扫一遍设备上的可启动应用。**只允许在 IO 线程调**（要 loadLabel 上百个包），
 * 但内部自己不切线程 —— [AppCatalog.Loading] 由调用方先摆出来。
 *
 * 异常绝不外泄：拿不到清单返回 [AppCatalog.Unavailable]，界面走错误态。
 *
 * ⚠️ 需要 `AndroidManifest` 里的 `<queries>` 声明（已加）：Android 11 起
 * 「查得到哪些应用」是被包可见性过滤的，没声明时 `queryIntentActivities` 会返回
 * **一个不全的清单**，而且不报任何错 —— 表现是「应用多选里少了一大半应用」。
 */
suspend fun loadAppCatalog(context: Context): AppCatalog = withContext(Dispatchers.IO) {
    // 进程内缓存：以前每进一次清单页都要 loadLabel 一两百个包（每个一次跨进程），
    // 页面打开明显卡一下（用户 2026-10-22 报）。包有增删改时系统的变更序号会变，才重扫。
    val pm = context.packageManager ?: return@withContext AppCatalog.Unavailable("no package manager")
    synchronized(CatalogCache) {
        val cached = CatalogCache.catalog
        if (cached != null && runCatching { pm.getChangedPackages(CatalogCache.sequence) }.getOrNull() == null) {
            return@withContext cached
        }
    }
    val sequence = runCatching { pm.getChangedPackages(0)?.sequenceNumber }.getOrNull() ?: 0
    scanAppCatalog(context).also { result ->
        if (result is AppCatalog.Ready) {
            synchronized(CatalogCache) {
                CatalogCache.catalog = result
                CatalogCache.sequence = sequence
            }
        }
    }
}

/** 上一次扫到的清单（没有就是 null）。界面拿它当初始值，进页面不闪「加载中」。 */
fun peekAppCatalog(): AppCatalog? = synchronized(CatalogCache) { CatalogCache.catalog }

private object CatalogCache {
    var catalog: AppCatalog.Ready? = null
    var sequence: Int = 0
}

private fun scanAppCatalog(context: Context): AppCatalog {
    val pm = context.packageManager ?: return AppCatalog.Unavailable("no package manager")
    val home = resolveHome(context)

    val resolved = try {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
    } catch (t: Throwable) {
        return AppCatalog.Unavailable("query: " + t.javaClass.simpleName)
    }

    val byPackage = HashMap<String, InstalledApp>(resolved.size * 2)
    for (r in resolved) {
        val pkg = r.activityInfo?.packageName ?: continue
        if (pkg == home) continue
        if (byPackage.containsKey(pkg)) continue
        val label = try {
            r.loadLabel(pm).toString()
        } catch (_: Throwable) {
            ""
        }
        if (label.isEmpty()) continue
        byPackage[pkg] = InstalledApp(pkg, label)
    }

    return if (byPackage.isEmpty()) {
        // 「一个都看不到」几乎只可能是包可见性被过滤（少了 <queries> 声明），
        // 而不是这台设备真的没有桌面应用 —— 所以这里给出可查的原因，而不是画一个空列表
        AppCatalog.Unavailable("visible=0")
    } else {
        AppCatalog.Ready(
            byPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
        )
    }
}

/**
 * 「全部」与「已选」两个视图 + 搜索框的过滤规则。
 *
 * 抽成普通函数（不是 Composable）是为了能直接读、也避免掉进
 * 「@Composable 出现在普通 lambda 里」那个坑（本项目反复踩的一类编译错误）。
 *
 * 搜索同时匹配**显示名**和**包名**：前者是用户的说法，后者是排障/维护者的说法
 * （搜 `com.tencent.mm` 应该能命中「微信」）。
 */
fun filterApps(
    all: List<InstalledApp>,
    query: String,
    onlySelected: Boolean,
    selected: Set<String>,
): List<InstalledApp> {
    val q = query.trim()
    return all.filter { app ->
        if (onlySelected && app.packageName !in selected) return@filter false
        if (q.isEmpty()) return@filter true
        app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true)
    }
}

private fun resolveHome(context: Context): String? = try {
    context.packageManager
        ?.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        ?.activityInfo?.packageName
} catch (_: Throwable) {
    null
}

/**
 * 应用图标。**懒加载 + 有上限的缓存**。
 *
 * 为什么不在扫清单时一次性把所有图标都解码出来：装机应用有一两百个，
 * 一个 40dp 的 ARGB 位图在 3x 密度下是 120×120×4 ≈ 57KB，全解就是 10MB 以上 ——
 * 而屏幕上一次只看得到十来个。列表是 `LazyColumn`，只会对**可见项**调 [load]。
 *
 * 缓存按**字节**计量（不是条目数）：条目数相同、图标大小不同，占的内存可以差一倍。
 */
class AppIconLoader(private val context: Context, private val sizePx: Int) {

    /** 取这个包的图标。取不到（已卸载 / PM 抛异常）返回 null，由界面画占位块。 */
    suspend fun load(packageName: String): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(packageName) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** 已缓存就直接给（不挂起），界面首帧用它，滚回来的行不再闪占位块。 */
    fun cached(packageName: String): ImageBitmap? = cache.get("$packageName@$sizePx")

    private fun decode(packageName: String): ImageBitmap? = try {
        context.packageManager?.getApplicationIcon(packageName)?.let { drawable ->
            // 画到一张固定尺寸的位图里：自适应图标、矢量图标都能这么画，
            // 不需要 branch 图标类型（也不需要额外依赖把它们转成 Painter）
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        /** 6MB 上限：够装下几屏滚动的图标，又不会让这个设置界面变成一个内存大户。 */
        const val MAX_BYTES = 6 * 1024 * 1024

        /**
         * 进程内共享（以前每个页面各建一个，进一次页面就重新解码一遍）。
         * 键带尺寸：同一个包在不同页面可能要不同大小的图。
         */
        val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
        }
    }
}
