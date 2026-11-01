package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.icu.text.Transliterator
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.IndexReuse
import io.github.kotoriinsky.tsuzuri.core.IndexAppFilter

/**
 * 一个可启动入口。
 *
 * [icon] 是**后台构建时预取**的（见 [AppIndex.build]）：面板在主线程上拼，
 * 每换一个字母才去 `PackageManager.getActivityIcon` 就是每次十几个跨进程调用，
 * 那是主线程上的卡顿来源。
 *
 * 代价是这些 Drawable 会在特性存活期间一直被持有（一百来个自适应图标，
 * 它们本身是矢量/路径数据、不含预渲染位图，量级在百 KB）。Appindex 也是这么做的。
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable? = null,
)

/**
 * 应用索引：扫出可启动入口，按**拼音首字母**分组。
 *
 * 分组键照 Appindex（`java/com/appindex/PinyinUtils.java` +
 * `AppListAdapter.buildSnapshot()`）：首字母（[AppIndex.firstLetter]）、
 * 数字与符号统一归 `#`、`#` 固定最后一格。
 *
 * ## 组内排序：照 Flyme `SlideAppsSorter.AppComparator`
 *
 * Flyme 的比较器是**两级**：
 *
 * ```java
 * int countFromItemInfo = getCountFromItemInfo(itemInfo2) - getCountFromItemInfo(itemInfo);
 * if (countFromItemInfo != 0) return countFromItemInfo;      // 第一级：打开次数，多的在前
 * ... 逐字符比较（getLetterUpperCase：空格跳过、非字母映射成 'N'）  // 第二级
 * ```
 *
 * **第一级我们做不到**（如实说，不编）：那个次数来自
 * `BgDataModel.flymeDataModel.appOpeningTimes` —— 桌面进程自己的内存表
 * （由 `AppRecordsUpdateTask` 维护）。我们在 SystemUI 里，读不到桌面的数据模型；
 * 换用 `UsageStatsManager` 要「使用情况访问」权限，失败的样子是「排序静默不对」，
 * 正是本项目最忌讳的那类问题（同样的理由见 [SideIndex] 里 RecentApps 的注释）。
 *
 * **第二级照抄了**：见 [sortKey] —— 汉字映射成拼音首字母、空格跳过、
 * 其它非字母映射成 `'N'`。所以「爱奇艺」和「Amazon」在 A 组里的先后与 Flyme 一致
 * （Flyme 拿 'Q' 比 'M'，而不是拿整串拼音比）。
 *
 * ## 关于「最近使用」
 *
 * Flyme 的第一级排序我们给不了，但把「最近用过的应用」放在最前面这件事
 * 由 [Index.withSpecial] 的 `★` 槽覆盖（那一路照的是 Appindex）。
 *
 * ## 关于「收藏项」
 *
 * Flyme 原版**没有**收藏项，这是我们的自建扩展（同 `★`）。它同样是
 * [Index.withSpecial] 的一个特殊槽位，数据来自用户在设置里排的应用清单
 * （`PrefSpec.OrderedList`，**顺序有意义**）。
 */
object AppIndex {

    private const val TAG = "FP-SideIndex"

    /** 「最近使用」伪字母槽。它贴在字母条最前面，不是 `#`（`#` 是数字/符号）。 */
    const val RECENT_SLOT = "★"

    /**
     * 「收藏项」伪字母槽。
     *
     * 用 `♥` 而不是 `☆`/`★`：`★` 已经被「最近使用」占了，两个槽位必须是**不同**的字形，
     * 否则字母条上会出现两个看起来一模一样的格子。真机在气泡里画的是图标
     * （[SideIndexOverlay.slotIcon]），这个字形只在图标取不到 / `bubble_icon_slots`
     * 关着时兜底显示。
     *
     * 契约层（`core/SideIndexPreview.kt`）有一份同样的字符串，两边必须一致 ——
     * 两个进程不共用代码，但语义必须一致（同 `LetterIndex` 顶部那条注记）。
     */
    const val FAVORITE_SLOT = "♥"

    /** 真实字母槽的顺序，`#` 固定最后 —— 和 Appindex 的 `letters` 数组一致。 */
    val ALPHABET: List<String> = (('A'..'Z').map { it.toString() }) + "#"

