package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 「最近使用」列表的纯逻辑（从纯内存改成可持久化 + 数量可调）。
 *
 * 这一层为什么要单独测：**顺序 + 截断就是这份数据的全部语义**。
 * 它在真机上的落点是「★ 槽里那个应用列表」（`AppIndex.Index.withSpecial`），
 * 而 [RecentList] 是它唯一可以被离线钉住的部分 —— hook 侧没有测试源集
 * （只有 `core/src/test`），所以顺序/去重/截断这三条必须在这里说死。
 *
 * 三条容易写错、且错了不会报错的点（本项目的纪律是「静默失效比没有更糟」）：
 *
 *  1. **头部不变时的引用相等**：采样线程每秒比对一次，「没变」必须能让调用方
 *     靠 `===` 判断出来 —— 否则每一秒都会写一次盘、惊动一次主线程。
 *  2. **重复项被提升而不是被保留**：同一个应用再次打开必须挪到最前，
 *     而不是在列表里留下两条（那会在面板里画两个一模一样的图标）。
 *  3. **截断的方向**：丢的是**最旧**的，不是最新的。
 */
class RecentListTest {

    @Test
    fun theNewestPackageGoesToTheFront() {
        var list = RecentList.push(emptyList(), "com.a", 10)
        assertEquals(listOf("com.a"), list)
        list = RecentList.push(list, "com.b", 10)
        assertEquals(listOf("com.b", "com.a"), list)
        list = RecentList.push(list, "com.c", 10)
        assertEquals(listOf("com.c", "com.b", "com.a"), list)
    }

    @Test
    fun openingAnOldPackagePromotesItWithoutLeavingADuplicate() {
        // 面板里同一格出现两个一模一样的图标是「去重没做」的唯一可见症状
        val list = RecentList.push(listOf("com.c", "com.b", "com.a"), "com.b", 10)
        assertEquals(listOf("com.b", "com.c", "com.a"), list)
    }

    @Test
    fun reOpeningTheCurrentHeadReturnsTheSameInstance() {
        // 采样线程每秒问一次「变了吗」；这里返回同一个实例 = 调用方可以靠引用相等
        // 决定「不写盘、不惊动主线程」。没有这一条，列表没变也会每秒写一次文件。
        val current = listOf("com.c", "com.b", "com.a")
        assertSame(current, RecentList.push(current, "com.c", 10))
        // 也覆盖「列表还没超过上限」这一档（上限那条分支不能顺手 copy 一份）
        val short = listOf("com.b")
        assertSame(short, RecentList.push(short, "com.b", 10))
    }

    @Test
    fun blankPackagesAreIgnoredAndReturnTheSameInstance() {
        val current = listOf("com.b")
        assertSame(current, RecentList.push(current, "", 10))
        assertSame(current, RecentList.push(current, "   ", 10))
        assertSame(current, RecentList.push(current, "\n", 10))
        // 前后空白按同一个包名处理（存储侧 OrderedItems.encode 也会 trim，
        // 不在这里对齐就会出现「存进去一个、读出来另一个」）
        assertSame(current, RecentList.push(current, " com.b ", 10))
        assertEquals(listOf("com.a"), RecentList.push(emptyList(), " com.a ", 10))
    }

    @Test
    fun theOldestItemIsDroppedWhenTheCapIsReached() {
        var list = listOf("com.c", "com.b", "com.a")
        list = RecentList.push(list, "com.d", 3)
        assertEquals(listOf("com.d", "com.c", "com.b"), list)
        // 上限为 1 时只剩最新的那一个
        assertEquals(listOf("com.d"), RecentList.push(listOf("com.c", "com.b"), "com.d", 1))
    }

    @Test
    fun anAlreadyOversizedListIsTrimmedOnTheNextPush() {
        // 用户在设置里把数量调小之后，内存里那份历史可能比上限长
        // （历史长度 = 上限的最大值，见 SideIndex.MAX_RECENT_COUNT）——
        // 下一次 push 必须顺手把它收口，否则「调小了但列表还是长的」会一直在
        val long = listOf("com.f", "com.e", "com.d", "com.c", "com.b", "com.a")
        assertEquals(listOf("com.g", "com.f"), RecentList.push(long, "com.g", 2))
    }

    @Test
    fun aNonPositiveCapYieldsAnEmptyList() {
        // 界面上取不到这个值（滑杆下限是 4），但纯函数不许依赖调用方的自觉：
        // 上限是 0 就是「不留历史」，不是「不截断」
        assertEquals(emptyList(), RecentList.push(listOf("com.a"), "com.b", 0))
        assertEquals(emptyList(), RecentList.push(listOf("com.a"), "com.b", -3))
    }

    @Test
    fun pushIsPureAndDoesNotMutateTheGivenList() {
        // 传进去的是上一份快照，可能正被主线程读着（发布给浮层的那一份）
        val given = listOf("com.b", "com.a")
        val out = RecentList.push(given, "com.c", 10)
        assertEquals(listOf("com.b", "com.a"), given)
        assertTrue(out !== given)
    }

    @Test
    fun theDisplayCountOnlyTrimsTheViewAndNotTheRetainedHistory() {
        // 「数量可调」的语义：改小只影响 ★ 槽里显示几个，**不丢历史** ——
        // 改回大值应当重新看到更多（这也是「持久化多留一些」的理由）
        val history = listOf("com.e", "com.d", "com.c", "com.b", "com.a")
        assertEquals(listOf("com.e", "com.d", "com.c"), RecentList.visible(history, 3))
        assertEquals(history, RecentList.visible(history, 5))
        assertEquals(history, RecentList.visible(history, 99))
        assertEquals(emptyList(), RecentList.visible(history, 0))
        assertEquals(emptyList(), RecentList.visible(history, -1))
        assertEquals(emptyList(), RecentList.visible(emptyList(), 10))
    }

    @Test
    fun theOrderSurvivesThePersistenceFormat() {
        // 落盘用的是 OrderedItems（一条一行）。顺序是这份数据的一半信息，
        // 所以「写出去能被原样读回来」必须有断言 —— StringSet 那条路会静默丢顺序，
        // 而表现是「最近使用每次重启都换个排列」，没有报错。
        val history = listOf("com.c", "com.b", "com.a")
        assertEquals(history, OrderedItems.decode(OrderedItems.encode(history)))
        // 坏行（空行、纯空白）不会变成「一个点不动的项」
        assertEquals(history, OrderedItems.decode("com.c\n\n \ncom.b\ncom.a\n"))
        // 坏数据整份丢掉也不行：认不出就按空历史处理，与「没配过」同一条语义
        assertEquals(emptyList(), OrderedItems.decode(null))
        assertEquals(emptyList(), OrderedItems.decode(""))
    }
}
