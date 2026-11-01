package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * 图标包（icon pack）的读取与应用。
 *
 * ## 图标包是什么（查证过的事实，不是推测）
 *
 * 一个图标包就是**一个普通 APK**：声明一个带特定 action 的 activity（让人能点开它），
 * 资源里放一张「组件 → drawable 名」的映射表 + 一堆图标位图。没有任何系统服务参与，
 * 全靠读取方自己去解析。
 *
 * ### 1) 怎么找出「设备上装了哪些图标包」
 *
 * 权威做法是 `queryIntentActivities` 一组固定 action。两处独立证据：
 *
 *  - **魅族 Launcher 的反编译**（本仓库 `research/decompiled/flyme/sources/com/meizu/flyme/
 *    launcher/iconpack/AppIconPackLoader.java:68-71`）：它只查两个 action ——
 *    `org.adw.launcher.THEMES` 与 `com.gau.go.launcherex.theme`，用 `getApplicationLabel`
 *    当显示名、包名当键；
 *  - **魅族真机上两个真实图标包的 AndroidManifest**（`me.morirain.dev.iconpack.pure`
 *    与 `com.nurke.perfectone`，本机 `aapt2 dump xmltree` 实测），它们声明了
 *    `org.adw.launcher.THEMES`、`com.anddoes.launcher.THEME`、`com.gau.go.launcherex.theme`、
 *    `com.novalauncher.THEME`、`com.teslacoilsw.launcher.THEME`、
 *    `com.dlto.atom.launcher.THEME`、`com.zeroteam.zerolauncher.theme`、
 *    `ch.deletescape.lawnchair.ICONPACK`、`com.fede.launcher.THEME_ICONPACK`、
 *    `com.gridappsinc.launcher.theme.apk_action`、`com.lge.launcher2.THEME`
 *    等一长串（还有 `…icons.ACTION_PICK_ICON` 这类「挑单个图标」的 action）。
 *
 * 一个包只要命中**任意一个** action 就算图标包。魅族只查前两个，于是漏掉一批 ——
 * 多查几个 action 的成本是几次包管理器查询，漏掉的代价是「我装的图标包在列表里找不到」。
 *
 * ### 2) 映射表长什么样（真机 APK 实测）
 *
 * 两个来源，**按顺序**找（顺序照抄魅族 `AppIconPackItem.java:57-81`）：
 *
 * ```
 * 1. res.getIdentifier("appfilter", "xml", packPkg) → res.getXml(id)   // res/xml/appfilter.xml
 * 2. res.getAssets().open("appfilter.xml")                            // assets/appfilter.xml
 * ```
 *
 * **两条都真实存在**：`pure` 包两者都有（1.37MB 的 res/xml 版 + 1.11MB 的 assets 版），
 * 更老的包只放 assets。只实现一条会漏掉一半的包。
 *
 * 内容是：
 *
 * ```xml
 * <resources>
 *   <iconback img1="icon_back"/>     <!-- 未命中时的底板 -->
 *   <iconmask img1="icon_mask"/>     <!-- 遮罩（DST_OUT 抠图） -->
 *   <iconupon img1="icon_upon"/>     <!-- 前景贴片 -->
 *   <scale factor="1.15"/>
 *   <item component=":CALCULATOR" drawable="calculator"/>                    <!-- 伪组件，忽略 -->
 *   <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="wechat_2"/>
 * </resources>
 * ```
 *
 * 也就是 `item@component` 是 **`ComponentInfo{包名/完整类名}`**（`pure` 的 7241 条里
 * 7229 条是这个形状，另外 12 条是 `:BROWSER` 这类 Nova/Apex 伪组件），
 * `item@drawable` 是指向图标包自己资源里的 drawable 名。
 *
 * ### 3) 我们只有「包名」，映射表要的是「组件名」
 *
 * 所以查询键必须**由包名推出启动组件**：`pm.getLaunchIntentForPackage(pkg)` 的
 * `component`，再 `ComponentName.flattenToString()`（= `包名/完整类名`）拼成
 * `ComponentInfo{...}`。这正是魅族那两行 lambda 干的事
 * （`ItemInfo$$ExternalSyntheticLambda6` = `Intent.getComponent()`、
 * `AppIconPackItem$$ExternalSyntheticLambda1` = `ComponentName.toString()`）。
 *
 * 三种键都试（[Pack.matchComponent]）：完整类名式、同包缩写式（`包/.类名`，ADW 老包用），
 * 以及魅族那条「组件全名点号换下划线后当 drawable 名查」的兜底
 * （`AppIconPackItem.java:157-165`）。
 *
 * ## 回退链（**必须可靠**：图标包通常只覆盖热门应用）
 *
 * ```
 * 1. 没有配图标包                        → 应用自带图标
 * 2. 映射表命中组件                      → 图标包里的图标
 * 3. 映射表没命中，但有 iconback/mask/upon → 应用自带图标 + 图标包给的形状
 * 4. 什么都没有                          → 应用自带图标
 * ```
 *
 * 第 3 条来自魅族 `AppIconPackItem.generateBitmap`（`:233-282`）：形状类图标包
 * （只有底板+遮罩、没有逐个应用的映射，真机上的 `pure` 就是）**本来就是这么用的** ——
 * 它想让所有没被覆盖的图标也有统一外形。跳过这一步，那些包的效果会全部消失。
 *
 * 每一步都套 `try/catch`：图标包可能被卸载、资源可能损坏、`Resources` 可能抛
 * `NotFoundException`。**任何一步失败都退回上一步，绝不把异常放出去** ——
 * 这里跑在 SystemUI 的后台扫描线程上，异常会带走宿主。
 *
 * ## 线程与缓存
 *
 * 解析 appfilter 是几千条的循环 + 一次跨进程读资源，**只在后台合成线程调**
 * （见 `FanTargets.Pool.composePages`），结果按包名缓存：换包才重解析。
 * 绘制路径（`FanView.onDraw`）绝不碰这个类 —— 位图在合成时就装好了。
 */