    /**
     * 一次构建的完整快照。
     *
     * **不可变**，这条是硬约束：它由主线程构建、发布（`@Volatile` 引用），
     * 输入分发线程在 `ACTION_DOWN` 直接读它来做命中判定。可变对象跨线程读就是数据竞争。
     */
    data class Index(
        /** 字母条上的槽位，顺序即显示顺序。 */
        val letters: List<String>,
        /** 槽位 → 该槽位下的应用。 */
        val groups: Map<String, List<AppEntry>>,
        /** 包名 → 一个可启动入口，供「最近使用」把前台包名还原成入口。 */
        val byPackage: Map<String, AppEntry>,
    ) {
        /**
         * 「包/组件」→ 入口。**只给重建时的复用用**（[build] 的 `reuse` 参数）。
         *
         * 键带组件名而不是只用包名：一个包可能有多个启动入口，按包名做键会把它们混成一个。
         *
         * eager 而不是 lazy：它只在后台重建线程上被读，而 lazy 的同步开销在
         * 「每次重建读一遍全表」这个用法下得不偿失。
         */
        val byComponent: Map<String, AppEntry> = buildMap(groups.values.sumOf { it.size }) {
            for (apps in groups.values) {
                for (e in apps) put(IndexReuse.componentKey(e.packageName, e.activityName), e)
            }
        }

        fun apps(slot: String): List<AppEntry> = groups[slot] ?: emptyList()

        /**
         * 把包名清单换成可启动入口，**丢掉不在清单里的包**。
         *
         * 前台轮询可能拿到桌面自己、负一屏、背屏这类没有启动入口的组件；
         * 收藏项里也可能留着已经被卸载的应用（配置不会自动清）。两类都不该出现在面板上。
         *
         * 数组级去重（`contains`）：一个包在 [byPackage] 里只对应一个入口，
         * 所以去重等价于「按包名去重」，不会误伤「一个包有多个启动入口」那种情况
         * （那种情况下 [byPackage] 本来就只留了第一个，见 [build]）。
         */
        private fun entriesFor(packages: List<String>): List<AppEntry> {
            if (packages.isEmpty()) return emptyList()
            val out = ArrayList<AppEntry>(packages.size)
            for (p in packages) {
                val entry = byPackage[p] ?: continue
                if (!out.contains(entry)) out.add(entry)
            }
            return out
        }

        /**
         * 产出一个带**特殊槽位**（收藏项 + 最近使用）的新快照（纯内存操作，主线程调）。
         *
         * [recent] / [favorite] 都是包名，最新的/最靠前的在前。
         *
         * ## 槽位顺序：收藏 → 最近 → A–Z → `#`
         *
         * 两个特殊槽都贴在字母条最前面（`#` 仍然固定在最后一格，那条规则没有变）。
         * 收藏排在最前，因为它是用户**明确指定**的那一组，比自动采样出来的「最近」
         * 更值得占据第一格；而且它稳定 —— 最近槽的内容会随着用户切应用而变，
         * 把第一格留给会自己变的列表会让每次下滑都要重新找。
         *
         * 某一组为空时**不加那个槽**（空清单 = 用户没配过 =
         * 不显示这个槽，见 [SideIndex] 里那一段说明）。两个都空 = 与改动前逐位一致。
         *
         * ## 两个槽**允许重叠**
         *
         * 一个被收藏、又刚用过的应用会在两个槽里各出现一次。不做「收藏里有的就从最近里
         * 去掉」那种排斥：那会让「最近使用」这个名字变成假的（明明刚用过却不在里面），
         * 而且加一个收藏会**悄悄**移走最近里的某一项 —— 用户看不出来是被谁拿走的。
         * 列表项在不同分组里重复出现是所有列表的常态，不需要为此做特例。
         */
        fun withSpecial(recent: List<String>, favorite: List<String>): Index {
            val favoriteApps = entriesFor(favorite)
            val recentApps = entriesFor(recent)

            val letters = ArrayList<String>(this.letters.size + 2)
            val groups = LinkedHashMap<String, List<AppEntry>>(this.groups.size + 2)
            // 先按显示顺序放特殊槽（groups 的插入顺序与 letters 一致，便于对账）
            if (favoriteApps.isNotEmpty()) {
                letters.add(FAVORITE_SLOT)
                groups[FAVORITE_SLOT] = favoriteApps
            }
            if (recentApps.isNotEmpty()) {
                letters.add(RECENT_SLOT)
                groups[RECENT_SLOT] = recentApps
            }
            for (slot in this.letters) {
                // 传进来的快照理论上已经不含特殊槽（它们只由本函数加），但这条判断
                // 让「重复调用 withSpecial」不会攒出两个 ★：
                // 上一版的 `withRecents` 就靠它保证幂等。
                if (slot == RECENT_SLOT || slot == FAVORITE_SLOT) continue
                letters.add(slot)
                this.groups[slot]?.let { groups[slot] = it }
            }
            // 内容没变就返回自己，调用方可以用引用相等判断「要不要重画」
            if (letters == this.letters) return this
            return Index(letters, groups, byPackage)
        }

        /**
         * 按「索引里显示哪些应用」过滤一份快照（本批 2️⃣）。**纯内存**：不查包管理器、
         * 不读配置、不碰 View，所以它既能在后台重建索引时跑，也能在参数变化时跑。
         *
         * ## 为什么连 [byPackage] 一起过滤（这一步是必须的，不是顺手）
         *
         * 收藏项与最近使用的槽位是**按包名查表**得到条目的（[entriesFor] 查 [byPackage]）。
         * 只过滤 `groups` 的话，一个被用户排除掉的应用仍然会出现在收藏槽或 `★` 槽里 ——
         * 用户看到的就是「我明明排除了它，它还在」。
         *
         * 反过来说，这也是「一个被排除的应用不该出现在任何槽里」这条要求的**唯一**落点：
         * 两个槽的取数路径都经过 [byPackage]，没有第二条路。
         *
         * ## 过滤发生在这里 = 触摸路径上零成本
         *
         * 判定（[IndexAppFilter.keeps]）在 `core` 里是纯函数，而这里的循环只在
         * 「索引重建 / 过滤配置变化」时跑一遍；`onTouch` / `trackPointer` 读到的永远
         * 是一份不可变的、已经过滤好的快照。
         *
         * 一个包都没被丢掉时返回 `this`（引用相等），调用方据此跳过重画 ——
         * 默认配置（不过滤）下这条路径是**零成本**的，行为与改动前逐位一致。
         */
        fun filtered(mode: String?, packages: Collection<String>): Index {
            val normalized = IndexAppFilter.normalizePackages(packages)
            if (!IndexAppFilter.isActive(mode, normalized)) return this
            var dropped = false

            val groups = LinkedHashMap<String, List<AppEntry>>(this.groups.size)
            for ((slot, apps) in this.groups) {
                val kept = ArrayList<AppEntry>(apps.size)
                for (e in apps) {
                    if (IndexAppFilter.keeps(mode, normalized, e.packageName)) {
                        kept.add(e)
                    } else {
                        dropped = true
                    }
                }
                // 整组被滤空的字母不再出现在字母条上（条上留一个点不开的字母是纯噪音）
                if (kept.isNotEmpty()) groups[slot] = kept
            }
            if (!dropped) return this

            val byPackage = HashMap<String, AppEntry>(this.byPackage.size)
            for ((pkg, e) in this.byPackage) {
                if (IndexAppFilter.keeps(mode, normalized, pkg)) byPackage[pkg] = e
            }
            return Index(ArrayList(groups.keys), groups, byPackage)
        }
    }

