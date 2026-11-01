package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.IconPackResolver

/**
 * 侧滑索引面板的**图标包替换**（需求 #5 的后半：「图标先以完整图标包更换为主」）。
 *
 * ## 为什么这里要自己包一层（而不直接调 [IconPackResolver]）
 *
 * [IconPackResolver] 是给**小窗扇形**写的：那一边的图标是**后台合成线程**上一次性合成好、
 * 存进位图的（`FanTargets.Pool.composePages` 的注释写明了「绘制路径绝不碰这个类」）。
 * 面板这边不一样 —— 它在**主线程**上、**每次换字母**都要重新绑定几十个格子
 * （手指划过字母条时一秒钟能换好几个字母）。把扇形那套调用方式照搬过来会有两个问题：
 *
 *  1. **`Pack.drawable()` 不是 O(1)。** 它内部的 `matchComponent` 有三个查询键，
 *     第 3 条是 `Resources.getIdentifier(组件名点号换下划线, "drawable", 包名)` ——
 *     那是**按名字查资源表**，比命中映射表（一次 HashMap 查）慢一到两个数量级；
 *     而「这个包没覆盖这个应用」恰恰是常态（真实图标包通常只覆盖热门应用），
 *     于是**失败路径几乎每次都会走到那个慢查询**。
 *  2. **未命中时它会现合成一张位图**（`shapeOn`，最大 256×256 ARGB_8888 ≈ 256KB），
 *     失败路径每次重新分配一张 —— 在跟手指的换字母路径上就是 GC 抖动。
 *
 * 所以这一层做**两件事**，都不改共用的那个文件：
 *  - **按 (包名, 组件) 缓存结果**（含「没覆盖」这个结论），把上面两条压到「每个应用一次」；
 *  - **把解析 appfilter 的那次同步读（几千条 XML）挪到后台线程预热** ——
 *    `IconPackResolver.packFor` 首次调用会同步解析整个映射表，在主线程上做会卡出可见的一帧。
 *
 * ## 缓存的内容是 `Drawable` 本身，不是「名字」
 *
 * 想过只缓存「组件 → drawable 名」再按名字取：那样能省下位图常驻内存。但 `Pack` 把
 * 映射表与取图都封在内部（`map` / `matchComponent` 是 private），外部拿不到名字，
 * 只有 `drawable()` / `drawableByName()` 两个出口 —— 前者内部做完整查找、后者要名字。
 * 于是只能缓存它的**返回值**。代价可接受：这些位图本来就在图标包 `Resources` 的
 * drawable 缓存里（`getDrawable` 第二次取到的是同一个对象），所以额外常驻的只是
 * 一层 `Drawable` 包装，不是又一份像素。
 *
 * ## 换包 / 卸载 / 损坏
 *
 * 缓存按**包名**分区：包名一变，整块作废（与 `FanTargets.setPack` 同一条纪律 ——
 * 缓存里的位图来自上一个包，留着会串味）。解析失败（包被卸载、资源损坏）时
 * [IconPackResolver.packFor] 返回 null，这里就退回自带图标，**绝不抛**：
 * 这段代码跑在 SystemUI 的主线程上，异常逃出去就是黑屏。
 *
 * ## 线程
 *
 * - [prewarm] 可以在任何线程调（它只排一个后台任务）；
 * - [apply] **只在主线程调**（`bindCell` 的调用点），内部只做查表 + `synchronized`，
 *   首次未预热时退回自带图标 —— 「第一次手势用的是自带图标、第二次起才是图标包」
 *   比「第一次手势卡 300ms」好得多，而且预热在装配置时就已经排上了。
 */
internal object SideIndexIconPack {

    private const val TAG = "FP-SideIndex"

    /**
     * 缓存上限。一台设备上可启动应用通常一两百个，512 已经远超「面板里能出现的格子数」；
     * 设上限只是为了让「异常情况下一直涨」有个终点（内存里最坏就是 512 个 Drawable）。
     */
    private const val CACHE_MAX = 512

    private val lock = Any()

    /** 当前缓存属于哪个包。null = 还没有缓存（或上一个包已作废）。 */
    private var cachedPack: String? = null

