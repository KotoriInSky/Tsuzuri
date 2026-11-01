package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalMediaAcquisitionTest {
    @Test fun retriesShareOneBudgetInsteadOfResettingThreeSeconds() {
        val session = PortalMediaAcquisition(100)
        assertEquals(3000, session.remainingMs(100))
        assertEquals(1700, session.remainingMs(1400))
        assertEquals(0, session.remainingMs(3100))
    }
    @Test fun lateOriginalCannotReplaceTimeoutFallback() {
        val session = PortalMediaAcquisition(100)
        assertTrue(session.completeFallback())
        assertFalse(session.completeOriginal(200))
        assertEquals(PortalMediaAcquisition.Status.COMPLETED, session.status)
    }
    @Test fun resultAtDeadlineMustFallBackAndCompleteOnlyOnce() {
        val session = PortalMediaAcquisition(100)
        assertFalse(session.completeOriginal(3100))
        assertTrue(session.completeFallback())
        assertFalse(session.completeFallback())
    }
    @Test fun cancelledSessionCannotPublishAnyResult() {
        val session = PortalMediaAcquisition(100)
        session.cancel()
        assertFalse(session.completeOriginal(101))
        assertFalse(session.completeFallback())
        assertEquals(PortalMediaAcquisition.Status.CANCELLED, session.status)
    }
    @Test fun originalCompletesOnceBeforeDeadline() {
        val session = PortalMediaAcquisition(100)
        assertTrue(session.completeOriginal(3099))
        assertFalse(session.completeOriginal(3099))
        assertFalse(session.completeFallback())
    }
}