    /**
     * 构建结果。**三态是必须的**：
     *
     *  - [Loading]：「还没扫完」。此时面板既不能报「空」也不能报「就绪」——
     *    真机上 PackageManager 首次查询要几十到几百毫秒，这段窗口是真实存在的。
     *  - [Empty]：「扫完了，确实一个可启动应用都没有」。
     *  - [Unavailable]：「查询本身失败了」。和 [Empty] 必须分开：一个是「没有」，
     *    一个是「不知道」。上一版把两者都变成 `emptyList()`，上层就无法区分。
     *  - [Ready]：就绪。
     */
    sealed interface Result {
        data object Loading : Result
        data object Empty : Result
        data class Unavailable(val reason: String) : Result
        data class Ready(val index: Index) : Result
    }

    /**
     * 扫出所有带启动入口的应用并分组。
     *
     * **只允许在后台线程调**：`queryIntentActivities` 之后每个应用还要 `loadLabel` /
     * `loadIcon` 各一次跨进程调用，一百来个应用就是两百多次 IPC。
     * 唯一会调它的地方是 [SideIndex] 里那个单线程 executor。
     *
     * 任何失败都变成 [Result.Unavailable]，绝不抛出去。
     */
    /**
     * @param reuse 上一次构建结果。**不变的那些项会连图标一起搬过来**，跳过
     *   `loadLabel` + `loadIcon` 两次 IPC（后者还要解码位图）。传入 null = 全量重扫。
     * @param invalidation 哪些包的项不能复用（见 [IndexReuse]）。默认全失效 = 全量重扫。
     * @param log 耗时埋点。**没有数字就没法说「快」或「慢」**，所以重建路径必须自己报账。
     */
    fun build(
        context: Context,
        reuse: Index? = null,
        invalidation: IndexReuse.Invalidation = IndexReuse.Invalidation.ALL,
        log: (String) -> Unit = {},
    ): Result {
        val tStart = System.nanoTime()
        val pm = context.packageManager ?: return Result.Unavailable("no package manager")

        val resolved = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
        } catch (t: Throwable) {
            return Result.Unavailable("query: " + t.javaClass.simpleName)
        }

