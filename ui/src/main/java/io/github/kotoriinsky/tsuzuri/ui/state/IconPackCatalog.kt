package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.IconPackResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 图标包在**设置界面**这一侧的取法。
 *
 * 与 hook 侧 `feature/smallwindow/IconPackResolver.kt` 是两份实现，理由和
 * [loadAppCatalog] 一样：一个跑在模块自己的进程（要能画预览图、要能被用户点），
 * 一个跑在 SystemUI（要快、要绝不抛异常）。共用的只有**格式知识**：
 *
 *  - 图标包是靠 action 找出来的（`org.adw.launcher.THEMES` 等，真机图标包 manifest 实测）；
 *  - 映射表是 `res/xml/appfilter.xml` 或 `assets/appfilter.xml`；
 *  - 条目是 `<item component="ComponentInfo{包/类}" drawable="名"/>`。
 *
 * 更详细的取证写在 hook 侧那个文件里（含行号引用），这里不重复。
 *
 * ⚠️ 这里**只做预览**：读数、画一张 48dp 的图给用户看「套上会是什么样」。
 * 真正生效的解析在 SystemUI 那边，两边解析不出同样的结果时**以那边为准**
 * （用户看到的最终效果就是那边画的）。
 */

/** 一行图标包。`packageName` 就是写进配置的值。 */
data class IconPack(val packageName: String, val label: String)

sealed interface IconPackCatalog {
    data object Loading : IconPackCatalog
    /** 读到了。**可能是空的** —— 设备上一个图标包都没装是正常状态，不是错误。 */
    data class Ready(val packs: List<IconPack>, val queries: List<String>) : IconPackCatalog
    data class Unavailable(val reason: String) : IconPackCatalog
}

/**
 * 查设备上装了哪些图标包。**IO 线程调**。
 *
 * `queries` 是这次实际查询用的 action 列表，写进界面（诊断用）：
 * 「我装了图标包但列表是空的」最常见的原因是包可见性被过滤（某个 action 没声明在
 * `<queries>` 里），把查过的 action 显示出来，用户截图就能定位。
 */
suspend fun loadIconPacks(context: Context): IconPackCatalog = withContext(Dispatchers.IO) {
    val pm = context.packageManager ?: return@withContext IconPackCatalog.Unavailable("no package manager")
    val found = LinkedHashMap<String, IconPack>()
    for (action in IconPackActions) {
        val resolved = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(action), 0)
        } catch (t: Throwable) {
            continue
        }
        for (r in resolved) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (found.containsKey(pkg)) continue
            val label = try {
                r.loadLabel(pm).toString()
            } catch (_: Throwable) {
                ""
            }
            found[pkg] = IconPack(pkg, label.ifBlank { pkg })
        }
    }
    IconPackCatalog.Ready(
        found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
        IconPackActions.toList(),
    )
}

/**
 * 图标包的候选 action —— **直接引用真机那一份**，这里不再抄第二份。
 *
 * 原来 `:ui` 自己抄了一份逐字节相同的清单，靠注释里一句「必须是同一份」来维护。
 * 那是约定、不是机制：任何一边加了一条，另一边的编译和测试都不会报错，
 * 症状只会是「我装的图标包在列表里找不到」。现在只有一处事实来源。
 *
 * ⚠️ 仍然**必须**在 `ui/src/main/AndroidManifest.xml` 的 `<queries>` 里逐条声明这些 action：
 * Android 11 起的包可见性过滤不看代码，只看清单，而且不会报错 ——
 * 只会**静默返回不全的结果**。（清单没法从代码里带过来，`IconPackQueriesTest` 负责盯住两者一致。）
 */
private val IconPackActions: List<String> = IconPackResolver.ACTIONS

/**
 * 单项覆盖可选的一个图标：包里那张图的**名字** + （第 ① 段才有）它原本是给哪个应用画的。
 *
 * 界面给这个包挑图时按 `from` 认路（用户认得「QQ」而不是 `qq.icon`）。
 */
data class PackIconOption(
    val drawableName: String,
    /** `null` = 没有映射到任何**已装**应用（第 ② 段「包内其他」）。 */
    val from: InstalledApp?,
)

