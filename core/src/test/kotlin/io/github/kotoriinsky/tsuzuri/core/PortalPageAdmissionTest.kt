package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalPageAdmissionTest {

    @Test
    fun disabledFeatureAlwaysKeepsRomResult() {
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = false,
                textTargetEnabled = true,
                pageConfigPresent = false,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.ABSENT,
            ),
        )
    }

    @Test
    fun disabledRomTextTargetAlwaysKeepsRomResult() {
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = false,
                pageConfigPresent = false,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.ABSENT,
            ),
        )
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = null,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.EMPTY,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
        )
    }

    @Test
    fun missingPageConfigNeedsTextFallback() {
        assertEquals(
            PortalPageAdmission.Verdict.INJECT_TEXT_FALLBACK,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = false,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.ABSENT,
            ),
        )
    }

    @Test
    fun twoEmptyListsNeedTextFallback() {
        assertEquals(
            PortalPageAdmission.Verdict.INJECT_TEXT_FALLBACK,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.EMPTY,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
        )
    }

    @Test
    fun anyRomEntryKeepsTheOriginalConfiguration() {
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.NON_EMPTY,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
        )
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.EMPTY,
                catchers = PortalPageAdmission.ListState.NON_EMPTY,
            ),
        )
    }

    @Test
    fun malformedRomListsFailClosed() {
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
        )
        assertEquals(
            PortalPageAdmission.Verdict.KEEP_ROM,
            PortalPageAdmission.decide(
                enabled = true,
                textTargetEnabled = true,
                pageConfigPresent = true,
                features = PortalPageAdmission.ListState.UNREADABLE,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
        )
    }
}