        val tQuery = System.nanoTime()
        val byLetter = HashMap<String, MutableList<AppEntry>>(32)
        val byPackage = HashMap<String, AppEntry>(resolved.size * 2)
        val prev = reuse?.byComponent
        var reused = 0
        var reloaded = 0
        for (r in resolved) {
            val ai = r.activityInfo ?: continue
            // ── 复用：同一个「包/组件」的项，连**已经加载好的图标**一起搬过来 ──
            // 判据只看包/组件（[IndexReuse] 顶部解释了为什么不看名字）：
            // 名字变了必然意味着包被改过，那时广播会把包名放进失效集，走不到这一支。
            val cached = if (prev != null && invalidation.canReuse(ai.packageName)) {
                prev[IndexReuse.componentKey(ai.packageName, ai.name)]
            } else {
                null
            }
            val entry: AppEntry
            if (cached != null) {
                entry = cached
                reused++
            } else {
                // 单个应用读不出名字/图标不该毁掉整次构建，跳过它就行
                val label = try {
                    r.loadLabel(pm).toString()
                } catch (_: Throwable) {
                    ""
                }
                if (label.isEmpty()) continue
                val icon = try {
                    r.loadIcon(pm)
                } catch (_: Throwable) {
                    null
                }
                entry = AppEntry(label, ai.packageName, ai.name, icon)
                reloaded++
            }
            val label = entry.label
            val letter = firstLetter(label)
            byLetter.getOrPut(letter) { ArrayList(4) }.add(entry)
            // 一个包可能有多个启动入口（设置、设置快捷方式…）。Appindex 不去重，
            // 我们也不去重（保持和参考一致）；「最近使用」按包名取第一个。
            byPackage.putIfAbsent(ai.packageName, entry)
        }

        val tNames = System.nanoTime()
        val groups = LinkedHashMap<String, List<AppEntry>>(byLetter.size * 2)
        for (letter in ALPHABET) {
            val apps = byLetter[letter] ?: continue
            if (apps.isEmpty()) continue
            // 排序键先算好再排（比较器里现算会为每次比较分配字符串）
            val keyed = ArrayList<Pair<AppEntry, String>>(apps.size)
            for (e in apps) keyed.add(e to sortKey(e.label))
            keyed.sortWith(compareBy { it.second })
            groups[letter] = keyed.map { it.first }
        }

