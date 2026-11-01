package io.github.kotoriinsky.tsuzuri.core

/** 获取时钟独立于长按阈值，所有请求共用一个预算。 */
class PortalMediaAcquisition(private val startedMs: Long) {
    enum class Status { ACQUIRING, COMPLETED, CANCELLED }
    @Volatile var status = Status.ACQUIRING
        private set
    fun remainingMs(nowMs: Long): Int = (3000L - (nowMs - startedMs).coerceAtLeast(0)).coerceIn(0, 3000).toInt()
    @Synchronized fun completeOriginal(nowMs: Long): Boolean {
        if (status != Status.ACQUIRING || remainingMs(nowMs) == 0) return false
        status = Status.COMPLETED
        return true
    }
    @Synchronized fun completeFallback(): Boolean {
        if (status != Status.ACQUIRING) return false
        status = Status.COMPLETED
        return true
    }
    @Synchronized fun cancel() { status = Status.CANCELLED }
}
