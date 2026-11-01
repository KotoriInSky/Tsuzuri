package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import io.github.kotoriinsky.tsuzuri.core.IndexAppFilter
import io.github.kotoriinsky.tsuzuri.core.LauncherScope

/**
 * 侧滑索引的**全部参数**，一次读齐（[SideIndex.readConfig]）。
 *
 * ## 为什么要有这个类
 *
 * 在这一版之前，参数是在 [SideIndex.install] 里逐个读成**局部 `val`** 的，而那些 `val`
 * 被 [SideIndexOverlay] / [SideIndexHandle] / [SideIndexDetector] 捕获 —— 「读一次」的代价是
 * **改完任何一项都得重启 SystemUI**。而设置界面上是即时滑杆，用户拖完看不到变化，
 * 会当成「这个参数没用」。这条判断不是我们推断的，是本项目已有的同一个结论：
 * 见 [io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.CornerSmallWindow.watchPrefs] 的注释
 * （它就是因为同一个缺陷才加了那条监听）。
 *
 * 现在参数只有一个来源（本类），两条路径读的是**同一份逻辑**：
 *
 *  - **安装**：[SideIndex.install] 建对象时读一次；
 *  - **热应用**：[SideIndex.watchPrefs] 收到 `side_index.*` 的变化后重读一遍，
 *    再按 [windowDiffersFrom] / [handleDiffersFrom] 决定动哪一部分 ——
 *    只动便宜的那一份，**绝不重建应用索引**（那是 loadLabel/loadIcon 上百个包）。
 *
 * 两份读法（安装读一遍、热应用再各读一遍）必然会漂移，漂移的表现是
 * 「装的时候是一套、拖动滑杆之后是另一套」，所以只允许 [SideIndex.readConfig] 一处读 prefs。
 *
 * ## 为什么放在 hook 侧而不是 `core/`
 *
 * 它引用 [SideIndexLook]（hook 侧的外观模型）。`core/` 是前后端共享的**契约层**，
 * 不许出现 hook 的实现细节 —— 见 `core/build.gradle.kts` 顶部那条「不允许 android.* 与 Compose」
 * 的注释（那条边界是靠 Gradle 挡的，不靠自觉）。
 *
 * ## 字段一律不含 px
 *
 * `density` 与 `touchSlopPx` 来自系统（不是我们的参数），由调用方在需要 px 时传进
 * [trigger]。这样本类不碰 `Resources` / `ViewConfiguration`，比对（[windowDiffersFrom]）
 * 与描述（[describe]）都不依赖任何系统状态。
 *
 * ## 顺带回答一个必然会有人问的问题：`touchSlopPx` 为什么不跟着参数变
 *
 * 它来自 `ViewConfiguration.get(ctx).scaledTouchSlop`，是**系统**按 density 算出来的常数，
 * 与 `side_index.*` 任何一个键都无关。所以它在本类里只是「触发几何快照的一部分」，
 * 值每次读都一样；[SideIndexDetector] 因此可以把它当成一段手势内的常量用，
 * 不需要每次 DOWN 重新去要一次 ViewConfiguration（输入分发线程上也不该去碰 Resources）。
 */
