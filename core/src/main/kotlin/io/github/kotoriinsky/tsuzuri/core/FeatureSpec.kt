package io.github.kotoriinsky.tsuzuri.core

/**
 * 一个特性在界面上的**全部**描述：叫什么、属于哪组、作用于哪些进程、有哪些参数。
 *
 * 这是本项目的「单一事实来源」。加一个特性只动 [Catalog] 一处 + 后端实现，
 * 前端的列表、详情页、开关、参数控件全部由它生成 —— 界面代码里不允许硬编码特性。
 */
data class FeatureSpec(
    /** 稳定标识，进配置键，发布后不可改。文案键由它派生，见 [titleI18n] / [summaryI18n]。 */
    val key: String,
    val group: FeatureGroup,
    /** 需要注入哪些进程。用于「作用域自检」和诊断页。 */
    val scope: List<ScopeTarget>,
    val restart: RestartPolicy,
    val maturity: Maturity,
    /** 该特性的参数。空 = 只有一个总开关。 */
    val prefs: List<PrefSpec> = emptyList(),
    /** 仅影响设置页，不改变 hook 安装与已有配置。 */
    val presentation: FeaturePresentation = FeaturePresentation(),
) {
    val titleI18n: String get() = "feature.$key.title"
    val summaryI18n: String get() = "feature.$key.summary"
}

/**
 * 参数类型。刻意做成封闭集合：新增一种控件类型 = 前端两个 Kit 都要实现，
 * 是有成本的决定，不允许随手加。
 */
sealed interface PrefSpec {
    /** 完整配置键，见 [Keys.pref]。 */
    val key: String

    /** 参数在界面上的标签 i18n 键。 */
    val labelKey: String

    /** 补一句说明用途，可为空。 */
    val hintKey: String?

    /**
     * 二级页分组标识，见 [prefGroups]。`null` = **没归类**，落进兜底分组
     * [PREF_GROUP_OTHER] —— 那表示声明漏了，不是一种设计（`CatalogTest` 会对参数多的
     * 特性断言「每一项都归了类」）。
     *
     * 为什么把分组写在**每一项参数上**，而不是另写一张「分组 → 参数键」的表：
     * 表会漂移。某个键在表里漏了，那一项就从界面上**消失**，而代码、日志、编译全正常。
     * 写在参数自己身上，漏了只是进兜底分组，看得见。
     */
    val group: String?

    /**
     * 这一项**依赖哪个开关**。`null` = 独立项（绝大多数参数）。
     *
     * 用途只有一个，但它是硬要求：当被依赖的开关是「关」时，
     * 这一项在界面上**必须不可操作**，并且说明为什么。
     * 不这么做的后果在真机上已经发生过一次 —— 小窗摆放还是布尔总开关的时候，它关着的
     * 那四个 `window_*_percent` 仍然能拖，而它们根本不参与运算：用户拖了没反应，
     * 只能判断成「这个参数坏了」，**而界面无法反驳他**。
     *
     * ## 为什么是一条数据，而不是 `enabledWhen: (Prefs) -> Boolean`
     *
     * 因为一段 lambda 既不能静态检查，也不能被文档引用：没人能断言「它依赖的那个键
     * 真的存在、真的是个开关、真的在同一个特性里」。写成数据之后 `CatalogTest` 能查这三条，
     * 而且拼键名的地方只有 [dependencyKey] 一处。
     *
     * ## 「开关」也包括 `Choice` 的某一个取值（2026-09-19）
     *
     * [PrefDependency] 现在有两种形态，靠 [PrefDependency.toggleName] 指向的**参数类型**
     * 自动判别（见 [PrefSpec.isOperable]）：
     *
     *  - 指向一个 `Toggle` → 「开关开着才生效」（原来的唯一形态）；
     *  - 指向一个 `Choice`  → 「**选了这个取值**才生效」。取值在 [PrefDependency.choiceValue]
     *    里写明，`null` 表示「只要这个参数的取值不是空就算」——但那会退化成
     *    「选了别的也生效」，所以声明时必须给值。
     *
     * 多类型是用**运行时查 Catalog** 做的，而不是给 `dependsOn` 再加一个 `when` 判别式：
     * 判别式是第二份事实（声明处 + 判别处），两边不一致时的表现是「灰控件而界面上
     * 没有任何解释」。查 Catalog 则让「依赖的那个键真的存在吗、是什么类型」由
     * `CatalogTest` 静态查出来。
     */
    val dependsOn: PrefDependency?

