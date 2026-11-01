package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住「复用判据」——它是这次性能优化里**唯一会出错的地方**，
 * 而且出错的样子很隐蔽：复用错了 = 图标或名字是旧的，用户只会觉得「有点不对」。
 *
 * 三个必须成立的性质：
 * 1. 全失效时一项都不复用（首次构建、拿不到包名时的保守路径）；
 * 2. 只有明确知道变了的包不复用，其余复用（这是省下 200 次 IPC 的来源）；
 * 3. **判据不看名字** —— 所以名字变化必须靠「包被放进失效集」来覆盖，
 *    这条要是破了，改名后的应用会一直显示旧名字（而没有任何报错）。
 */
class IndexReuseTest {

    @Test
    fun `全失效时一项都不复用`() {
        val inv = IndexReuse.Invalidation.ALL
        assertFalse(inv.canReuse("com.miui.home"))
        assertFalse(inv.canReuse("com.tencent.mm"))
    }

    @Test
    fun `无失效时一切都可复用`() {
        val inv = IndexReuse.Invalidation.NONE
        assertTrue(inv.canReuse("com.miui.home"))
        assertTrue(inv.canReuse("com.tencent.mm"))
    }

    @Test
    fun `只有失效的那几个包不复用`() {
        val inv = IndexReuse.Invalidation.NONE.plus("com.tencent.mm")
        assertFalse(inv.canReuse("com.tencent.mm"), "明确变了的那一个不该复用")
        assertTrue(inv.canReuse("com.miui.home"), "其余必须复用 —— 这正是省下 IPC 的来源")
    }

    @Test
    fun `连续合并多个包名会累积`() {
        val inv = IndexReuse.Invalidation.NONE
            .plus("com.a")
            .plus("com.b")
        assertFalse(inv.canReuse("com.a"))
        assertFalse(inv.canReuse("com.b"))
        assertTrue(inv.canReuse("com.c"))
    }

    @Test
    fun `拿不到包名时退化成全失效`() {
        // 广播没带 data、或者解析失败 —— **不能猜**：猜错的表现是一个不更新的条目，
        // 而多扫一遍只是慢一点。正确性优先。
        assertTrue(IndexReuse.Invalidation.NONE.plus(null).all)
        assertTrue(IndexReuse.Invalidation.NONE.plus("").all)
        // 已经明确失效过几个包，再来一条拿不到包名的 → 仍然退化成全失效
        assertTrue(IndexReuse.Invalidation.NONE.plus("com.a").plus(null).all)
    }

    @Test
    fun `全失效之后再合并包名仍然是全失效`() {
        // 别让「又收到一条具体包名」把保守状态降级回去 —— 那会让已经失效的项偷偷复用旧图标
        val inv = IndexReuse.Invalidation.ALL.plus("com.a")
        assertTrue(inv.all)
        assertFalse(inv.canReuse("com.a"))
        assertFalse(inv.canReuse("com.anything.else"))
    }

    @Test
    fun `复用键是包加组件`() {
        // 同一个包的多个启动入口必须是**不同的键**，否则复用时会丢入口
        assertEquals("com.a/B", IndexReuse.componentKey("com.a", "B"))
        assertTrue(
            IndexReuse.componentKey("com.a", "B") != IndexReuse.componentKey("com.a", "C"),
            "一个包的多个入口必须区分开",
        )
    }
}