internal data class SideIndexConfig(
    /** `trigger_width`，dp。它同时是**判定带宽**和把手宽度，所以两者永远一样宽。 */
    val triggerWidthDp: Float,
    /** `side`：`right` / `left` / `both`。见 [allowLeft] / [allowRight]。 */
    val side: String,
    /** `show_recent`：索引里那个 ★ 槽要不要参与。 */
    val showRecent: Boolean,
    /** `show_handle`：边缘那个常驻提示条要不要建。 */
    val showHandle: Boolean,
    /** `haptic`：振动总开关。 */
    val hapticEnabled: Boolean,
    /**
     * `haptic_mode` 的**原始取值**（`off` / `light` / `standard` / `strong`）。
     *
     * 容错读：键读不到时为 null，此时 [SideIndexHaptics.resolveMode] 会退到
     * [hapticStrengthPercent] 的老值迁移，行为不退化（见 [SideIndex.LookReader.choice]）。
     */
    val hapticModeKey: String?,
    /**
     * **已废弃，只作老值迁移输入。**
     *
     * 旧键 `haptic_strength`（百分比 → 振幅）已随参数面重构从 Catalog 删除 ——
     * 新实现走 Android 官方的三条路径（View 语义常量 / 预定义效果 / Composition 原语），
     * 而 `performHapticFeedback` 不吃振幅、预定义效果是固定波形，**没有对应的旋钮**。
     * 留着那个滑块只会变成「拖了没反应」，所以键与控件一起去掉。
     */
    val hapticStrengthPercent: Int,
    /**
     * **已废弃，刻意忽略。**
     *
     * 旧键 `haptic_duration`（毫秒）同 [hapticStrengthPercent] 的理由删除：
     * 三条官方路径里只有 Composition 的 `scale` 一个力度旋钮，而它**不吃时长**。
     */
    val hapticDurationMs: Int,
    /** `blur`：用户**要**模糊。真生效还要看系统支不支持（[SideIndexOverlay.crossWindowBlurEnabled]）。 */
    val blurRequested: Boolean,
    /** `scope`：`launcher_only` / `anywhere` / `exclude_launcher`（[SideIndexScope]）。 */
    val scope: String,
    /** 外观与触感（[SideIndexLook]）。 */
    val look: SideIndexLook,
    /**
     * `icon_pack`：**整包替换面板图标**（需求 #5）。
     *
     * 键名与写侧完全同源：`Keys.pref(side_index, "icon_pack")`（= `ui/state/ShortcutCatalog`
     * 的 `iconPackKey(featureKey)`），类型是「只放一个包名的 `StringSet`」
     * —— 空集 = 不套用。所以这个特性**不新造参数类型、也不新造键名约定**，
     * 图标包选择页（`IconPackPickerScreen`）往哪个键写，这里就读哪个键。
     *
     * `null` / 空 = 不套用（面板图标就是应用自带图标 = 改动前的形态）。
     *
     * ⚠️ 它**必须进** [windowDiffersFrom]：图标虽然是在 `bindCell` 那一刻按包名解析的，
     * 但**浮层对象是跨手势复用的**（`SideIndexOverlay` 只在换代时才换，`dismiss()`
     * 只拆窗口、不换对象）—— 不换代的话，用户改完图标包要等到下一次换代
     * （即改了别的外观参数）才生效，而那正是「改完看不到变化」那类缺陷。
     * 换代本身很便宜：一次对象构造 + 一次构造期读配置，**不重扫应用清单**。
     */
    val iconPack: String?,
    /**
     * `favorites`：**收藏项**的用户清单（包名列表，**顺序有意义**）。
     *
     * 键名与写侧同源：`Keys.pref(side_index, "favorites")`，类型是 `PrefSpec.OrderedList`
     * （一条一行、`\n` 连接的**单个字符串**，见 `OrderedItems`）。所以这个特性
     * **不新造参数类型**，界面侧（`FanItems` / `AppPickerScreen`）怎么写，这里就怎么读。
     *
     * ## 空清单 = **不显示这个槽**（不是「自动挑选」）
     *
     * `PrefSpec.AppList` / `OrderedList` 的 KDoc 写着「空集 = 用户没配过 → 消费侧走自动挑选」。
     * 收藏项**不套用那半句**，理由很具体：自动挑选对「收藏」没有合理的答案 ——
     * 唯一能自动凑出来的就是「最近使用」，而它已经是一个独立槽位（`★`），
     * 那样会变成两个内容相同的槽，用户分不清哪个是哪个。
     *
     * 「空 = 不显示」同时也是**关态要求**：默认（键不存在 / 空清单）下字母条与改动前
     * 逐位一致（只有 `★` 和 A–Z），不需要任何额外的开关来关掉它。
     *
     * ## 含 `/` 的条目会被跳过
     *
     * 界面侧的清单是「应用 + 快捷方式混排」（`ShortcutEntries`：应用 = 纯包名、
     * 快捷方式 = `包名/id`）。侧滑索引只能启动**应用**（它拿的是 `MAIN/LAUNCHER` 入口），
     * 所以快捷方式条目在这里没有落点。跳过的条数会进安装报告（[contentNotes]）——
     * 静默吞掉的表现是「用户在收藏里加了 3 个，面板上只有 2 个」，那是最难查的一类。
     */
    val favorites: List<String>,
    /**
     * `recent_count`：`★` 槽里显示几个应用（旧版硬编码 10）。
     *
     * 只影响**显示**：采样线程在内存与磁盘上最多保留 [MAX_RECENT_COUNT] 条
     * （= 本参数的滑杆上限），所以把它调大能立刻看到更多历史，调小只是少显示几格。
     * 那半句的理由见 `core/RecentList` 的类注释。
     */
    val recentCount: Int,
    /**
     * `launcher_packages`：**哪些包算桌面**（本批 1️⃣，`PrefSpec.AppList`）。
     *
     * ## 与 [scope] 的关系：它是「什么算桌面」，不是第四档
     *
     * [scope] 的三档语义一个字都没改（`launcher_only` / `anywhere` / `exclude_launcher`），
     * 变的是那个判据的来源。判定逻辑全在 `core/LauncherScope`（纯函数、有测试），
     * 本类只负责**把配置读成集合**，主/辅/退化的结论见那个对象的类注释。
     *
     * ## 空集 = 没配过 → 退回系统解析出来的桌面（= 改动前逐位一致）
     *
     * 这正是 [`PrefSpec.AppList`] 的 KDoc 那条契约的用法：空集不是「一个都不算桌面」，
     * 而是「用户没配过，消费侧走自动挑选」。这里的「自动挑选」= 那个 250ms 轮询读的
     * [SideIndexScope]（系统按 `ACTION_MAIN/CATEGORY_HOME` 解析出来的前台判定）。
     *
     * ## 为什么要它（真实缺陷）
     *
     * 那条自动判据只在**安装时解析一次**，用户之后换成第三方桌面它也不会更新 ——
     * 于是 `launcher_only` 整个失效。用户把 Niagara 勾进来之后就按 Niagara 判，
     * 不再看那个（可能已经过期的）解析结果。
     *
     * ## 它为什么不进 [windowDiffersFrom]
     *
     * `scope` 怎么改就生效，它就怎么改就生效：轮询每跑一次都要读一次判定，
     * 所以只要把生效集合换成新的就够了（[SideIndex.applyConfig] 里那一步），
     * 不需要换代浮层、也不需要重扫应用清单。
     */
    val launcherPackages: Set<String> = emptySet(),
    /**
     * `index_filter_mode` + `index_filter_packages` 归一化之后的**生效值**（本批 2️⃣）。
     *
     * 存的是 `core/IndexAppFilter` 归一化过的两个值，而不是原始字符串：
     * 归一化有两个消费者（过滤本身与安装报告里那句人话），各算一遍就会出现
     * 「报告说不过滤、实际过滤了」这种只有用户能发现的偏差。
     *
     * 默认（`all` + 空集）下 [SideIndex.publishIndex] 走的是**引用相等**的短路，
     * 一次都不重建快照 —— 与改动前逐位一致，且这条路径零成本。
     */
    val indexFilterMode: String = IndexAppFilter.MODE_ALL,
    val indexFilterPackages: Set<String> = emptySet(),
    /**
     * `name_overrides`：**单应用显示名覆盖**（本批 3️⃣ 前半），`<包名>` → 新名字。
     *
     * 键后缀与格式见 `core/DisplayNameOverrides`（与扇形那边的单项图标覆盖同一套约定：
     * 一条覆盖一个集合元素、第一个字段是目标键）。
     *
     * **进 [windowDiffersFrom]**：理由与 [iconPack] 逐字相同 —— 名字是在 `bindCell`
     * 那一刻填进 `TextView` 的，而浮层对象**跨手势复用**，不换代就会出现
     * 「改完名字要等下一次换代才生效」。
     */
    val nameOverrides: Map<String, String> = emptyMap(),
    /**
     * `icon_overrides`：**单应用图标覆盖**（本批 3️⃣ 后半）。
     *
     * 解析与缓存都在 [SideIndexIconOverride] 里（它同时负责把第一次解码挪到后台线程）。
     * 优先级链是「单项覆盖 → 整体图标包（[iconPack]）→ 自带图标」，
     * 落点在 [SideIndexOverlay.bindCell]。
     *
     * **进 [windowDiffersFrom]**：同 [iconPack]（浮层跨手势复用，不换代就看不到变化）。
     */
    val iconOverrides: SideIndexIconOverride.Set = SideIndexIconOverride.EMPTY,
    /**
     * `Catalog` 里**还没声明**的 look 键名（[SideIndex.LookReader] 的产出）。
     *
     * 只用于安装报告的 notes：缺项必须是**看得见**的，否则会出现「界面上没有这个滑杆、
     * 代码里却按默认值跑」这种没人能发现的偏差。
     */
    val lookNotes: List<String> = emptyList(),
    /**
     * **内容参数**（[favorites] / [recentCount]）的容错读说明，同上进安装报告。
     *
     * 与 [lookNotes] 分开而不是合成一条：两边的键在 `Catalog` 里由同一处声明，
     * 但「外观参数缺声明」和「清单参数缺声明」的排查方向完全不同，
     * 合成一行会让人去外观那一组里找一个其实是清单的键。
     */
    val contentNotes: List<String> = emptyList(),
) {

    /**
     * 允许左边缘起手。
     *
     * 判据与 [Catalog] 里 `side` 的三个选项一一对应：`right` → 只许右，`left` → 只许左，
     * `both` / 任何未知取值 → 两条都许（未知取值按「都给」处理，因为
     * [SideIndexDetector] 那边还有严格单轴闸门兜着，多给一侧不会变成误触）。
     */
    val allowLeft: Boolean get() = side != SIDE_RIGHT

    /** 允许右边缘起手。见 [allowLeft]。 */
    val allowRight: Boolean get() = side != SIDE_LEFT

    /**
     * 触发几何。**不含** `density` 的字段（见类注释），px 在这里算出来。
     *
     * @param density 屏幕密度（`Resources.displayMetrics.density`）
     * @param touchSlopPx `ViewConfiguration.getScaledTouchSlop()`。**不是我们的参数**，
     *   由调用方给（见类注释最后一节）。
     * @param cornerGuardPx 底部角落保留区（`SideIndexHotZone.CORNER_GUARD_DP` 换算成 px）
     * @param topGuardPx 顶部保留区（`SideIndexHotZone.TOP_GUARD_DP` 换算成 px）
     */
    fun trigger(
        density: Float,
        touchSlopPx: Float,
        cornerGuardPx: Float,
        topGuardPx: Float,
    ): TriggerConfig =
        TriggerConfig(
            edgePx = triggerWidthDp * density,
            allowLeft = allowLeft,
            allowRight = allowRight,
            cornerGuardPx = cornerGuardPx,
            topGuardPx = topGuardPx,
            touchSlopPx = touchSlopPx,
        )

    /**
     * **浮层窗口参数**变了没有 —— 变了就得换代浮层（见 [SideIndex.overlaySwapTick]）。
     *
     * 这几个（加 look）都是 [SideIndexOverlay] 的**构造参数**：窗口参数在 `addView`
     * 那一刻就定了，改它们没有「就地更新」这条路（那一侧的文件有另一个协作者在改，
     * 我们也不该去碰）。好在浮层窗口是**每次手势新建、抬手就拆**的，
     * 所以「两次手势之间换掉整个对象」既廉价又安全。
     *
     * [iconPack] 也在里面：它是构造参数（见那个字段的 KDoc —— 浮层对象跨手势复用，
     * 不换代就会「改了图标包看不到变化」）。
     *
     * ## [favorites] / [recentCount] **故意不在**这里
     *
     * 这两个改的是**面板的内容**，而内容是 `SideIndexOverlay.show()` 每一帧从
     * 刚发布的那份 `AppIndex.Index` 现读的（浮层本身不持有清单）。所以它们
     * 只要重算快照 + 重发字母表就够了（`SideIndex.applyConfig` 那条路），
     * 换代浮层是纯粹的多余动作 —— 而换代要拆窗口，那是有可见代价的。
     */
    fun windowDiffersFrom(o: SideIndexConfig): Boolean =
        look != o.look ||
            hapticEnabled != o.hapticEnabled ||
            hapticStrengthPercent != o.hapticStrengthPercent ||
            hapticDurationMs != o.hapticDurationMs ||
            blurRequested != o.blurRequested ||
            iconPack != o.iconPack ||
            // 本批 3️⃣：名字与单项图标都是**在 bindCell 那一刻填/解析**进格子的，
            // 而浮层对象跨手势复用 —— 不换代就会出现「改完名字/图标要等下次换代才生效」。
            // 单项图标比的是**签名**（而不是对象引用）：签名相同就是同一份覆盖，
            // 换代是纯多余动作（换代会拆一次窗口，有可见代价）。
            nameOverrides != o.nameOverrides ||
            iconOverrides.signature != o.iconOverrides.signature

    /**
     * **把手窗口**相关参数变了没有。
     *
     * 把手是**常驻**窗口，不能换代（换代会闪，而且它的窗口是可复用的，见 [SideIndexHandle]），
     * 所以走的是 [SideIndexHandle.applyLook] 的增量更新：边变了增删窗口，
     * 尺寸/位置变了走 `updateViewLayout`。
     */
    fun handleDiffersFrom(o: SideIndexConfig): Boolean =
        showHandle != o.showHandle ||
            side != o.side ||
            triggerWidthDp != o.triggerWidthDp ||
            look.handleHeightPercent != o.look.handleHeightPercent ||
            look.handleOffsetPercent != o.look.handleOffsetPercent

    /**
     * 一句话描述，进安装日志与安装报告，便于在设备上肉眼核对「设置有没有生效」。
     *
     * 只写**参数本身**；派生的东西（px、模糊是否真的生效、把手可见性）由各自的
     * 日志行负责，混在一起就分不清哪个数是配的、哪个是算出来的。
     */
    fun describe(): String =
        "edge=${triggerWidthDp.toInt()}dp side=$side recent=$showRecent/${recentCount} " +
            // 收藏项打**条数**而不是内容：内容可能几十个字符，打进日志会把这一行撑散，
            // 而「配了几条」正是核对「用户配了但面板上没有」时第一个要看的数。
            // 具体是哪些包另有 `FP-SideIndex: 收藏项 …` 那条日志（只在不为空时打）。
            "favorites=${favorites.size} " +
            "handle=$showHandle " +
            // 打档位，不打老的「强度%/时长ms」—— 那两个键已经删了（理由见本类字段的 KDoc），
            // 继续打它们等于让安装报告报一个界面上不存在的参数。
            "haptic=" + if (hapticEnabled) {
                SideIndexHaptics.resolveMode(hapticEnabled, hapticModeKey, hapticStrengthPercent)
                    .name.lowercase()
            } else {
                "off"
            } + " " +
            "blur=$blurRequested scope=$scope iconpack=${iconPack ?: "none"} " +
            // 本批 1️⃣/2️⃣/3️⃣ 的四项。都只打**条数/档位**，理由与 favorites 那条相同：
            // 具体是哪些包打进这一行会把日志撑散，而「配了几条、什么档位」才是
            // 核对「用户配了但面板上没有」时第一个要看的数。
            "launchers=${LauncherScope.describeLaunchers(launcherPackages)} " +
            "index_apps=" + if (IndexAppFilter.isActive(indexFilterMode, indexFilterPackages)) {
                "$indexFilterMode(${indexFilterPackages.size})"
            } else {
                "all"
            } + " " +
            "name_overrides=${nameOverrides.size} icon_overrides=${iconOverrides.size} " +
            "look[${look.describe()}]"

    companion object {
        /**
         * `recent_count` 的默认值：**10**，等于改动前 `RecentApps` 里硬编码的 `MAX`
         * 默认值等于改动前的行为是一条硬要求（规范 §9 的关态要求）。
         */
        const val DEFAULT_RECENT_COUNT = 10

        /**
         * `recent_count` 的滑杆下限。
         *
         * 为什么是 4 而不是 1：这个槽位是**面板里的一格网格**，1–2 项时面板退化成
         * 一行都填不满的样子，与「最近使用」这个语义不成比例；下限也就是
         * 「这个功能还成立」的最小值。
         */
        const val MIN_RECENT_COUNT = 4

        /**
         * `recent_count` 的滑杆上限，**同时是采样线程的保留上限**
         * （内存与磁盘上最多留这么多条，见 [SideIndex] 的 `RecentApps`）。
         *
         * 两者取同一个数是有意的：这样「把数量调到最大」一定能看到完整的历史，
         * 不存在「调大了却没有更多可显示」的中间态。
         * 24 的由来：面板一屏 3 行 × 5 列 = 15 格，24 已经超过它，
         * 再多就只能靠滚动看 —— 而这个是「最近使用」，不是归档。
         */
        const val MAX_RECENT_COUNT = 24

        private const val SIDE_LEFT = "left"
        private const val SIDE_RIGHT = "right"
    }
}