    data class Toggle(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        val default: Boolean,
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
    ) : PrefSpec

    data class Slider(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        val default: Float,
        val min: Float,
        val max: Float,
        /** 0 = 连续。n = n 个中间刻度（Compose 语义）。 */
        val steps: Int,
        /** 数值后缀，如 "dp" / "mm" / "%"。 */
        val unit: String,
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
        /**
         * 推荐范围：滑杆轨道上加深标出的一段（2026-10-22 用户定：上下限尽量放开，
         * 不舒服用户自己会调；常用值用节点和这一段标出来）。null = 不标。
         */
        val recommended: ClosedFloatingPointRange<Float>? = null,
    ) : PrefSpec

    data class Choice(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        val default: String,
        val options: List<Option>,
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
        /**
         * true = 用 HyperOS 设置的原位下拉（右侧当前值 + 上下箭头，浮层贴行弹出），
         * 不按「≤4 项且短 → 分段」的默认分层。2026-10-25 用户指定「动图保存格式」照系统设置这样做。
         */
        val dropdown: Boolean = false,
    ) : PrefSpec {
        data class Option(
            val value: String,
            val labelKey: String,
            /**
             * 选中这一项时，在选项行下方补的一句说明（i18n 键）；`null` = 不说。
             * 只给「选了它就有一件用户必须知道的事」的选项用（例如复制直达的超级岛：
             * 不需要为本应用开通知权限）—— 不是每个选项都配一句。
             */
            val noteKey: String? = null,
        )
    }

    /**
     * 一串包名（应用多选）。用来表达「这个特性只对我挑的这几个应用生效」。
     *
     * ## 两种**相反**的用法，由 [emptyIsValid] 区分（声明时必须选一个）
     *
     * | | 白名单（默认） | 黑名单（[emptyIsValid] = true） |
     * | --- | --- | --- |
     * | 例子 | 「扇子里显示这几个应用」 | 「这几个应用里不响应手势」 |
     * | 空集的意思 | **用户没配过** → 消费侧走自动挑选 | **一份有效配置** = 一个都不屏蔽 |
     * | 少配了几个 | 低于 [minCount] 仍走自动挑选 | 就按选中的这几个来 |
     *
     * 不加这个开关之前，黑名单只能借用白名单的语义，后果是具体的：
     * 用户把勾全取消（他想表达「我一个都不屏蔽了」），消费侧却按「没配过 → 自动挑选」
     * 处理 —— 于是**自动挑一批应用来屏蔽**。功能在他眼里就是「我点坏了」。
     * 这个开关让「空 = 不屏蔽任何应用」成为**声明里写明的**契约，而不是消费侧的默契。
     *
     * ## 为什么是 Set 而不是 List
     *
     * 因为**这里的顺序没有意义**：消费侧（底角扇形的应用清单）每一段弧、每一个角度都是
     * 由几何算出来的（半径 × 层 × 序号），不按配置顺序排；而 [Set] 恰好表达了这一点，
     * 顺带免掉「同一个包被存了两次」这种只能靠调用方自觉避免的坏状态。
     *
     * 代价现在就写清楚，免得以后有人在这里偷偷假设有序：**要按用户顺序排的清单表达不了**
     * （例如「按我排的顺序固定前三个」）。真需要那种语义时，正确做法是换成 `List`
     * （改这个类型，或另加一个有顺序的类型），而不是把 Set 当成有序集合用 ——
     * `HashSet` 的迭代顺序是实现细节，今天看着像插入序，明天换个版本就变了。
     *
     * ## 白名单语义下的空集（[emptyIsValid] = false）
     *
     * 空集的意思是**「用户没配过」**，消费侧据此走自动挑选（最近使用 + 补全）。
     * 这条语义必须守住：若把空集当成「用户要求清空」，用户勾完再把勾全取消之后，
     * 功能会变成空的 —— 在他眼里就是「功能被我点坏了」。
     * 也正因为如此，[minCount] 的默认值是 1 而不是 0。
     */
    data class AppList(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        /** 默认集合。空集 = 用自动挑选（最近使用 + 补全），不是「一个都不显示」。 */
        val default: Set<String> = emptySet(),
        /** 最少选几个才认为「用户配过了」。低于它仍走自动挑选。 */
        val minCount: Int = 1,
        /**
         * **空集是不是一份有效配置**（黑名单语义）。`false` = 白名单（默认，见类注释的表格）。
         *
         * 三处必须按它分岔，否则界面会替用户把意思说反：
         *  1. 参数行的右值（空集时写「自动选取」还是「不屏蔽」）；
         *  2. 选择页顶上的状态条（空集时是**警告**还是正常态）；
         *  3. 消费侧（黑名单直接按集合判定，不做任何「自动挑选」的兜底）。
         */
        val emptyIsValid: Boolean = false,
        /** 选应用页「已选」段的段头（带个数）。排除类清单写「已排除」，开关的含义才不会读反。 */
        val pickedLabelKey: String = "app_picker.section.added",
        /** 选应用页其余应用的段头。 */
        val restLabelKey: String = "app_picker.section.rest",
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
    ) : PrefSpec

