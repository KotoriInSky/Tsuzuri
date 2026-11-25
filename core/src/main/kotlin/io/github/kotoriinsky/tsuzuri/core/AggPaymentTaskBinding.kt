package io.github.kotoriinsky.tsuzuri.core

/**
 * 聚合支付入口条对**具体小窗 taskId** 的绑定状态机（纯逻辑，零 Android 依赖）。
 *
 * ## 为什么不能按包名每次取 Z 序最上层的小窗
 *
 * 早期版本假设「同包最多只有一个小窗」，于是每次收到 WM Shell 事件都按包名取
 * `getFreeformTasksInZOrder()` 里的第一个小窗。开启「小窗多开」后这个假设破产：
 *
 *  1. 切到同包的普通小窗（例如普通微信聊天小窗）时，入口条会从支付小窗跳过去；
 *  2. 关闭支付小窗时，因为普通小窗还在，入口条不会收起，反而寄生在普通小窗上；
 *  3. 在错绑的普通小窗上点切换条目，`switchTo` 会把普通小窗当成旧支付窗 `removeTask` 误杀。
 *
 * ## 两阶段规则（兼顾支付宝跳板链与非多开原地复用）
 *
 *  - **排除既有窗**：会话启动前快照同包已存在的小窗集合 [preExistingTaskIds]，绝不抢挂它们；
 *  - **落地期（[settleMs] 内）允许跳板接力**：支付宝 1002 会连起 2~3 个同包新 Task，
 *    允许在落地期内向「不在 [preExistingTaskIds] 且未退役」的新 taskId 接力升级；
 *  - **稳态锁死**：一旦超过 [settleMs]，严格只认已绑定的 `boundTaskId` —— 同包其他小窗置顶
 *    或新开都不抢绑；`boundTaskId` 一旦消失立刻返回 [Decision.Gone] 收起入口条；
 *  - **非多开原地复用兜底**：未开多开且 MIUI 直接复用了已有小窗（没有新 taskId 产生）时，
 *    仅在 [reuseFallbackMs] 后、且某个既有小窗确实收到了启动后的系统更新事件（或同包切换复用了
 *    [closeTaskId]）时才允许回退绑定；之后若真有新 taskId 出现仍会在落地期内升级过去。
 */
