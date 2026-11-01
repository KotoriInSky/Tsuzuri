package io.github.kotoriinsky.tsuzuri.core

/**
 * 参数的**语义类别**：用户按「我要改哪一类东西」找参数，而不是按实现分组名找。
 *
 * 为什么需要这一层：
 * 现有 `PrefSpec.group` 是**实现维度** —— 扇形的「外观」被劈成
 * `corner_icon` / `corner_layout` / `corner_window` / `corner_motion` 四个平级分组，
 * 调「图标直径」和调「扇形排布」隔着两页，改一个看不见另一个的效果。
 * 详情页因此有 9 行入口，首屏被入口占满。
 *
 * 这一层把「分组」收进「类别」，详情页只列类别（扇形 9 → 5 行、侧滑索引 6 → 4 行），
 * 到任一参数仍是 2 次点击。分组标识本身**不改**（它同时是返回栈键、搜索落点、
 * 文案键、图标工具区判据，改它等于四件事一起变）。
 */
enum class PrefCategory {
    TRIGGER,
    CONTENT,
    APPEARANCE,
    FEEDBACK,
    ADVANCED,

    /** 没登记的分组落这里并**照常显示** —— 「新增参数却从界面上消失」是最难查的一类故障。 */
    OTHER;

    /** 稳定标识：进 `Dest.FeatureCategory`，不要用 `name` 直接当界面键。 */
    val id: String get() = name.lowercase()

    /** 类别名的 i18n 键。由标识派生，加类别只需在 strings.xml 里加一行。 */
    val labelKey: String get() = "pref.category.$id"
}

/**
 * 一个分组的登记项。
 *
 * [exposed] = false 表示「键与 hook 行为保留、存量配置不悬空，但界面上不暴露」。
 */
data class CategoryDecl(
    val category: PrefCategory,
    val exposed: Boolean = true,
)

/**
 * 分组 → 类别映射表，**唯一一处**。
 *
 * `CatalogTest.everyPrefGroupIsRegisteredInCategories` 会遍历 Catalog 的所有分组，
 * 断言它们都在这张表里 —— 新增分组却忘了登记时编译期不报错，但测试会红。
 * （没登记也不会从界面消失，只会落到 OTHER；测试是提前发现，不是兜底。）
 */
object PrefCategories {

    val of: Map<String, CategoryDecl> = mapOf(
        // 触发：怎么唤出来
        "corner_trigger" to CategoryDecl(PrefCategory.TRIGGER),
        "side_trigger" to CategoryDecl(PrefCategory.TRIGGER),

        // 内容：显示什么
        "corner_list" to CategoryDecl(PrefCategory.CONTENT),
        "side_content" to CategoryDecl(PrefCategory.CONTENT),
        "copy_direct_content" to CategoryDecl(PrefCategory.CONTENT),
        // 图片卡的按钮开关：决定卡片上**显示哪些操作**（edit/share/save），属于内容层
        "buttons" to CategoryDecl(PrefCategory.CONTENT),
        // 识屏三类（2026-10-25）的二级分类：按入口分（传送门 / 超级小爱），详情页内联成分组标题
        "portal" to CategoryDecl(PrefCategory.CONTENT),
        "xiaoai" to CategoryDecl(PrefCategory.CONTENT),
        "portal_capture" to CategoryDecl(PrefCategory.CONTENT),
        "portal_card" to CategoryDecl(PrefCategory.CONTENT),
        "portal_capsules" to CategoryDecl(PrefCategory.CONTENT),

        // 外观：长什么样
        // 扇形排布决定长什么样，和预览、图标放一起（用户 2026-10-31）
        "corner_layout" to CategoryDecl(PrefCategory.APPEARANCE),
        "corner_icon" to CategoryDecl(PrefCategory.APPEARANCE),
        "corner_window" to CategoryDecl(PrefCategory.APPEARANCE),
        "corner_motion" to CategoryDecl(PrefCategory.APPEARANCE),
        "side_panel" to CategoryDecl(PrefCategory.APPEARANCE),
        "side_material" to CategoryDecl(PrefCategory.APPEARANCE),
        "copy_direct_interaction" to CategoryDecl(PrefCategory.APPEARANCE),

        // 反馈：摸得出来的手感
        "corner_selection" to CategoryDecl(PrefCategory.FEEDBACK),
        "corner_feedback" to CategoryDecl(PrefCategory.FEEDBACK),
        "side_feedback" to CategoryDecl(PrefCategory.FEEDBACK),

        // 高级：低频、调坏了不好恢复
        "corner_advanced" to CategoryDecl(PrefCategory.ADVANCED),

        // 登记但不暴露：键与 hook 行为保留，界面整组下架
        "side_handle" to CategoryDecl(PrefCategory.APPEARANCE, exposed = false),
        // 已删除或合并的项：hook 与预览仍按声明读，界面不显示；
        // 已存值由 ConfigMigration v5 删掉，合并项由 DerivedPrefs 联动写
        "corner_internal" to CategoryDecl(PrefCategory.ADVANCED, exposed = false),
        "side_internal" to CategoryDecl(PrefCategory.ADVANCED, exposed = false),
    )

    /** 查不到 = 没登记：落 OTHER 并照常显示（不是隐藏）。 */
    fun declarationOf(groupId: String): CategoryDecl =
        of[groupId] ?: CategoryDecl(PrefCategory.OTHER)
}

/** 一个类别的全部参数：按类别把 [PrefGroup] 聚起来，**类别内保持声明的分组顺序**。 */
class PrefCategoryGroup(
    val category: PrefCategory,
    val groups: List<PrefGroup>,
) {
    /** 这个类别下的全部参数（按分组顺序展开）。 */
    val prefs: List<PrefSpec> get() = groups.flatMap { it.prefs }

    /** 类别名的 i18n 键。 */
    val labelKey: String get() = category.labelKey

    /** 这个类别里有几项被改过（详情页拿不到组内值时，用它回答「我改过没有」）。 */
    fun changedCount(prefs: Prefs): Int = groups.sumOf { it.changedCount(prefs) }
}

/**
 * 按类别把参数聚合，供详情页与类别页用。
 *
 * 顺序 = [PrefCategory] 的枚举顺序（触发 → 内容 → 外观 → 反馈 → 高级 → 其它），
 * 这个顺序对用户是稳定的：同一类东西永远在同一个位置。
 *
 * `exposed = false` 的分组在这里被过滤掉，**但它仍在 [FeatureSpec.prefs] 里** ——
 * 配置键、hook 行为、存量数据都不动。
 */
fun FeatureSpec.prefCategories(): List<PrefCategoryGroup> {
    val visible = prefGroups().filter { PrefCategories.declarationOf(it.id).exposed }
    if (visible.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<PrefCategory, MutableList<PrefGroup>>()
    for (group in visible) {
        val category = PrefCategories.declarationOf(group.id).category
        buckets.getOrPut(category) { ArrayList() }.add(group)
    }
    return PrefCategory.entries.mapNotNull { category ->
        buckets[category]?.let { PrefCategoryGroup(category, it) }
    }
}

/** 某个分组属于哪个类别。搜索落点等地方要靠它从「组」反推「类别页」。 */
fun categoryOfGroup(groupId: String): PrefCategory =
    PrefCategories.declarationOf(groupId).category