    /**
     * 有序清单（应用 + 快捷方式自由混排，**顺序有意义**）。
     *
     * ## 为什么是另一个类型，而不是把 [AppList] 的集合当成有序的
     *
     * [AppList] 的 KDoc 早就写死了这条：`Set` 表达的就是「顺序无意义」，
     * 要按用户顺序排就得换类型。顺序在这里不是装饰：扇形里每一项的位置由
     * 序号算出来（`FanMetrics.place(index)`），**顺序决定哪一项落在哪个槽位**。
     *
     * ## 存储
     *
     * 一条一行（`\n` 连接）的**单个字符串**，见 [OrderedItems]。
     * 不能用 `StringSet`：它的迭代顺序是实现细节，今天看着像插入序，明天换个版本就变了。
     * 这与 `AppSettings` 里搜索历史那次的结论是同一条（那边也是被迫从 StringSet 改成
     * 带分隔符的字符串，注释写着「换成一个带分隔符的字符串，顺序就是原样」）。
     *
     * **空清单 = 删键**（与 [AppList] 同一条契约：空集语义是「用户没配过」）。
     */
    data class OrderedList(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        /** 默认顺序。空 = 没配过 → 消费侧走自动挑选。 */
        val default: List<String> = emptyList(),
        /** 最少几项才认为「用户配过了」。低于它仍走自动挑选。 */
        val minCount: Int = 1,
        /** 清单为空时入口行的右值：空清单的含义每份不同（自动挑选 / 没有收藏 / 系统顺序）。 */
        val emptyLabelKey: String = "app_picker.state.auto",
        /** 只能加应用：宿主那边没有快捷方式 / 链接 / 页面的对应项。 */
        val appsOnly: Boolean = false,
        /** 清单只决定排在最前的几项，没加进来的项照常显示（否则 = 只显示清单里的项）。 */
        val pinnedFirst: Boolean = false,
        /** 点清单行能改什么。 */
        val iconTools: ListIconTools = ListIconTools.ICON,
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
    ) : PrefSpec

    /**
     * 子页入口：这一行**没有可调的值**，点进去是一个完整的功能页
     * （编辑器/导入导出/预览这类塞不进行控件的东西）。
     *
     * 与 `OrderedList`（行也是入口，但右值是清单状态）的区别：Screen **不读配置**，
     * 它声明的是「这里有一个编辑面」，而不是「这里有一个值」。页面的读写目标
     * 由页面自己从 [key] 推导 —— 与 OrderedList 用 key 推特性键是同一条纪律：
     * 声明进 Catalog，页面对键名零硬编码。
     */
    data class Screen(
        override val key: String,
        override val labelKey: String,
        override val hintKey: String? = null,
        override val group: String? = null,
        override val dependsOn: PrefDependency? = null,
    ) : PrefSpec
}

/** 清单行点进去能改什么，清单页按它决定行点击，页面不再按特性键判断。 */
enum class ListIconTools {
    /** 换这一项的图标。 */
    ICON,

    /** 改这一项的名称与图标。 */
    NAME_AND_ICON,

    /** 图标由宿主画，换了也不生效：行不可点。 */
    NONE,
}