class AggPaymentTaskBinding(
    val startedAtMs: Long,
    preExistingTaskIds: Set<Int>,
    private val closeTaskId: Int? = null,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
    private val reuseFallbackMs: Long = DEFAULT_REUSE_FALLBACK_MS,
) {
    /** 启动前就已经存在的同包小窗（不含待关闭的旧支付窗也一样要排除）。 */
    private val preExisting: Set<Int> = preExistingTaskIds.toSet()

    /** 曾经绑定过但已被跳板接力替代或已消失的 taskId，防止回跳到快死的旧跳板。 */
    private val retired = LinkedHashSet<Int>()

    /** 当前绑定的小窗 taskId。 */
    var boundTaskId: Int? = null
        private set

    /** 当前绑定是否来自「非多开原地复用」兜底。 */
    private var boundFromFallback = false

    /** 是否曾经绑定过一个真正的**新**小窗（一旦为 true，即使它在落地期内关闭也绝不回退去抢普通小窗）。 */
    private var everBoundNew = false

    /** 启动后确实收到过系统更新/置顶事件的既有小窗（用于区分「MIUI 原地复用」与「 untouched 普通窗」）。 */
    private var touchedPreExistingId: Int? = null

    sealed interface Decision {
        /** 绑定到指定小窗（调用方应只查这个 taskId 的信息，不再按包名扫 Z 序）。 */
        data class Bind(val taskId: Int) : Decision

        /**
         * 仍在等待新小窗或跳板下一跳落地。
         *
         * @param recheckAfterMs 建议调用方在这段时间后主动复检一次（用于兜底或落地超时收尾），null = 无需定时。
         * @param previousVanished true = 先前绑定的小窗刚消失，正在短暂等跳板下一跳（若仍挂在死窗上可先隐藏）。
         */
        data class Waiting(
            val recheckAfterMs: Long? = null,
            val previousVanished: Boolean = false,
        ) : Decision

        /** 支付小窗已关闭或超时未出现，会话应结束并收起入口条。 */
        object Gone : Decision
    }

    /**
     * 根据当前同包小窗列表（Z 序，顶层在前）决定本次应绑定的小窗。
     *
     * @param samePkgTasksInZOrder 当前仍在小窗仓库里的同包 taskId（顶层在前）
     * @param nowMs 当前时间戳（毫秒，与 [startedAtMs] 同源）
     * @param eventTaskId 触发本次刷新的 taskId（`onTaskAppeared` / `onOpenTransitionReady` / `onTaskInfoChanged`），
     *   轮询或 `start` 传 null
     * @param eventIsUpdate 该事件是否表示系统正在打开/更新/置顶该小窗（区别于 `start`/`poll`/`onTaskVanished`）
     */
    fun resolve(
        samePkgTasksInZOrder: List<Int>,
        nowMs: Long,
        eventTaskId: Int? = null,
        eventIsUpdate: Boolean = false,
    ): Decision {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        val inSettle = elapsed <= settleMs

        // 记录「确实被本次启动触碰过的既有同包窗」（要求事件发生在启动之后，且该窗处于同包顶层）
        if (eventIsUpdate && eventTaskId != null && eventTaskId in preExisting &&
            samePkgTasksInZOrder.firstOrNull() == eventTaskId
        ) {
            if (isAllowedReuseCandidate(eventTaskId)) {
                touchedPreExistingId = eventTaskId
            }
        }

        // 所有不在既有窗、未退役、非待关旧窗的「真正新小窗」
        val newCandidates = samePkgTasksInZOrder.filter { id ->
            id !in preExisting && id !in retired && id != closeTaskId
        }

        val current = boundTaskId
        if (current != null) {
            val stillAlive = current in samePkgTasksInZOrder
            if (stillAlive) {
                // 落地期内若有更新的「真正新小窗」（跳板链 Task A → B → C，或兜底绑定后新窗终于到达），接力过去
                if (inSettle) {
                    val successor = newCandidates.firstOrNull { it != current }
                    if (successor != null) {
                        if (!boundFromFallback) retired.add(current)
                        boundTaskId = successor
                        boundFromFallback = false
                        everBoundNew = true
                        return Decision.Bind(successor)
                    }
                }
                return Decision.Bind(current)
            }

            // 已绑定的小窗消失了
            if (!boundFromFallback) retired.add(current)
            boundTaskId = null
            boundFromFallback = false

            if (inSettle) {
                // 跳板前一跳刚消失、下一跳可能已经出现
                val successor = newCandidates.firstOrNull()
                if (successor != null) {
                    boundTaskId = successor
                    everBoundNew = true
                    return Decision.Bind(successor)
                }
                return Decision.Waiting(
                    recheckAfterMs = (settleMs - elapsed).coerceAtLeast(1L) + RECHECK_MARGIN_MS,
                    previousVanished = true,
                )
            }
            return Decision.Gone
        }

        // 尚未绑定到任何小窗：优先认真正的新小窗
        val fresh = newCandidates.firstOrNull()
        if (fresh != null) {
            boundTaskId = fresh
            boundFromFallback = false
            everBoundNew = true
            return Decision.Bind(fresh)
        }

        // 未出现新小窗：检查是否满足「非多开原地复用」兜底条件
        if (!everBoundNew) {
            val fallbackId = pickReuseFallback(samePkgTasksInZOrder)
            if (fallbackId != null) {
                if (elapsed >= reuseFallbackMs) {
                    boundTaskId = fallbackId
                    boundFromFallback = true
                    return Decision.Bind(fallbackId)
                }
                return Decision.Waiting(
                    recheckAfterMs = (reuseFallbackMs - elapsed).coerceAtLeast(1L) + RECHECK_MARGIN_MS,
                )
            }
        }

        return if (inSettle) {
            Decision.Waiting(
                recheckAfterMs = (settleMs - elapsed).coerceAtLeast(1L) + RECHECK_MARGIN_MS,
                previousVanished = everBoundNew,
            )
        } else {
            Decision.Gone
        }
    }

    /**
     * 同包切换时（[closeTaskId] 本身就在 [preExisting] 里），若 MIUI 原地复用，
     * 只可能复用我们原来的支付窗 [closeTaskId]，绝不能去抢旁边开着的普通同包窗。
     */
    private fun isAllowedReuseCandidate(taskId: Int): Boolean {
        if (taskId !in preExisting) return false
        if (closeTaskId != null && closeTaskId in preExisting) {
            return taskId == closeTaskId
        }
        return true
    }

    private fun pickReuseFallback(samePkgTasksInZOrder: List<Int>): Int? {
        val touched = touchedPreExistingId
        if (touched != null && touched in samePkgTasksInZOrder && isAllowedReuseCandidate(touched)) {
            return touched
        }
        // 同包切换场景：原地复用时 MIUI 可能只切 Activity 而不重发焦点变更，允许回退保留原支付窗
        if (closeTaskId != null && closeTaskId in preExisting && closeTaskId in samePkgTasksInZOrder) {
            return closeTaskId
        }
        return null
    }

    companion object {
        const val DEFAULT_SETTLE_MS = 3_000L
        const val DEFAULT_REUSE_FALLBACK_MS = 450L
        private const val RECHECK_MARGIN_MS = 40L
    }
}