object IconPackResolver {

    private const val TAG = "FP-Fan"

    /** 一个图标包的显示信息（选择界面用；hook 侧只用包名做校验与日志）。 */
    class PackInfo(val packageName: String, val label: String)

    /**
     * 候选 action。前两条是魅族 Launcher 查的那两条（`AppIconPackLoader.java:68-71`），
     * 其余来自真机图标包 manifest 里实际声明的 action。顺序有意义：命中即收工。
     *
     * **公开**是刻意的：设置界面（`:ui`）必须用这一份，而不是自己再抄一遍
     * （抄一遍就要手动同步，两份都会在对方改动后静默漂移 —— 原来的
     * `ui/state/IconPackCatalog.kt` 里就抄了一份逐字节相同的）。
     * 注意 `ui/src/main/AndroidManifest.xml` 的 `<queries>` 里仍然要逐条声明这些 action ——
     * 那是包可见性过滤的**清单**要求，没法从代码里带过去。
     *
     * 用 `List` 而不是 `Array`：这是跨模块可见的常量，`Array` 是可变的 ——
     * 调用方一句 `ACTIONS[0] = "..."` 就能改掉真机侧的行为，而且编译期拦不住。
     */
    val ACTIONS: List<String> = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.zeroteam.zerolauncher.theme",
        "ch.deletescape.lawnchair.ICONPACK",
        "com.fede.launcher.THEME_ICONPACK",
        "com.gridappsinc.launcher.theme.apk_action",
    )

    /**
     * 映射表所在资源名。两个来源：`res/xml/appfilter.xml`（先）与 `assets/appfilter.xml`（后）。
     */
    private const val FILTER_NAME = "appfilter"

    /** 形状合成的兜底尺寸与上下限（px）。别太大：每个图标一份，会白占内存。 */
    private const val SHAPE_PX = 192
    private const val SHAPE_MIN = 144
    private const val SHAPE_MAX = 256

    /** 只有一个包在用（用户单选），所以缓存一条就够；换包整体替换。 */
    @Volatile
    private var cached: Pack? = null

    private val lock = Any()

    /** 进程级 `PackageManager`：`Pack` 不持有 Context，推启动组件时要用它。 */
    @Volatile
    private var pm: PackageManager? = null

    /** 预热 `PackageManager` 引用。幂等，次次都调没关系。 */
    fun attach(context: Context) {
        if (pm == null) pm = context.packageManager
    }

    /**
     * 列出设备上所有图标包。**后台线程调**（十次包查询 + 若干次 `loadLabel`）。
     *
     * 按包名去重：一个包通常声明好几个 action，不去重会出现同一个包好几行。
     */
    fun listInstalled(context: Context): List<PackInfo> {
        val localPm = context.packageManager ?: return emptyList()
        val out = LinkedHashMap<String, PackInfo>()
        for (action in ACTIONS) {
            val resolved = try {
                @Suppress("DEPRECATION")
                localPm.queryIntentActivities(Intent(action), 0)
            } catch (t: Throwable) {
                Log.w(TAG, "查图标包失败（$action）：" + t.javaClass.simpleName)
                continue
            }
            for (r in resolved) {
                val pkg = r.activityInfo?.packageName ?: continue
                if (out.containsKey(pkg)) continue
                val label = try {
                    r.loadLabel(localPm).toString()
                } catch (_: Throwable) {
                    ""
                }
                out[pkg] = PackInfo(pkg, label.ifBlank { pkg })
            }
        }
        return out.values.toList()
    }

    /**
     * 取当前该用的图标包。`packageName` 为空 = 不套用（返回 null）。
     *
     * 同一个包重复调用返回同一个实例（解析结果在里面缓存）；换包才重新解析。
     * 包被卸载 / 解析失败时返回 null，于是上层自动退回应用自带图标。
     */
    fun packFor(context: Context, packageName: String?): Pack? {
        attach(context)
        val name = packageName?.trim().orEmpty()
        if (name.isEmpty()) return null
        cached?.let { if (it.packageName == name) return it }
        synchronized(lock) {
            cached?.let { if (it.packageName == name) return it }
            val loaded = try {
                val res = pm?.getResourcesForApplication(name) ?: return null
                Pack(name, res)
            } catch (t: Throwable) {
                // 卸载 / 损坏 / 被禁用：静默退回系统图标，只留一条日志
                Log.w(TAG, "图标包 $name 打不开，退回应用自带图标：" + t.javaClass.simpleName)
                null
            }
            cached = loaded
            return loaded
        }
    }

    /**
     * 一个已解析的图标包。
     *
     * 构造时就解析映射表（后台线程），之后 [drawable] 都是内存查表 + 一次资源解码。
     */
    class Pack internal constructor(
        val packageName: String,
        private val res: Resources,
    ) {
        /** `ComponentInfo{包/类}` → 图标包里的 drawable 名。键是 appfilter 里写的原文。 */
        private val map = HashMap<String, String>(8192)
        /** 未安装的许可目标没有启动组件；按 XML 顺序保留其首个包名映射。 */
        private val packageMap = HashMap<String, String>()

        /** 未命中时的底板 / 遮罩 / 前景。取不到就是 null。 */
        private var back: Bitmap? = null
        private var mask: Bitmap? = null
        private var upon: Bitmap? = null

        /** `<scale factor>`，夹在 (0,1]，默认 1（照抄 `AppIconPackItem.java:110-116`）。 */
        private var factor = 1f

        /** 映射表里有多少条 —— 写进日志用（0 条 = 这个包只有形状、没有逐个应用）。 */
        val size: Int get() = map.size

        /** 有没有形状素材。没有的话第 3 条回退链是空的，不用白跑一次合成。 */
        private val hasShape: Boolean get() = back != null || mask != null || upon != null

        init {
            loadFilter()
            loadShape()
            Log.i(
                TAG,
                "图标包已解析：$packageName 映射 ${map.size} 条、形状" + (if (hasShape) "有" else "无"),
            )
        }

        // ── 解析 ──────────────────────────────────────────────────────

        /**
         * 解析映射表。两个来源按顺序试（见类注释），都没有就留空表。
         *
         * 解析器统一用 [XmlPullParser]：`res.getXml(id)` 返回的就是它的实现（能解 AXML），
         * assets 里的是纯文本 XML，用 `android.util.Xml.newPullParser()` 解。
         */
        private fun loadFilter() {
            var parser: XmlPullParser? = null
            try {
                val id = res.getIdentifier(FILTER_NAME, "xml", packageName)
                if (id > 0) parser = res.getXml(id)
            } catch (t: Throwable) {
                Log.w(TAG, "res/xml/$FILTER_NAME 读不到：" + t.javaClass.simpleName)
            }
            if (parser == null) {
                parser = try {
                    android.util.Xml.newPullParser().apply {
                        setInput(res.assets.open("$FILTER_NAME.xml"), "utf-8")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "assets/$FILTER_NAME.xml 也读不到，这个包按「无映射」处理")
                    return
                }
            }

            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "item" -> readItem(parser)
                            "scale" -> readScale(parser)
                        }
                    }
                    event = parser.next()
                }
            } catch (t: Throwable) {
                // 解析到一半失败：**保留已经读到的部分**（真实包里最常见的是尾部截断），
                // 比整包丢弃有用得多
                Log.w(TAG, "appfilter 解析中断（已读到 ${map.size} 条）：" + t.javaClass.simpleName)
            }
        }

        private fun readItem(p: XmlPullParser) {
            var component: String? = null
            var drawable: String? = null
            for (i in 0 until p.attributeCount) {
                when (p.getAttributeName(i)) {
                    "component" -> component = p.getAttributeValue(i)
                    "drawable" -> drawable = p.getAttributeValue(i)
                }
            }
            val c = component?.trim().orEmpty()
            val d = drawable?.trim().orEmpty()
            // 伪组件（`:BROWSER` 这类）没有对应的真实应用，直接跳过；
            // 空值也跳过（个别包里有 `[package_name]/[launcher_activity_name]` 这种模板行）
            if (c.isEmpty() || d.isEmpty() || c.startsWith(":")) return
            map[c] = d
            if (c.startsWith("ComponentInfo{")) {
                val pkg = c.removePrefix("ComponentInfo{").substringBefore('/')
                if (pkg.isNotBlank() && '/' in c) packageMap.putIfAbsent(pkg, d)
            }
        }

        private fun readScale(p: XmlPullParser) {
            for (i in 0 until p.attributeCount) {
                if (p.getAttributeName(i) != "factor") continue
                val f = p.getAttributeValue(i)?.toFloatOrNull() ?: return
                // >1 会让图标溢出底板，魅族就是这么夹的（`AppIconPackItem.java:110-116`）
                factor = f.coerceIn(0.1f, 1f)
            }
        }

        /** 形状素材：`<iconback img1="…">`（可能 img1…imgN，取第一个，同魅族 `getBitmapFromList`）。 */
        private fun loadShape() {
            back = firstShapeBitmap("iconback")
            mask = firstShapeBitmap("iconmask")
            upon = firstShapeBitmap("iconupon")
        }

        /**
         * 读形状标签的第一个 `img*` 属性，再按名字取位图。
         *
         * 为什么重新解析一遍 appfilter 而不是解析时顺手记下名字：形状标签可能出现在
         * `item` 之后，「后面覆盖前面」会变成实现细节；重读一遍是确定性的，且只在
         * 第一次用这个包时发生。
         */
        private fun firstShapeBitmap(tag: String): Bitmap? = try {
            val id = res.getIdentifier(FILTER_NAME, "xml", packageName)
            if (id <= 0) null else {
                val p = res.getXml(id)
                var name: String? = null
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT && name == null) {
                    if (event == XmlPullParser.START_TAG && p.name == tag) {
                        // 取「img 开头里字典序最小」的那个 = img1 < img2 < …，也就是第一个
                        for (i in 0 until p.attributeCount) {
                            val an = p.getAttributeName(i)
                            if (!an.startsWith("img")) continue
                            val v = p.getAttributeValue(i) ?: continue
                            if (name == null || v < name) name = v
                        }
                    }
                    event = p.next()
                }
                name?.let { bitmapOf(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读 $tag 失败：" + t.javaClass.simpleName)
            null
        }

        /** 按名字取一张位图。取不到、或不是位图（矢量/颜色形状）就转成位图。 */
        private fun bitmapOf(name: String): Bitmap? = try {
            val id = res.getIdentifier(name, "drawable", packageName)
            if (id <= 0) null else {
                when (val d = @Suppress("DEPRECATION") res.getDrawable(id)) {
                    is BitmapDrawable -> d.bitmap
                    null -> null
                    else -> drawToBitmap(d, SHAPE_PX)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "取形状素材 $name 失败：" + t.javaClass.simpleName)
            null
        }

        // ── 应用 ──────────────────────────────────────────────────────

        /**
         * 给一个应用取最终图标。**没命中 / 取不到一律返回 [fallback]**（或它的形状版）。
         *
         * @param component `包名/类名`（完整或缩写形式），可为 null —— 拿不到启动组件时
         *                  退回「按包名推组件」+「点号换下划线」两条兜底
         * @param fallback 应用自带图标，也是所有失败路径的出口
         */
        fun drawable(pkg: String, component: String?, fallback: Drawable?): Drawable? {
            if (map.isEmpty() && !hasShape) return fallback
            val hit = matchComponent(pkg, component) ?: return shapeOn(fallback)
            val d = try {
                val id = res.getIdentifier(hit, "drawable", packageName)
                if (id > 0) @Suppress("DEPRECATION") res.getDrawable(id) else null
            } catch (t: Throwable) {
                Log.w(TAG, "取图标 $hit 失败（$pkg）：" + t.javaClass.simpleName)
                null
            }
            // 映射表指向了不存在的 drawable（真实包里出现过，通常是作者笔误）：
            // 不能返回 null（那会画成占位圆），退回形状或自带图标
            return d ?: shapeOn(fallback)
        }

        /**
         * 查表。三个键依次试：
         *
         * ```
         * 1. ComponentInfo{包/完整类名}   ← 主流写法（真机 pure 包 7229 条全是这个）
         * 2. ComponentInfo{包/.缩写类名}  ← ADW 时代的老包
         * 3. 组件全名把 `.` 和 `/` 换成 `_` 后当 drawable 名去查
         *    ← 魅族的兜底（`AppIconPackItem.java:157-165`），不少包确实按这个规则命名 drawable
         * ```
         */
        private fun matchComponent(pkg: String, component: String?): String? {
            val cn = component ?: launchComponent(pkg) ?: return packageMap[pkg]
            map["ComponentInfo{$cn}"]?.let { return it }

            // 2：把两种写法互相补一遍（映射表写缩写、我们给完整名，或反过来）
            val slash = cn.indexOf('/')
            if (slash > 0) {
                val cls = cn.substring(slash + 1)
                val full = if (cls.startsWith(".")) "$pkg$cls" else cls
                if (full.startsWith("$pkg.")) {
                    map["ComponentInfo{$pkg/${full.substring(pkg.length)}}"]?.let { return it }
                    map["ComponentInfo{$pkg/$full}"]?.let { return it }
                }
            }

            // 3
            val normalized = cn.lowercase(Locale.ROOT).replace('.', '_').replace('/', '_')
            val id = try {
                res.getIdentifier(normalized, "drawable", packageName)
            } catch (_: Throwable) {
                0
            }
            return if (id > 0) normalized else packageMap[pkg]
        }

        /** 包名 → 启动组件（`包/类`）。取不到返回 null，由调用方走兜底键。 */
        private fun launchComponent(pkg: String): String? = try {
            pm?.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
        } catch (_: Throwable) {
            null
        }

        /**
         * 按 **drawable 名**取一张图标。
         *
         * 给「单项图标覆盖」用：用户从图标包里挑了一个图标（存的是
         * `包名/drawable名`），这里按名字取 —— 不经过组件映射，所以「借 QQ 的图标
         * 贴给微信」这种用法是成立的（用户明确要的就是这个）。
         */
        fun drawableByName(name: String): Drawable? = try {
            val id = res.getIdentifier(name, "drawable", packageName)
            if (id > 0) @Suppress("DEPRECATION") res.getDrawable(id) else null
        } catch (t: Throwable) {
            Log.w(TAG, "取图标 $name 失败：${t.javaClass.simpleName}")
            null
        }

        /**
         * 形状回退：把应用自带图标按 `iconback / iconmask / iconupon / scale` 合成一遍。
         *
         * 逐条对应魅族 `AppIconPackItem.generateBitmap`（`:233-282`）：
         * 先画底板 → 图标按 factor 缩放居中画上 → 用遮罩以 `DST_OUT` 抠掉外面 →
         * 最后画前景贴片。没有形状素材时原样返回（不白跑一次合成）。
         *
         * @return 合成结果；任何一步失败都返回 [icon] 本身
         */
        fun shapeOn(icon: Drawable?): Drawable? {
            if (icon == null || !hasShape) return icon
            return try {
                val n = sizeOf(icon)
                val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                back?.let { drawScaled(c, it, n) }

                val iw = (n * factor).toInt().coerceAtLeast(1)
                val inner = drawToBitmap(icon, iw)
                val m = mask
                if (m != null) {
                    // 先画到独立图层再抠：直接在画布上抠会把底板一起抠掉
                    val layer = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                    val lc = Canvas(layer)
                    drawScaled(lc, inner, n)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    drawScaled(lc, m, n, p)
                    p.xfermode = null
                    c.drawBitmap(layer, 0f, 0f, null)
                    layer.recycle()
                } else {
                    drawScaled(c, inner, n)
                }
                upon?.let { drawScaled(c, it, n) }
                BitmapDrawable(res, out)
            } catch (t: Throwable) {
                Log.w(TAG, "形状合成失败，退回应用自带图标：" + t.javaClass.simpleName)
                icon
            }
        }

        /** 合成尺寸：取图标自身的方边，夹在 [SHAPE_MIN]–[SHAPE_MAX]。 */
        private fun sizeOf(icon: Drawable): Int {
            val w = if (icon.intrinsicWidth > 0) icon.intrinsicWidth else SHAPE_PX
            val h = if (icon.intrinsicHeight > 0) icon.intrinsicHeight else SHAPE_PX
            val n = maxOf(w, h).coerceIn(SHAPE_MIN, SHAPE_MAX)
            return if (n % 2 == 0) n else n + 1
        }

        private fun drawScaled(c: Canvas, b: Bitmap, n: Int, paint: Paint? = null) {
            val scale = n.toFloat() / maxOf(1, maxOf(b.width, b.height))
            val w = (b.width * scale).toInt().coerceAtLeast(1)
            val h = (b.height * scale).toInt().coerceAtLeast(1)
            c.drawBitmap(
                b,
                null,
                Rect((n - w) / 2, (n - h) / 2, (n + w) / 2, (n + h) / 2),
                paint,
            )
        }

        private fun drawScaled(c: Canvas, d: Drawable, n: Int, paint: Paint? = null) {
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else n
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else n
            val scale = n.toFloat() / maxOf(1, maxOf(w, h))
            val dw = (w * scale).toInt().coerceAtLeast(1)
            val dh = (h * scale).toInt().coerceAtLeast(1)
            d.setBounds((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2)
            d.draw(c)
        }

        private fun drawToBitmap(d: Drawable, n: Int): Bitmap {
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else n
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else n
            val scale = n.toFloat() / maxOf(1, maxOf(w, h))
            val dw = (w * scale).toInt().coerceAtLeast(1)
            val dh = (h * scale).toInt().coerceAtLeast(1)
            d.setBounds((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2)
            d.draw(c)
            return b
        }
    }
}