/**
 * 「这一项在什么条件下生效」—— [PrefSpec.dependsOn] 的取值。
 *
 * 只表达一种关系形态（「依赖某**一个**参数」），刻意不做成 DSL：
 * 依赖的**键**用参数名（不带特性前缀）表达，拼完整键的地方只有 [PrefSpec.dependencyKey]。
 *
 * ## 被依赖的那个参数可以是 `Toggle`，也可以是 `Choice`
 *
 * [toggleName] 这个名字是历史（它诞生时只有开关这一种依赖，而**键名不能动**：
 * 它进 `CatalogTest` 的静态检查与文档）。它指向的参数是什么类型，由
 * [PrefSpec.isOperable] 在运行时查 Catalog 得出：
 *
 * | 被依赖的参数 | 什么时候「满足」 |
 * | --- | --- |
 * | `PrefSpec.Toggle` | 该开关为 `true` |
 * | `PrefSpec.Choice` | 该参数的当前值 == [choiceValue] |
 *
 * 查 Catalog 而不是在声明里再写一个 `when`：后者是第二份事实，
 * 而漂移的表现是**静默的**（灰控件或永远可见的控件），见 [PrefSpec.dependsOn] 的注释。
 */
class PrefDependency(
    /** 被依赖的参数的**参数名**（`Catalog` 里 `p(FEATURE, "…")` 的那一段，不含特性前缀）。 */
    val toggleName: String,
    /**
     * 依赖没满足时补的一句说明（i18n 键）。**必须写**：只说「不能点」等于把用户扔在原地，
     * 他需要的是一句「因为上面那个开关关着 / 因为你没选自定义」。
     */
    val offHintKey: String,
    /**
     * 历史字段，**已不影响显隐**：2026-10-21 起所有依赖一律「不满足就收起」（
     * 用户确认按 HyperOS 设置的折叠方式：父项关着子项不在页面上，打开后在父项下方展开）。
     * 原来的「禁用 + 说明」形态不再使用。保留字段只为不改动 Catalog 里已有的声明。
     */
    val hideWhenOff: Boolean = false,
    /**
     * [toggleName] 指向 `PrefSpec.Choice` 时，要匹配的**那一个取值**。
     *
     * `null` 且被依赖的是 `Choice` 时，[PrefSpec.isOperable] 退化成「该参数有值就满足」——
     * 那等于依赖失效（选了另外两项照样生效），所以那是一种**声明错误**，
     * 由 `CatalogTest` 当场判红，而不是在真机上表现成「拖了没反应」。
     */
    val choiceValue: String? = null,
)

/** 被依赖开关的完整配置键；没有依赖时 `null`。 */
fun PrefSpec.dependencyKey(featureKey: String): String? =
    dependsOn?.let { Keys.pref(featureKey, it.toggleName) }

/**
 * 这一项现在**能不能操作**（依赖的参数满足了，或没有依赖）。
 *
 * 放在 `core` 而不是界面里：这条判断同时被两个 Kit 的页面用，而且它必须能被单测
 * 直接钉住（「关掉开关 → 四项不可操作」这件事不该靠肉眼在真机上确认）。
 * 读配置失败一律回退默认值 —— 那是全局规则，[Prefs] 自己兜住。
 *
 * ## 两种依赖形态（见 [PrefDependency] 的表格）
 *
 * 被依赖的参数在 `Catalog` 里是什么**类型**，决定了「满足」的判据：
 *  - `Toggle` → 它得是 `true`；
 *  - `Choice` → 它的当前值得等于 [PrefDependency.choiceValue]。
 *
 * ⚠️ 读不到被依赖的参数时（键没声明 / 拼错 / Catalog 被改坏）**回退到「不可操作」**：
 * 这是保守方向 —— 一个不许点、并给了原因的控件，用户能看懂；
 * 一个点了没反应的控件，用户只会判断成「功能坏了」（这个项目真的发生过一次）。
 * 用 `rawBoolean` / `rawString` 而不是走 `Catalog` 查 spec，就是为了让这条路径
 * 在「键根本不在 Catalog 里」时也**不抛异常**。
 */
fun PrefSpec.isOperable(prefs: Prefs, featureKey: String): Boolean {
    val d = dependsOn ?: return true
    val key = dependencyKey(featureKey) ?: return true
    val target = Catalog.find(featureKey)?.prefs?.firstOrNull { it.key == key }
    return when (target) {
        // 只认明确声明的开关：`null` 落进下面那条（保守）。`PrefSpec.Choice` 的取值
        // 与 `Toggle` 的 `true` 在存储里类型不同，绝不能混读。
        is PrefSpec.Toggle -> prefs.rawBoolean(key, false)
        // Choice 依赖「某一个取值」。`choiceValue` 没写 = 声明错误（`CatalogTest` 判红），
        // 这里按「不满足」处理，至少不会让用户拖一个不生效的滑杆。
        is PrefSpec.Choice ->
            d.choiceValue != null && prefs.rawString(key, null) == d.choiceValue
        else -> false
    }
}