    /**
     * `包名/组件` → 图标。**值可以是 null**（= 这个包没覆盖这个应用，用自带图标），
     * 所以查询时要用 `containsKey` 区分「缓存过 null」与「没缓存」。
     */
    private val cache = HashMap<String, Drawable?>(256)

    /** 已经排过后台预热（或已经在用）的包名。避免每次手势都排一个重复任务。 */
    @Volatile
    private var warmed: String? = null

    /**
     * 已经预解析过的那一批应用（签名 = 包名 + 数量 + 首个包）。
     * 见 [prewarmIcons]：同一代不重复扫（清单重建时会换成新签名，自然会再扫一次）。
     */
    @Volatile
    private var swept: String? = null

    /**
     * 预热：把 appfilter 的解析（几千条 XML，同步、耗时可观）挪到后台线程。
     *
     * 只对**当前配置的包名**排一次；同一个包重复调用直接返回。
     * `IconPackResolver` 自己按包名缓存解析结果，所以这里预热出来的对象
     * 主线程侧的 [apply] 会直接命中它的缓存（一次指针比较）。
     */
    fun prewarm(context: Context, packPkg: String?) {
        val pkg = packPkg?.trim().orEmpty()
        if (pkg.isEmpty() || warmed == pkg) return
        warmed = pkg
        // 换包时立刻作废缓存（新包的图标与旧包不是一套）
        synchronized(lock) {
            if (cachedPack != null && cachedPack != pkg) {
                cache.clear()
                cachedPack = null
                // 逐应用那份缓存刚作废 → 允许 [prewarmIcons] 对新包再扫一遍
                swept = null
            }
        }
        val app = context.applicationContext ?: context
        val thread = Thread({
            try {
                val pack = IconPackResolver.packFor(app, pkg)
                if (pack == null) {
                    Log.w(TAG, "图标包 $pkg 解析失败，面板图标退回应用自带")
                } else {
                    Log.i(TAG, "图标包已预热：$pkg（映射 ${pack.size} 条）")
                }
            } catch (t: Throwable) {
                // 后台线程上抛出去同样会带走进程（SystemUI），必须自己吃掉
                Log.w(TAG, "图标包预热失败（$pkg），面板图标退回应用自带", t)
            }
        }, "fp-side-index-iconpack")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /**
     * 给一个应用取图标。**主线程**。
     *
     * @param packPkg 配置里的图标包（null / 空 = 不套用）
     * @param pkg 应用包名
     * @param activity 启动组件类名。**必须传**（`AppEntry.activityName`）：图标包的
     *   appfilter 是按 `包/类` 索引的，传 null 会让 `Pack` 内部走
     *   `pm.getLaunchIntentForPackage` —— 那是一次跨进程查询，而这条路径在主线程上。
     * @param fallback 应用自带图标（也是所有失败路径的出口）
     */
    fun apply(context: Context, packPkg: String?, pkg: String, activity: String?, fallback: Drawable?): Drawable? {
        val packName = packPkg?.trim().orEmpty()
        if (packName.isEmpty() || fallback == null) return fallback
        val app = context.applicationContext ?: context
        val pack = IconPackResolver.packFor(app, packName) ?: return fallback
        val key = pkg + "/" + activity
        synchronized(lock) {
            if (cachedPack != packName) {
                cache.clear()
                cachedPack = packName
            }
            if (cache.containsKey(key)) return cache[key] ?: fallback
        }
        // 查表在锁外做：它可能要读图标包资源（跨进程），不该占着锁 ——
        // 这段代码只在主线程调，锁本身只用于保护那块 HashMap。
        val resolved = try {
            pack.drawable(pkg, activity, fallback)
        } catch (t: Throwable) {
            Log.w(TAG, "套用图标包失败（$pkg），退回自带图标", t)
            fallback
        }
        val stored = if (resolved === fallback) null else resolved
        synchronized(lock) {
            if (cachedPack == packName) {
                if (cache.size >= CACHE_MAX) cache.clear()
                cache[key] = stored
            }
        }
        return resolved
    }

    /**
     * **把这批应用的面板图标在后台全部解析好**（2026-09-15 加，治「换字母掉帧」）。
     *
     * ## 为什么必须有它（实测数字）
     *
     * 一次「快速划过字母条」的探针（`SideIndexOverlay` 的临时探针，同一次手势）：
     *
     * ```
     * buildPanel=9次/353ms   bindCell=80次/259ms（其中图标解析 213ms）  assemblePanel=9次/43ms
     * ```
     *
     * 即 **73% 的主线程时间花在图标解析上，平均 2.7ms/格**。原因在 [IconPackResolver]
     * 的回退链：真实图标包只覆盖热门应用，所以**大多数格子走的是「没命中 → 现合成一张
     * 192×192 形状位图」**那条路（`shapeOn`）。而它每换一个字母都会为一批**从没解析过**
     * 的应用重新发生一次 —— 60fps 的预算是 16.7ms，光这一项就爆帧。
     *
     * 同一件事在扇形那边是**后台合成**的（`FanTargets.Pool.composePages` 的注释写明
     * 「绘制路径绝不碰这个类」）；索引这边一直没有对应的那一环，于是成本落在
     * 跟手指的换字母路径上。这个函数就是补上那一环。
     *
     * ## 代价与边界（写清楚，免得后人以为是白拿的）
     *
     * - **内存**：形状位图 192×192×4 ≈ 147KB/个，扫完一份完整索引（一两百个应用）约 20–30MB。
     *   这不是新增的峰值 —— 用户完整划过一遍字母条，现有缓存也会攒下同一批对象
     *   （[CACHE_MAX] 512 是原有的上限）；这里只是把它从「跟着手指一路上分配」
     *   改成「后台一次做完」。
     * - **时机**：由调用方在清单就绪后**延迟几秒**再叫（开机时 SystemUI 本来就忙），
     *   并且本函数自己带「同一代只扫一次」的闸门。
     * - **失败**：整条循环里任何异常都只影响那一个应用（退回自带图标），绝不逃逸 ——
     *   后台线程上抛出去同样会带走 SystemUI。
     */
    fun prewarmIcons(context: Context, packPkg: String?, apps: List<AppEntry>) {
        val packName = packPkg?.trim().orEmpty()
        if (packName.isEmpty() || apps.isEmpty()) return
        val app = context.applicationContext ?: context
        val sign = packName + "#" + apps.size + "#" + (apps.firstOrNull()?.packageName ?: "")
        synchronized(lock) {
            if (swept == sign) return
            swept = sign
        }
        val thread = Thread({
            try {
                // 与 [apply] 同一个入口（`IconPackResolver` 自己按包名缓存映射表）
                val pack = IconPackResolver.packFor(app, packName) ?: run {
                    Log.w(TAG, "图标包 $packName 解析失败，跳过预解析（面板会退回自带图标）")
                    return@Thread
                }
                var resolved = 0
                for (e in apps) {
                    val key = e.packageName + "/" + e.activityName
                    synchronized(lock) {
                        if (cachedPack != packName) {
                            cache.clear()
                            cachedPack = packName
                        }
                        if (cache.containsKey(key)) continue
                    }
                    // 与 [apply] 里逐字相同的一步：命中还是没命中都记进缓存
                    val d = try {
                        pack.drawable(e.packageName, e.activityName, e.icon)
                    } catch (t: Throwable) {
                        e.icon
                    }
                    val stored = if (d === e.icon) null else d
                    synchronized(lock) {
                        if (cachedPack == packName) {
                            if (cache.size >= CACHE_MAX) cache.clear()
                            cache[key] = stored
                        }
                    }
                    resolved++
                }
                Log.i(TAG, "图标包预解析完成：$packName，缓存 ${resolved} 个应用（主线程换字母时直接命中）")
            } catch (t: Throwable) {
                // 后台线程上抛出去同样会带走进程（SystemUI），必须自己吃掉
                Log.w(TAG, "图标包预解析失败（$packName），换字母时按需解析（性能差但功能不受影响）", t)
            }
        }, "fp-side-index-icons")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }
}
