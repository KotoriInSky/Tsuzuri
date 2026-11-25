package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.AggPaymentTaskBinding.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggPaymentTaskBindingTest {

    @Test
    fun `first open binds newly appeared task and stays locked`() {
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = emptySet())

        assertTrue(b.resolve(emptyList(), nowMs = 1_000L) is Decision.Waiting)
        assertEquals(Decision.Bind(101), b.resolve(listOf(101), nowMs = 1_080L, eventTaskId = 101, eventIsUpdate = true))
        assertEquals(101, b.boundTaskId)

        // 超过落地期后用户又多开一个同包小窗 #102 并置顶，仍死锁在 #101
        assertEquals(
            Decision.Bind(101),
            b.resolve(listOf(102, 101), nowMs = 5_000L, eventTaskId = 102, eventIsUpdate = true),
        )
    }

    @Test
    fun `multi-freeform switching to pre-existing normal window never steals bar`() {
        // 打开聚合支付前已经有一个普通微信聊天小窗 #50
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = setOf(50))

        // 启动瞬间只有 #50，绝不抢挂到 #50
        assertTrue(b.resolve(listOf(50), nowMs = 1_000L) is Decision.Waiting)

        // 付款码小窗 #51 出现，立刻绑定 #51
        assertEquals(
            Decision.Bind(51),
            b.resolve(listOf(51, 50), nowMs = 1_100L, eventTaskId = 51, eventIsUpdate = true),
        )

        // 用户在落地期内点回普通聊天小窗 #50（#50 跑到 Z 序顶层），仍严格绑定 #51
        assertEquals(
            Decision.Bind(51),
            b.resolve(listOf(50, 51), nowMs = 1_500L, eventTaskId = 50, eventIsUpdate = true),
        )

        // 超过落地期后再点回 #50，仍严格绑定 #51
        assertEquals(
            Decision.Bind(51),
            b.resolve(listOf(50, 51), nowMs = 6_000L, eventTaskId = 50, eventIsUpdate = true),
        )
    }

    @Test
    fun `closing bound payment window returns Gone even when normal window remains`() {
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = setOf(50))
        assertEquals(Decision.Bind(51), b.resolve(listOf(51, 50), nowMs = 1_100L))

        // 稳态关闭付款码 #51，只剩普通小窗 #50 → 立刻 Gone，绝不寄生到 #50
        assertEquals(Decision.Gone, b.resolve(listOf(50), nowMs = 5_000L))
    }

    @Test
    fun `closing bound payment window during settle never falls back to normal window`() {
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = setOf(50))
        assertEquals(Decision.Bind(51), b.resolve(listOf(51, 50), nowMs = 1_100L))

        // 即使在落地期且超过 reuseFallbackMs，关闭 #51 后也绝不能去抢 #50
        val waiting = b.resolve(listOf(50), nowMs = 2_000L, eventTaskId = 50, eventIsUpdate = true)
        assertTrue(waiting is Decision.Waiting && waiting.previousVanished)

        // 落地超时后干净结束
        assertEquals(Decision.Gone, b.resolve(listOf(50), nowMs = 4_050L))
    }

    @Test
    fun `alipay trampoline chain relays across new tasks and ignores pre-existing window`() {
        // 已有普通支付宝小窗 #60
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = setOf(60))

        // 第一跳 ShortcutsLauncherActivity #200
        assertEquals(Decision.Bind(200), b.resolve(listOf(200, 60), nowMs = 1_050L))
        // 第二跳 SchemeLauncherActivity #201（#200 还没 vanish）
        assertEquals(Decision.Bind(201), b.resolve(listOf(201, 200, 60), nowMs = 1_120L))
        // 第三跳 OnsitepayActivity #202（#201 还没 vanish）
        assertEquals(Decision.Bind(202), b.resolve(listOf(202, 201, 200, 60), nowMs = 1_200L))
        // 跳板 #200/#201 清理掉，#202 稳定
        assertEquals(Decision.Bind(202), b.resolve(listOf(202, 60), nowMs = 1_400L))
        // 就算用户切到普通支付宝 #60，也绝不跟过去
        assertEquals(Decision.Bind(202), b.resolve(listOf(60, 202), nowMs = 1_500L, eventTaskId = 60, eventIsUpdate = true))
        // 退役的 #201 哪怕残留也不会回跳
        assertEquals(Decision.Bind(202), b.resolve(listOf(201, 202, 60), nowMs = 1_550L))
    }

    @Test
    fun `trampoline hop where old task vanishes right before next task appears`() {
        val b = AggPaymentTaskBinding(startedAtMs = 1_000L, preExistingTaskIds = setOf(60))
        assertEquals(Decision.Bind(200), b.resolve(listOf(200, 60), nowMs = 1_050L))

        // #200 先 vanish，#201 下一帧才来
        val wait = b.resolve(listOf(60), nowMs = 1_100L)
        assertTrue(wait is Decision.Waiting && wait.previousVanished)

        // #201 到达，顺利接住
        assertEquals(Decision.Bind(201), b.resolve(listOf(201, 60), nowMs = 1_150L))
    }

    @Test
    fun `same-package switch binds new payment window and never touches normal window`() {
        // 普通微信 #50 + 当前聚合支付微信收付款 #51；切到微信扫一扫
        val b = AggPaymentTaskBinding(
            startedAtMs = 1_000L,
            preExistingTaskIds = setOf(50, 51),
            closeTaskId = 51,
        )

        // 新扫一扫窗口 #52 出现前，不抢 #50，也不误把 #51 提前当新窗
        assertTrue(b.resolve(listOf(51, 50), nowMs = 1_050L) is Decision.Waiting)

        // #52 出现 → 立刻绑定 #52
        assertEquals(Decision.Bind(52), b.resolve(listOf(52, 51, 50), nowMs = 1_120L))

        // #51 被关掉后，用户切去 #50，条死死留在 #52
        assertEquals(
            Decision.Bind(52),
            b.resolve(listOf(50, 52), nowMs = 2_000L, eventTaskId = 50, eventIsUpdate = true),
        )
    }

    @Test
    fun `non-multi-freeform in-place reuse falls back only after delay and upgrades if new task arrives`() {
        val b = AggPaymentTaskBinding(
            startedAtMs = 1_000L,
            preExistingTaskIds = setOf(50),
            reuseFallbackMs = 450L,
        )

        // 未收到该窗更新事件时，即便过了 reuseFallbackMs 也不抢 untouched 普通窗
        assertTrue(b.resolve(listOf(50), nowMs = 1_600L) is Decision.Waiting)

        // MIUI 原地复用 #50 并发出置顶更新事件
        assertEquals(
            Decision.Bind(50),
            b.resolve(listOf(50), nowMs = 1_650L, eventTaskId = 50, eventIsUpdate = true),
        )

        // 如果落地期内真正的新小窗 #51 还是冒出来了，升级到 #51
        assertEquals(Decision.Bind(51), b.resolve(listOf(51, 50), nowMs = 1_900L))
    }

    @Test
    fun `same-package switch in-place reuse keeps closeTaskId and never steals unrelated normal window`() {
        val b = AggPaymentTaskBinding(
            startedAtMs = 1_000L,
            preExistingTaskIds = setOf(50, 51),
            closeTaskId = 51,
            reuseFallbackMs = 450L,
        )

        // 即使普通窗 #50 在切换期间被点到置顶，也不允许回退去抢 #50
        assertTrue(b.resolve(listOf(50, 51), nowMs = 1_200L, eventTaskId = 50, eventIsUpdate = true) is Decision.Waiting)

        // 过 reuseFallbackMs 后无新窗出现，只能回退保留原支付窗 #51（绝不是 #50）
        assertEquals(Decision.Bind(51), b.resolve(listOf(50, 51), nowMs = 1_500L))
    }
}