/** 不可操作时那句「为什么」；可操作或没有依赖时 `null`。 */
fun PrefSpec.blockedHintKey(prefs: Prefs, featureKey: String): String? =
    if (isOperable(prefs, featureKey)) null else dependsOn?.offHintKey

/**
 * 这一行**要不要显示**：依赖不满足就收起（HyperOS 设置的折叠方式）。
 *
 * 判据就是 [isOperable] —— 显隐与可操作性同源，所以不会出现「看得见但点不动」的行，
 * 也就不再需要「禁用 + 说明为什么」那句小字（用户 2026-10-21：小字越少越好）。
 * 界面用展开 / 收起动画呈现这个变化，而不是瞬间出现消失。
 */
fun PrefSpec.isVisible(prefs: Prefs, featureKey: String): Boolean = isOperable(prefs, featureKey)

/** 没归类的参数落进哪个分组（见 [PrefSpec.group]）。 */
const val PREF_GROUP_OTHER = "other"

/** 一个二级页分组：标识 + 它的参数（**保持 Catalog 里的声明顺序**）。 */
class PrefGroup(val id: String, val prefs: List<PrefSpec>) {
    /** 分组名的 i18n 键。由标识派生，所以加一个分组只需要在 strings.xml 里加一行。 */
    val labelKey: String get() = "pref.group.$id"
}

/**
 * 按 [PrefSpec.group] 把参数分组，供二级页用。
 *
 * 顺序 = 分组**第一次出现**的位置，所以 Catalog 里声明的顺序就是界面上的顺序，
 * 不需要在别处再维护一份顺序表。
 *
 * 一条都没归类时只返回一个分组 —— 调用方（`FeatureDetailScreen`）据此**内联展开**，
 * 于是参数少的特性不会被迫多一层二级页。
 */
