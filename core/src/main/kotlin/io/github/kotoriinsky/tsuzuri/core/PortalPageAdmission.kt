package io.github.kotoriinsky.tsuzuri.core

/**
 * 传送门页面配置的兜底准入判定。
 *
 * 这里只描述是否需要补一份通用文本配置，不依赖厂商类。任何无法明确证明为
 * “无配置”的状态都保留 ROM 原结果，避免覆盖厂商已经下发的页面规则。
 */
object PortalPageAdmission {

    enum class ListState {
        ABSENT,
        EMPTY,
        NON_EMPTY,
        UNREADABLE,
    }

    enum class Verdict {
        KEEP_ROM,
        INJECT_TEXT_FALLBACK,
    }

    fun decide(
        enabled: Boolean,
        textTargetEnabled: Boolean?,
        pageConfigPresent: Boolean,
        features: ListState,
        catchers: ListState,
    ): Verdict {
        if (!enabled || textTargetEnabled != true) return Verdict.KEEP_ROM
        if (!pageConfigPresent) return Verdict.INJECT_TEXT_FALLBACK
        return if (features == ListState.EMPTY && catchers == ListState.EMPTY) {
            Verdict.INJECT_TEXT_FALLBACK
        } else {
            Verdict.KEEP_ROM
        }
    }
}
