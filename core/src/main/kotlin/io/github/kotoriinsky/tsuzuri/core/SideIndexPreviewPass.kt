package io.github.kotoriinsky.tsuzuri.core

/**
 * 侧滑索引「触发」页的**临时通行**契约（设置界面 → SystemUI）。
 *
 * ## 为什么需要它
 *
 * 侧滑索引默认仅在桌面生效（`scope = launcher_only`），而「触发」页在屏幕边缘高亮了
 * 真实热区，用户调完宽度/边缘/振动后想就地划一下验手感，却因为前台包名是本模块自己
 * 而被 `SideIndexScope` 拦住。
 *
 * ## 安全与生命周期约束
 *
 * 1. **只放行本模块包名**（[ALLOWED_PACKAGE]），切到任何其他应用立即失效；
 * 2. **有硬租期上限**（[LEASE_MS] / [MAX_LEASE_MS]），页面停留期间定期续期，离开页面
 *    立刻写 `0L` 撤销；即使设置进程被强杀，超时后自动关闭；
 * 3. **瞬态内部键**（`__` 前缀），走 `ConfigStore.signal` 只写远端、不进草稿、不触发重载，
 *    且自动被 [BackupRestorePlan.isBackupConfigKey] 排除出用户备份。
 */
object SideIndexPreviewPass {

    /** 跨进程瞬态信号键（Long，到期时间 epoch ms；`0L` 表示关闭）。 */
    const val SIGNAL_KEY = "__side_index_preview_pass_until"

    /** 仅允许对本模块设置页临时放行。 */
    const val ALLOWED_PACKAGE = "io.github.kotoriinsky.tsuzuri"

    /** 单次租期（毫秒）。 */
    const val LEASE_MS = 45_000L

    /** 页面开启期间的心跳续期间隔（毫秒）。 */
    const val RENEW_INTERVAL_MS = 20_000L

    /** 租期硬上限（毫秒），防止时钟异常或脏数据导致长期放行。 */
    const val MAX_LEASE_MS = 120_000L

    /** 当前是否处于有效的临时通行窗口内。 */
    fun isActive(foreground: String?, untilEpochMs: Long, nowEpochMs: Long): Boolean {
        if (foreground != ALLOWED_PACKAGE) return false
        val remaining = untilEpochMs - nowEpochMs
        return remaining in 1L..MAX_LEASE_MS
    }
}
