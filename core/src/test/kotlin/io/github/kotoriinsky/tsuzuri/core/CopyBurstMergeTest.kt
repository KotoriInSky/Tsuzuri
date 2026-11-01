package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「同一次复制被写了两次」的合并判定（[PasscodeIsland.isSameCopyBurst]）。
 *
 * 真实场景（2026-10-06 用户报「知乎跳两个岛」，发射侧日志）：一次复制在 **181ms** 内
 * 先写 `www.zhihu.com/question/…/answer/…`、再写 `oia.zhihu.com/km_paid_content/share?…`。
 * 两条 URL 不同 → 身份不同 → 防抖拦不住 → 两个岛。所以窗口内的**同平台**后一条要顶掉前一条。
 */
class CopyBurstMergeTest {

    private val zhihu = "com.zhihu.android"
    private val taobao = "com.taobao.taobao"

    @Test
    fun `窗口内同平台算同一次复制`() {
        assertTrue(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, zhihu, 1_181L))
        assertTrue(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, zhihu, 1_000L))
        // 边界：正好等于窗口长度算「超出」（与 isDuplicate 的半开区间一致）
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, zhihu, 1_400L))
    }

    /** 用户连着复制两条**不同平台**的链接时必须各出一条岛。 */
    @Test
    fun `不同平台不合并`() {
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, taobao, 1_100L))
    }

    @Test
    fun `缺平台包时不合并`() {
        assertFalse(PasscodeIsland.isSameCopyBurst(null, 1_000L, zhihu, 1_100L))
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, null, 1_100L))
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 1_000L, "  ", 1_100L))
    }

    /** 时钟倒退（或首条还没记录）不算合并 —— 与 isDuplicate 同一条纪律。 */
    @Test
    fun `时钟异常不合并`() {
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 0L, zhihu, 100L))
        assertFalse(PasscodeIsland.isSameCopyBurst(zhihu, 2_000L, zhihu, 1_900L))
    }
}