        log(
            "索引重建：" + resolved.size + " 个入口（复用 " + reused + " / 重载 " + reloaded + "）耗时 " +
                ms(tStart, System.nanoTime()) + "ms" +
                "（查询 " + ms(tStart, tQuery) + " / 名称与图标 " + ms(tQuery, tNames) +
                " / 分组排序 " + ms(tNames, System.nanoTime()) + "）",
        )
        if (groups.isEmpty()) return Result.Empty
        return Result.Ready(
            Index(
                letters = ArrayList(groups.keys),
                groups = groups,
                byPackage = byPackage,
            )
        )
    }

    /**
     * 组内排序键。逐行对应 Flyme `SlideAppsSorter.AppComparator.getLetterUpperCase`：
     *
     * ```java
     * private static final char ORDERED_SHARP = 'N';       // 非法字符统一映射成 'N'
     * char c = MzAlphabetUtils.characterToAlphabet(charSequence.charAt(i));   // 汉字 → 拼音首字母
     * return !isLegalLetterCharacter(c) ? ORDERED_SHARP : c;
     * // 主循环里：字符是空格就跳过（i++ 之后不比较）
     * ```
     *
     * 于是「爱奇艺」的键是 `AQY`、「Amazon」是 `AMAZON` —— 逐字符比，
     * 所以 A 组里 'M' < 'Q'，Amazon 在前。这和「整串拼音比」的结果**不一样**
     * （整串比是 `AIQIYI` vs `AMAZON`，'I' < 'M'），所以必须按字符映射，
     * 不能图省事去 transliterate 整串。
     *
     * 映射规则：
     *  - 空格 → 跳过（两个键逐字符对齐，和 Flyme 的 `z` 标志同义）
     *  - `A-Z`/`a-z` → 大写字母
     *  - 其它 ASCII（数字、标点） → `'N'`
     *  - 非 ASCII → 拼音首字母（[firstLetterByIcu]，和分组用的是同一套转换器）；
     *    拿不到就 `'N'`
     *
     * **只在后台构建线程调**：每个汉字一次 ICU 转换（一百多个应用、几百次调用，
     * 全在 [build] 的那个单线程 executor 上，不碰主线程）。
     */
    private fun sortKey(label: String): String {
        val sb = StringBuilder(label.length + 4)
        for (ch in label) {
            if (ch == ' ') continue
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                sb.append(ch.uppercaseChar())
                continue
            }
            if (ch.code < 128) {
                sb.append(ORDERED_SHARP)
                continue
            }
            val letter = firstLetterByIcu(ch.code)
            sb.append(if (letter.length == 1 && letter[0] != '#') letter[0] else ORDERED_SHARP)
        }
        return sb.toString()
    }

    /** Flyme `SlideAppsSorter.AppComparator.ORDERED_SHARP`。 */
    private const val ORDERED_SHARP = 'N'

    /** 方便诊断页/日志用的一句人话。 */
    fun describe(result: Result): String = when (result) {        is Result.Loading -> "loading"
        is Result.Empty -> "empty"
        is Result.Unavailable -> "unavailable(" + result.reason + ")"
        is Result.Ready -> "ready(slots=" + result.index.letters.size +
            ", apps=" + result.index.byPackage.size + ")"
    }

    /**
     * 把手指的纵向位置映射成第几个槽位。
     *
     * 这是**照搬 Appindex `FloatingService.calculateSelectedLetter` 的命中算法**，
     * 原文就三行：
     *
     * ```java
     * float relativeY = y - barTop;
     * int index = (int) (relativeY / this.letterItemHeightPx);
     * String letter = this.letters[Math.max(0, Math.min(index, this.letters.length - 1))];
     * ```
     *
     * 即「线性分格 + 两端夹紧」：每个槽位等高，索引 = 偏移量整除格高，O(1)、
     * 不依赖任何视图测量结果、不需要遍历。我们只把 `Math.max/min` 换成 `coerceIn`。
     *
     * 它是**纯函数**的原因很重要：判定跑在输入分发线程上，不能去读
     * `getLocationOnScreen()`（那是跨线程读 View 状态）也不能测高，
     * 所以条的几何必须在手势之外算好（见 [BarLayout]）。
     */
    fun letterAt(y: Float, top: Float, itemHeight: Float, count: Int): Int {
        if (count <= 0 || itemHeight <= 0f) return 0
        val idx = ((y - top) / itemHeight).toInt()
        return idx.coerceIn(0, count - 1)
    }

    // ── 拼音首字母 ─────────────────────────────────────────────────────

    /**
     * ICU 的汉字转拼音。
     *
     * 这一串 ID 是**直接从 Appindex 抄的**（`PinyinUtils.init` 里的
     * `Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")`）：
     * `Han-Latin` 做汉字→拼音，`/Names` 用姓名读音规则处理多音字的常见歧义，
     * `Latin-ASCII` 把带声调的拉丁字母折成 ASCII（`Ǎ` → `A`）。
     *
     * 后面两级是降级链：某些 ROM 的 ICU 数据被裁剪过，不一定有 `/Names` 变体。
     * 拿不到就退到下一级，全拿不到就所有汉字归 `#`（和 Appindex 的
     * `getFirstLetterByIcu` 返回 `#` 是同一个行为）。
     */
    private val TRANSLITERATOR_IDS = listOf(
        "Han-Latin/Names; Latin-ASCII",
        "Han-Latin; Latin-ASCII",
        "Han-Latin",
    )

    /**
     * 缓存的 transliterator。
     *
     * 构建一次要解析上百条规则，是毫秒级开销，绝不能每个应用名建一个
     * （Appindex 也是 `once`：`if (hanToLatin == null)`）。
     * ICU 的 `Transliterator` **不是线程安全的**（内部有游标状态），
     * 所以下面用锁把它圈起来；调用点只有后台构建那一条路径，锁竞争为零。
     */
    @Volatile
    private var hanToLatin: Transliterator? = null

    /** 一次性诊断标记：平台一个可用的汉字转换器都没有时，只提示一次。 */
    @Volatile
    private var warnedNoTransliterator = false

    private val transliteratorLock = Any()

    private fun transliterator(): Transliterator? {
        hanToLatin?.let { return it }
        synchronized(transliteratorLock) {
            hanToLatin?.let { return it }
            for (id in TRANSLITERATOR_IDS) {
                val t = try {
                    Transliterator.getInstance(id)
                } catch (_: Throwable) {
                    null
                }
                if (t != null) {
                    hanToLatin = t
                    return t
                }
            }
            // 一个都没有：所有汉字都会归 `#`。
            // 这在真机上必须**看得见**，否则只会表现为「中文应用全挤在 # 里」，
            // 而那种现象很容易被当成「拼音就是没做」而不是「ICU 数据没有这个转换器」。
            if (!warnedNoTransliterator) {
                warnedNoTransliterator = true
                Log.w(TAG, "平台没有可用的汉字转拼音转换器（已试 " + TRANSLITERATOR_IDS +
                    "），汉字将归入 #。平台提供的转换器 ID：" + availableTransliterators())
            }
            return null
        }
    }

    /** 平台实际提供了哪些转换器。签名是 `Enumeration<String>`（从 android.jar 里确认过）。 */
    private fun availableTransliterators(): String = try {
        Transliterator.getAvailableIDs().asSequence().joinToString(",")
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 取首字母。逐行对应 Appindex 的 `PinyinUtils.getFirstLetter`：
     *
     * ```java
     * if ((firstChar >= 'A' && firstChar <= 'Z') || (firstChar >= 'a' && firstChar <= 'z'))
     *     return String.valueOf(Character.toUpperCase(firstChar));
     * if (Character.isDigit(firstChar) || firstChar < 128) return "#";
     * String icuLetter = getFirstLetterByIcu(firstCodePoint);
     * return !"#".equals(icuLetter) ? icuLetter : getFirstLetterByTable(firstCodePoint);
     * ```
     *
     * **改了一处**：Appindex 最后还有一层 `pinyin_table.bin` 兜底（assets 里那个
     * 20902 字的表）。我们不打包那张表 —— 它在 Appindex 自己那边也是可选资产
     * （`PinyinUtils.init` 是 try/catch 的失败路径），而魅族那张表只覆盖
     * U+4E00–U+9FA5；ICU 拿不到就归 `#`，这是 Appindex 在表缺失时的实际行为。
     *
     * 注意只按**第一个 code point** 判：emoji 开头的应用名（代理对）走 ICU 也是 `#`，
     * 不会把代理对拆坏。
     */
    internal fun firstLetter(name: String): String {
        if (name.isEmpty()) return "#"
        val first = name[0]
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()
        if (first.isDigit() || first.code < 128) return "#"
        return firstLetterByIcu(name.codePointAt(0))
    }

    private fun firstLetterByIcu(codePoint: Int): String {
        val t = transliterator() ?: return "#"
        return try {
            val source = String(Character.toChars(codePoint))
            val out = synchronized(transliteratorLock) { t.transliterate(source) }
            for (c in out) {
                if (c in 'A'..'Z' || c in 'a'..'z') return c.uppercaseChar().toString()
            }
            "#"
        } catch (_: Throwable) {
            "#"
        }
    }

    // ── 条上的几何 ─────────────────────────────────────────────────────

    /**
     * 字母条离屏幕边缘的距离。
     *
     * Flyme 的 `ASLetterBarView` 不设外边距（它由布局规则贴在起始边），
     * 字母的横向位置是 `mPadding + 字号/2`（见 [SideIndexOverlay] 里的绘制），
     * 所以这里留 0，和它的实际效果一致。
     */
    private const val BAR_MARGIN_DP = 0

    /**
     * 单格高度，取自 Flyme `ASLetterBarView.setupViewSpecs()`：
     * `mLetterHeight = dpToPx(16) + 1`（那个 +1 是它的原样，照抄）。
     *
     * （Appindex 这一项是可调的 14–28dp，默认 22dp；形态以 Flyme 为准。
     * 我们把它做成一个**整体缩放**系数，见 [barLayout] 的 `scale`。）
     */
    private const val BAR_ITEM_DP = 16

    /** 字母字号，Flyme `ASLetterBarView.initialize`：`mLetterPaint.setTextSize(dpToPx(12))`。 */
    private const val BAR_TEXT_DP = 12

    /**
     * 字号相对格高的上限（0.8）。
     *
     * 两个来源都指向同一个比例，所以这个数不是我们编的：
     *  - Appindex `FloatingService.highlightLetter`：
     *    `Math.min(normalSizePx * 1.4f, letterItemHeightPx * 0.8f)` —— 把放大后的字号
     *    夹到格高的 0.8 倍，正是为了不让字溢出格子；
     *  - Flyme `ASLetterBarView.drawTextInCenter` 也在夹 `fontSpacing`。
     *
     * 因为 12 / 16 = 0.75 < 0.8，**缩放为 1 时这一条不生效**（字号就是 12dp，和现在一致），
     * 只有用户把 [BAR_ITEM_DP] 那一档拉大时才靠它兜底。
     */
    private const val BAR_TEXT_MAX_RATIO = 0.8f

    /**
     * 条的宽度。
     *
     * 注意它**只用于命中判定**（[BarLayout.inBarColumn] 的列宽），不是画出来的宽度：
     * Flyme 的命中用的是 `ASLetterBarView` 自身的 `getGlobalVisibleRect`，那个宽度来自
     * 它看不见的 layout xml；我们保留原来的宽容列宽（28dp），避免把「手指还在不在条上」
     * 变得比参考项目更苛刻。**画出来的**字母位置另外对齐（见 SideIndexOverlay）。
     */
    private const val BAR_WIDTH_DP = 28

    /**
     * 条最高占屏幕的比例。
     *
     * Flyme 没有这条：它把条**居中**摆（`mLetterPaddingTop = (屏幕高 - 条高)/2`），
     * 字母多、屏幕矮时同样会顶出屏幕（Appindex 干脆把 maxOffset 夹到 ≥0）。
     * 我们换成「整条等比缩小」——比隔行隐藏简单，也不丢字母。
     * 27 格 × 17dp ≈ 459dp，正常手机屏幕（约 800dp 高）根本触发不到这一条。
     */
    private const val BAR_MAX_HEIGHT_FRACTION = 0.82f

    /**
     * 字母条在屏幕上的位置 —— 判定与绘制**共用这一份**。
     *
     * 单独抽出来是因为：命中算法（[letterAt]）和绘制必须用**同一组数字**，
     * 一旦各算各的就会出现「看到的字母」和「碰到的字母」对不上。
     * 而输入线程又不能去测 View 的高度，所以这里把几何提前算成不可变数据。
     */
    internal data class BarLayout(
        val letters: List<String>,
        val itemHeight: Int,
        val barTop: Int,
        val barWidth: Int,
        val barMargin: Int,
        val screenW: Int,
        val screenH: Int,
        /** 画字母用的字号（像素）。缩放后的值，见 [barLayout]。 */
        val letterTextPx: Int,
    ) {
        val count: Int get() = letters.size

        /** 条的左边（按这一侧算）。贴哪边由手势的起始边决定，所以不进构造函数。 */
        fun barLeft(onRight: Boolean): Int =
            if (onRight) screenW - barMargin - barWidth else barMargin

        fun indexAt(y: Float): Int =
            letterAt(y, barTop.toFloat(), itemHeight.toFloat(), count)

        /**
         * 手指横向是否还在条这一列里。
         *
         * **换字母只认这个判据（只判 x，不判 y）**：纵向位置是选择量，
         * 所以纵向跑出条的上下端也仍然按两端夹紧处理（和 `indexAt` 的夹紧一致）；
         * 但横向一旦离开条这一列（跑到面板上），就**必须停止换字母** ——
         * 否则手指在图标网格里上下移动会不停重建面板，字母也跟着乱跳。
         * Appindex 的 MOVE 分支就是先 `isPointInLetterIndex` 再决定要不要重算字母。
         */
        fun inBarColumn(x: Float, onRight: Boolean): Boolean {
            val left = barLeft(onRight)
            return x >= left && x <= left + barWidth
        }

        /** 手指是否落在字母条上。Appindex 用 `isPointInLetterIndex` 做同一件事。 */
        fun inBar(x: Float, y: Float, onRight: Boolean): Boolean {
            if (count <= 0) return false
            return inBarColumn(x, onRight) &&
                y >= barTop && y <= barTop + itemHeight * count
        }
    }

    /**
     * 算出条的几何。**主线程**调（要读 displayMetrics），结果发布出去给输入线程读。
     *
     * 两个个性化参数来自 Appindex 的 `letter_prefs`（**设置项以 Appindex 为准**）：
     *
     * ```java
     * // LetterIndexSettingsActivity → FloatingService.createLetterButtons
     * float verticalOffset = prefs.getFloat("letter_vertical_offset", 0.75f);
     * int sizeProgress     = prefs.getInt("letter_size_progress", 14);
     * int itemHeightDp = (int) (sizeProgress / 100.0f * 14.0f + 14.0f);   // 14–28dp
     * int fontSizeSp   = (int) (sizeProgress / 100.0f *  5.0f +  9.0f);   //  9–14sp
     * lp.y / translationY = (screenHeight - 总高) * verticalOffset;       // 0 = 贴顶
     * ```
     *
     * **默认值取 Flyme 的形态**（形态冲突以 Flyme 为准）：
     *  - `offsetFraction = 0.5` —— Flyme 把条**垂直居中**
     *    （`mLetterPaddingTop = (mScreenHeight - 条高)/2`）。Appindex 默认 0.75（偏下）。
     *  - `scale = 1` —— Flyme 原尺寸，即 16dp+1 一格的 12dp 字。
     *    Appindex 的默认（`sizeProgress = 14`）折出来是 15dp 格高 / 9sp 字，
     *    那是**另一组比例**，不是同一形态；我们用一个缩放系数表达同一种意图
     *    （整体放大缩小），而不是把 Flyme 的比例丢掉。
     *
     * 刻度换算：`scale` 直接乘在格高和字号上（两个都乘 = 「整体大小」），
     * 再按 [BAR_TEXT_MAX_RATIO] 给字号封顶，避免放大后字溢出格子。
     */
    internal fun barLayout(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        letters: List<String>,
        /** 字母条纵向位置，`[0,1]` 是「条可放置区域」的比例：0 贴顶、0.5 居中、1 贴底。 */
        offsetFraction: Float = 0.5f,
        /** 整体缩放，1 = Flyme 原尺寸（见上）。 */
        scale: Float = 1f,
    ): BarLayout {
        val width = (BAR_WIDTH_DP * density).toInt()
        val margin = (BAR_MARGIN_DP * density).toInt()
        val count = letters.size
        val safeScale = scale.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE)

        // Flyme: mLetterHeight = dpToPx(16) + 1
        val base = ((BAR_ITEM_DP * safeScale * density).toInt() + 1).coerceAtLeast(1)
        val maxTotal = (heightPx * BAR_MAX_HEIGHT_FRACTION).toInt()
        val item = if (count > 0 && base * count > maxTotal) {
            (maxTotal / count).coerceAtLeast(1)
        } else {
            base
        }
        val total = item * count
        // Flyme 是固定居中（0.5），这里把它做成可调：0.5 的取值与原来的
        // `(heightPx - total) / 2` 完全相等（非负整数下整除即乘以 0.5 取整）
        val span = (heightPx - total).coerceAtLeast(0)
        val top = (span * offsetFraction.coerceIn(0f, 1f)).toInt().coerceIn(0, span)

        // 字号：Flyme 固定 12dp；缩放后再按格高的 0.8 封顶（见 BAR_TEXT_MAX_RATIO）
        val textPx = minOf(
            (BAR_TEXT_DP * safeScale * density).toInt(),
            (item * BAR_TEXT_MAX_RATIO).toInt(),
        ).coerceAtLeast(1)

        return BarLayout(
            letters = letters,
            itemHeight = item,
            barTop = top,
            barWidth = width,
            barMargin = margin,
            screenW = widthPx,
            screenH = heightPx,
            letterTextPx = textPx,
        )
    }

    /** 缩放的可用区间。下限保证字还看得见，上限保证它还在 Flyme 那套比例的量级里。 */
    internal const val MIN_BAR_SCALE = 0.6f
    internal const val MAX_BAR_SCALE = 1.8f
}

/** 两个 `System.nanoTime()` 之间隔了多少毫秒（只用于耗时埋点）。 */
private fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000