/**
 * 触发几何的快照：**输入分发线程在 `ACTION_DOWN` 读一次**，整段手势用同一份。
 *
 * ## 为什么是一份不可变快照，而不是几个 `@Volatile` 散字段
 *
 * 一次手势**必须自始至终用同一份几何**：如果 `edgePx` 因为用户正在拖滑杆而中途变了，
 * 就会出现「前半个手势按 A 判定、后半个按 B」。这份快照与 [AppIndex.BarLayout]
 * （字母表的几何快照）是同一套纪律、同一个时机（都在 DOWN 取一次、UP/CANCEL 清空）。
 *
 * ## 为什么可以放心地每次 DOWN 重读
 *
 * [SideIndex.trigger] 是 `@Volatile` 的可变引用，写它是**主线程或安装线程**上的一次
 * 引用赋值，读它是一次 volatile 读 —— 没有分配、没有 IPC、没有锁，
 * 符合「输入分发线程只读 volatile」的纪律。所以「下次手势生效」
 * 的延迟上限就是 0（本帧之后的第一次 DOWN 就已经是新几何了）。
 */
internal data class TriggerConfig(
    /** 边缘判定带宽度（px）。等于把手的宽度 —— 看得见的把手刚好覆盖有效热区。 */
    val edgePx: Float,
    /** 允许左边缘起手。 */
    val allowLeft: Boolean,
    /** 允许右边缘起手。 */
    val allowRight: Boolean,
    /** 底部角落保留区（px）。小窗优先，这一带里侧滑索引不抢。 */
    val cornerGuardPx: Float,
    /**
     * 顶部保留区（px）。**系统的下拉手势区**：状态栏 / 控制中心（右侧下拉）都在这里，
     * 而它们和「沿边缘下滑呼出索引」是同一条边缘上的同一段位移 —— 不避开就是
     * 「滑索引结果拉开控制中心」（2026-09-14 用户实测反馈）。语义与 [cornerGuardPx] 对称：
     * 只是**不许在这一带起手**，带内照常显示/滑动。
     */
    val topGuardPx: Float,
    /** `ViewConfiguration.getScaledTouchSlop()`，严格起步策略的底数（见的 4× 规则）。 */
    val touchSlopPx: Float,
) {
    companion object {
        /**
         * **关态**：任何位置都不成立。
         *
         * 卸载（[SideIndex.teardown]）时把 [SideIndex.trigger] 换成它，于是「特性关掉」
         * 在输入线程这一侧就是**可证明的空操作**：`allowLeft`/`allowRight` 都是 false，
         * 所以 `SideIndexHotZone.classify` 恒为 `OUTSIDE`，DOWN 直接 `return false`。
         * 这与「没装模块」的行为逐位一致（规范 §9 的关态要求）。
         */
        val DISABLED = TriggerConfig(
            edgePx = 0f,
            allowLeft = false,
            allowRight = false,
            cornerGuardPx = 0f,
            topGuardPx = 0f,
            touchSlopPx = 0f,
        )
    }
}