/**
 * 单项覆盖的候选池，**分两段**。
 *
 * ```
 * ① matched  appfilter 命中这个包对**已装应用**的映射   ← 默认视野，通常就是要找的那张
 * ② others   包内其他图标（没被映射到已装应用的）      ← 「借一张别的图」才往下翻
 * ```
 *
 * 分段的理由：包内 drawable 可能上千个，把「这个包本来给微信画的那张」和「从一大堆里
 * 翻出来的」混在一列，用户每次都要从头找。
 */
data class PackIconPool(
    val matched: List<PackIconOption>,
    val others: List<PackIconOption>,
) {
    val total: Int get() = matched.size + others.size
}

/**
 * 从图标包里挑图标的候选池（两段，见 [PackIconPool]）。
 *
 * ## 两段的数据来源
 *
 * ① 来自 appfilter 的**组件映射**：对每个已装应用查「这个包给它画的是哪张」。
 * 用 `seen` 去重：多个应用在包里共用一张图时只出现一次（`pure` 那种按类别共用图标的包很常见）。
 *
 * ② 来自 appfilter 里出现过的**其余 drawable 名**（含映射给未安装应用的 —— 真机 `pure` 包里
 * 7000 多条映射里绝大多数都是这一类，它们正是「借一张别的图」的来源）+ 包自己声明的
 * 形状素材（`iconback`/`iconmask`/`iconupon` 的 `img*`）。
 *
 * ⚠️ 这里**不做**「枚举 APK 里全部 drawable」：那要解析 `resources.arsc` 的（
 * TypeSpec/Type）表，是另一个量级的活；而枚举几千张图对用户也没有帮助 —— 名字是
 * `weixin.icon` 这种内部命名，翻列表认不出来。缺的那部分由**手输名字**补上
 * （见 `IconPackDrawableScreen` 的搜索框：候选里没命中时它会把输入当图标名去包里查一次）。
 */
suspend fun loadPackIconPool(
    context: Context,
    packPkg: String,
    apps: List<InstalledApp>,
): PackIconPool = withContext(Dispatchers.IO) {
    val filter = PackFilter.load(context, packPkg)
        ?: return@withContext PackIconPool(emptyList(), emptyList())

    val seen = HashMap<String, PackIconOption>()
    for (app in apps) {
        val name = try {
            filter.nameFor(context, app.packageName)
        } catch (_: Throwable) {
            null
        } ?: continue
        if (seen.containsKey(name)) continue
        seen[name] = PackIconOption(name, app)
    }
    val matched = seen.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.from?.label.orEmpty() })

    val matchedNames = HashSet<String>(matched.size * 2)
    for (o in matched) matchedNames.add(o.drawableName)

    val others = (filter.referencedDrawableNames() + filter.shapeNames())
        .filter { it !in matchedNames }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .map { PackIconOption(it, null) }

    PackIconPool(matched, others)
}

/**
 * 这个字符串**可能**是一个 drawable 资源名。
 *
 * 资源入口名的字符集就是 `[A-Za-z0-9._]`（aapt2 编出来的名字都在这个范围），所以这里当
 * **输入闸**用：用户打字过程中会出现空格、汉字、符号（那是**搜索词**，不是图标名），
 * 拿去查包只会得到「包里没有」这种噪音 —— 每敲一个字报一次，比不报还糟。
 */
fun looksLikeDrawableName(s: String): Boolean = s.isNotEmpty() && s.all { c ->
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.'
}

/**
 * 候选是否命中搜索词：**应用名 / 包名 / drawable 名**三者都搜。
 *
 * 抽成普通函数（不是 Composable）是为了能直接读、也能被「这一段还要不要去包里查手输名」
 * 那张判断复用 —— 两处各写一遍必然漂移，症状是「列表里有结果、却还是弹『包里没有』」。
 */
fun optionMatches(option: PackIconOption, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (option.drawableName.contains(q, ignoreCase = true)) return true
    val from = option.from ?: return false
    return from.label.contains(q, ignoreCase = true) ||
        from.packageName.contains(q, ignoreCase = true)
}

