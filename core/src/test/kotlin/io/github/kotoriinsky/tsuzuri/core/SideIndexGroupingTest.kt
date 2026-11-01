package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 钉住「侧滑索引的参数必须有归类」。
 *
 * ## 为什么值得一个专门的测试文件
 *
 * 这个缺陷是**静默**的，而且真实发生过：`side_index` 的参数一度全部没有 `group`，
 * 于是 `prefGroups()` 只返回一组、`FeatureDetailScreen` 把 25 个参数**内联展开**在详情页上，
 * 其中「收藏项」那一行的编辑入口就夹在 24 个滑杆/开关中间 —— 产品经理找不到它，
 * 直接问「收藏自定义做了吗」。
 *
 * 代码上没有任何东西是坏的：数据读写正常、入口 `onClick` 正常、构建全绿。
 * **坏的是"用户能不能看见"**，而这件事没有任何自动化能替我们发现 ——
 * 除非把它变成一条断言。所以：
 *
 * - 每个参数都要有 `group`（漏一个就会落进兜底分组、和别的混在一起）；
 * - 分组数要 > 1（一组 = 内联展开 = 又回到被埋的状态）；
 * - 分组里不能只有一项（一个只有一行的二级页比放在详情页更难用，见 [FeatureSpec.prefGroups] 的注释）。
 *
 * 新增参数时如果忘了写 `group`，这个测试会红 —— 比等用户来问便宜得多。
 */
class SideIndexGroupingTest {

    private val spec = assertNotNull(Catalog.find(Catalog.SIDE_INDEX))

    @Test
    fun `侧滑索引的每个参数都有归类`() {
        val ungrouped = spec.prefs.filter { it.group == null }.map { it.key }
        assertEquals(emptyList(), ungrouped, "这些参数没有 group，会被内联展开在详情页里、用户找不到")
    }

    @Test
    fun `分组数大于一（否则还是内联展开）`() {
        val groups = spec.prefGroups()
        assertTrue(groups.size > 1, "只有 ${groups.size} 组 —— FeatureDetailScreen 会内联展开，入口又会被埋")
    }

    @Test
    fun `没有只有一项的分组`() {
        val lonely = spec.prefGroups().filter { it.prefs.size <= 1 }.map { it.id }
        assertEquals(emptyList(), lonely, "只有一行的二级页比放在详情页更难用")
    }

    @Test
    fun `收藏项与最近数量在同一组里（它们是同一个功能面）`() {
        val content = spec.prefGroups().firstOrNull { it.id == "side_content" }
        assertNotNull(content, "「收藏与最近」这一组不存在 —— 收藏项的编辑入口会找不到")
        val keys = content.prefs.map { it.key }
        assertTrue(
            keys.contains(Keys.pref(Catalog.SIDE_INDEX, "favorites")),
            "收藏项不在 side_content 组里：$keys",
        )
        assertTrue(
            keys.contains(Keys.pref(Catalog.SIDE_INDEX, "recent_count")),
            "最近数量不在 side_content 组里：$keys",
        )
    }
}