fun FeatureSpec.prefGroups(): List<PrefGroup> {
    if (prefs.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<PrefSpec>>()
    for (p in prefs) buckets.getOrPut(p.group ?: PREF_GROUP_OTHER) { ArrayList() }.add(p)
    return buckets.map { (id, list) -> PrefGroup(id, list) }
}

/**
 * 这一组里有几项被改过（当前值 ≠ Catalog 里声明的默认值）。
 *
 * 详情页用它做每组的**状态摘要**：分组一旦收进二级页，
 * 用户在详情页就看不到组内的值了 —— 没有这一列，他只能靠记忆判断「我改没改过这一组」。
 *
 * 取不到配置（框架没连上）时按「一项都没改」处理：读配置失败一律回退默认值，这是全局规则。
 */
fun PrefGroup.changedCount(prefs: Prefs): Int = try {
    // 注意 `this.prefs`：参数名与 `PrefGroup.prefs` 同名，不写限定就变成在 `Prefs` 上找 `count`
    this.prefs.count { !it.isDefault(prefs) }
} catch (_: Throwable) {
    0
}

/** 这一项当前是不是声明里的默认值。 */
private fun PrefSpec.isDefault(prefs: Prefs): Boolean = when (this) {
    is PrefSpec.Toggle -> prefs.value(this) == default
    // 滑杆是浮点：用户把滑杆拖回默认位置时，存下来的值和默认值的二进制表示可能差一丝，
    // 用「差值小于一个刻度」判等，免得显示成「改过一项」而用户明明没动过
    is PrefSpec.Slider -> kotlin.math.abs(prefs.value(this) - default) < stepSize
    is PrefSpec.Choice -> prefs.value(this) == default
    is PrefSpec.AppList -> prefs.value(this) == default
    // 顺序也算内容：拖一下顺序就是一次「改动」，详情页那一列要跟着变
    is PrefSpec.OrderedList -> prefs.value(this) == default
    // 子页入口没有可调的值，永远不算「改过」
    is PrefSpec.Screen -> true
}

/** 一个刻度的大小（连续滑杆给一个够小的值，任何真实拖动都超过它）。 */
private val PrefSpec.Slider.stepSize: Float
    get() = if (steps <= 0) 1e-4f else ((max - min) / (steps + 1)) * 0.5f

/** 特性分组。顺序即界面上的展示顺序。 */
enum class FeatureGroup {
    GESTURE,
    NEGATIVE_SCREEN,

    /**
     * 识屏（传送门 / taplus）。
     *
     * 单独一组而不是塞进 [NEGATIVE_SCREEN]：后者的语义是「负一屏（PA）上的东西」，
     * 而传送门是 `com.miui.contentextension` 进程、由长按触发、与负一屏没有关系
     * （它俩只是恰好都跟「小部件」有点交集）。混在一组里会让用户按「负一屏」的心智
     * 去找一个不在负一屏上的开关。
     */
    SCREEN_RECOGNITION,
    CLIPBOARD,
    APPS;

    val titleI18n: String get() = "group.$name"
}

/** 需要注入的进程。`packageName` 就是 `scope.list` 里写的那个。 */
enum class ScopeTarget(val packageName: String) {
    SYSTEM_UI("com.android.systemui"),
    AI_ENGINE("com.xiaomi.aicr"),
    SYSTEM_UI_PLUGIN("miui.systemui.plugin"),
    AOD("com.miui.aod"),
    PERSONAL_ASSISTANT("com.miui.personalassistant"),

    /**
     * 传送门（内部代号 taplus）的宿主进程。
     *
     * 注意它承载的是**识别 + 悬浮窗 UI + 联网**三件事；真正的 View 抓取代码跑在
     * **被识别的 App 进程**里，因此抓取器或 UI 增强不应落到 `com.miui.contentcatcher`。
     */
    CONTENT_EXTENSION("com.miui.contentextension"),

    /**
     * 传送门的 Binder 服务与页面准入配置中心。
     *
     * 它不执行 View 抓取或卡片 UI；只有 `getPageConfig(Token)` 这类服务端准入逻辑应注入这里。
     */
    CONTENT_CATCHER("com.miui.contentcatcher"),

    /**
     * 小爱同学（语音助手）主进程。识屏页 `ScreenRecognitionActivity` 与
     * 手势入口 `VoiceService` 都在这里；只有改识屏页**呈现样式**的特性应注入这里，
     * 识别本身（云链路）不碰。
     */
    VOICE_ASSIST("com.miui.voiceassist"),
    SUBSCREEN("com.xiaomi.subscreencenter"),
    SETTINGS("com.android.settings"),

    /** 分享面板（`MiIntentResolver`）。按需启动的普通应用进程，有 ART。 */
    SHARE_SHEET("com.android.intentresolver"),
    /** 虚拟包名，代表 system_server 自身。 */
    SYSTEM_SERVER("system");

    val labelI18n: String get() = "scope.$name"
}

/**
 * 改完开关之后，这个特性需要多大动作才生效。
 * 前端据此决定是提示「已生效」还是弹「需要重启系统界面」。
 */
enum class RestartPolicy {
    /** 后端监听配置变化自己重装 hook，用户无感。 */
    HOT,

    /** 需要前端调框架的 `hotReloadModule()` 重载目标进程（不闪屏）。 */
    HOT_RELOAD,

    /** 只能杀进程重启（会闪一下）。 */
    PROCESS_RESTART,
}

/**
 * 成熟度。**必须诚实标注**：把没跑通的特性标成稳定，比不做还糟。
 */
enum class Maturity {
    /** 设备上验证过，开态关态都过。 */
    STABLE,

    /** 能跑，但边界情况没摸清。界面上带「实验」标记。 */
    EXPERIMENTAL,

    /** 代码在，但目标没达成，暂时搁置。界面默认不可开。 */
    PARKED,

    /** 只有设计，没有实现。界面上灰显 + 「规划中」。 */
    PLANNED,
}

/**
 * 用户能不能真正打开这个特性。
 *
 * STABLE / EXPERIMENTAL 可以；PARKED（代码在但目标没达成）和 PLANNED（还没写）
 * 一律灰显 —— **一个开了没反应的开关比没有开关更糟**，那会让人以为功能坏了。
 */
val Maturity.isUserToggleable: Boolean
    get() = this == Maturity.STABLE || this == Maturity.EXPERIMENTAL

/** 后端是否有实现。没实现的特性连装都不装。 */
val Maturity.isImplemented: Boolean
    get() = this == Maturity.STABLE || this == Maturity.EXPERIMENTAL