/**
 * 图标包预览图。**懒加载 + 有上限缓存**，只在可见行上取（和 [AppIconLoader] 同构）。
 *
 * 解析一个包的 appfilter 是几千条循环，所以**每个包只解析一次**，结果留在缓存里；
 * 缓存按条目数算在这里够用（包最多十来个）。
 */
class IconPackPreviewLoader(private val context: Context, private val sizePx: Int) {

    private val parsed = HashMap<String, PackFilter?>()

    /**
     * 保护 [parsed] **和**它里面的 `PackFilter`。
     *
     * 为什么需要：候选池里每张图各有一个 `LaunchedEffect`，它们同时进入 IO 线程 ——
     * 一个列表滚动时十来个协程会**同时**走到 `parsed.getOrPut`。裸 `HashMap` 的并发写会
     * 丢条目甚至把桶链写坏；而更隐蔽的是「解析到一半的表被另一个线程读」（`map` 边填边查）
     * 会静默查出错误结果。加锁之后每个包只解析一次，且查表永远看到完整的表。
     */
    private val parseLock = Mutex()

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * 取「[packPkg] 这个图标包里，[appPkg] 这个应用的图标」。
     *
     * @return null = 这个包没有覆盖这个应用（界面上会显示应用自带图标 + 说明）
     */
    suspend fun load(packPkg: String, appPkg: String): ImageBitmap? {
        val key = "$packPkg|$appPkg"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(packPkg, appPkg) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * 直接按 drawable 名取图标（「单项图标覆盖」里用户从图标包挑的是**名字**，
     * 而不是「某个应用的图标」—— 他可以借 QQ 的图标贴给微信）。
     */
    suspend fun loadByName(packPkg: String, drawableName: String): ImageBitmap? {
        val key = "$packPkg#$drawableName"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val filter = filterOf(packPkg) ?: return@withContext null
            filter.drawableByName(drawableName)?.toImageBitmap(sizePx)
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private suspend fun filterOf(packPkg: String): PackFilter? =
        parseLock.withLock { parsed.getOrPut(packPkg) { PackFilter.load(context, packPkg) } }

    private suspend fun decode(packPkg: String, appPkg: String): ImageBitmap? {
        val filter = filterOf(packPkg) ?: return null
        val drawable = filter.iconFor(context, appPkg) ?: return null
        return drawable.toImageBitmap(sizePx)
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * 一个图标包的映射表（预览用，只保留查表需要的东西）。
 *
 * 刻意**不实现** iconback/iconmask/iconupon 的合成：那是最终效果的细节，
 * 而这里只回答「这个包有没有我用的应用」。少一份实现就少一处漂移。
 *
 * 除了查表，它还负责回答「这个包里有哪些 drawable **名字**」（第 ② 段的来源）——
 * 那是同一份 appfilter 里的信息，只是换了个用法（见 [referencedDrawableNames]）。
 */
private class PackFilter(
    private val packageName: String,
    private val res: android.content.res.Resources,
) {
    private val map = HashMap<String, String>(8192)

    /** `iconback`/`iconmask`/`iconupon` 声明的形状素材名（`img*` 的值）。 */
    private val shapes = LinkedHashSet<String>(8)

    fun iconFor(context: Context, appPkg: String): Drawable? {
        val cn = launchComponent(context, appPkg) ?: return null
        val name = map["ComponentInfo{$cn}"]
            ?: map["ComponentInfo{$appPkg/${shortClass(appPkg, cn)}}"]
            ?: return null
        return try {
            val id = res.getIdentifier(name, "drawable", packageName)
            if (id > 0) @Suppress("DEPRECATION") res.getDrawable(id) else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 按名字取 drawable（单项覆盖用）。 */
    fun drawableByName(name: String): Drawable? = try {
        val id = res.getIdentifier(name, "drawable", packageName)
        if (id > 0) @Suppress("DEPRECATION") res.getDrawable(id) else null
    } catch (_: Throwable) {
        null
    }

    /** 这个包给某个应用用的是哪个 drawable（`null` = 没覆盖这个应用）。 */
    fun nameFor(context: Context, appPkg: String): String? {
        val cn = launchComponent(context, appPkg) ?: return null
        return map["ComponentInfo{$cn}"] ?: map["ComponentInfo{$appPkg/${shortClass(appPkg, cn)}}"]
    }

    /**
     * appfilter 里出现过的**全部** drawable 名（含指向未安装应用的）。
     *
     * 为什么这就是「包内其他图标」的来源：真机包里映射表动辄几千条，其中映射到**已装**
     * 应用的只有几十条 —— 剩下那些正是用户「借一张别的图」时会去翻的池子，而它们的名字
     * 就在这张表里，不必去解析 `resources.arsc`。
     *
     * 过滤掉不像资源名的值：包里偶见 `drawable="{{...}}"` 这类模板行（作者从模板生成时
     * 没替换），那种名字 `getIdentifier` 必然查不到，列出来只是噪音。
     */
    fun referencedDrawableNames(): Set<String> =
        map.values.filterTo(LinkedHashSet()) { looksLikeDrawableName(it) }

    /** 形状素材名（底板/遮罩/贴片），也属于「包内其他图标」。 */
    fun shapeNames(): Set<String> =
        shapes.filterTo(LinkedHashSet()) { looksLikeDrawableName(it) }

    private fun shortClass(pkg: String, cn: String): String {
        val cls = cn.substringAfter('/', cn)
        val full = if (cls.startsWith(".")) pkg + cls else cls
        return if (full.startsWith("$pkg.")) full.substring(pkg.length) else full
    }

    private fun launchComponent(context: Context, pkg: String): String? = try {
        context.packageManager?.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** 形状标签名。与 hook 侧 `IconPackResolver` 读的是同一组（那边要合成，这边只要名字）。 */
        private const val SHAPE_BACK = "iconback"
        private const val SHAPE_MASK = "iconmask"
        private const val SHAPE_UPON = "iconupon"

        fun load(context: Context, packPkg: String): PackFilter? = try {
            val res = context.packageManager?.getResourcesForApplication(packPkg) ?: return null
            PackFilter(packPkg, res).apply { parse() }
        } catch (_: Throwable) {
            // 图标包被卸载 / 资源损坏：界面显示「取不到预览」，不影响其它行
            null
        }
    }

    private fun parse() {
        try {
            val id = res.getIdentifier("appfilter", "xml", packageName)
            val parser = if (id > 0) {
                res.getXml(id)
            } else {
                android.util.Xml.newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "utf-8")
                }
            }
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            var c: String? = null
                            var d: String? = null
                            for (i in 0 until parser.attributeCount) {
                                when (parser.getAttributeName(i)) {
                                    "component" -> c = parser.getAttributeValue(i)
                                    "drawable" -> d = parser.getAttributeValue(i)
                                }
                            }
                            val cc = c?.trim().orEmpty()
                            val dd = d?.trim().orEmpty()
                            if (cc.isNotEmpty() && dd.isNotEmpty() && !cc.startsWith(":")) map[cc] = dd
                        }

                        // 形状素材：`<iconback img1="icon_back"/>`（img1…imgN，全收）
                        SHAPE_BACK, SHAPE_MASK, SHAPE_UPON -> {
                            for (i in 0 until parser.attributeCount) {
                                if (!parser.getAttributeName(i).startsWith("img")) continue
                                parser.getAttributeValue(i)?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let { shapes.add(it) }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // 解析中断就用已经读到的部分（真机包偶见尾部截断）
        }
    }
}

/** 把 drawable 画进一张固定尺寸的方图（自适应图标/位图都能这么画）。 */
internal fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val w = if (intrinsicWidth > 0) intrinsicWidth else sizePx
    val h = if (intrinsicHeight > 0) intrinsicHeight else sizePx
    val scale = sizePx.toFloat() / maxOf(1, maxOf(w, h))
    val dw = (w * scale).toInt().coerceAtLeast(1)
    val dh = (h * scale).toInt().coerceAtLeast(1)
    setBounds((sizePx - dw) / 2, (sizePx - dh) / 2, (sizePx + dw) / 2, (sizePx + dh) / 2)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
