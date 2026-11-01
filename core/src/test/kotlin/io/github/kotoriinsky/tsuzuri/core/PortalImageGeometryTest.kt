package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalImageGeometryTest {
    @Test fun tallImageKeepsItsVisibleBoundsInsteadOfTouchSquare() {
        val rect = PortalImageGeometry.Bounds(30, 100, 1190, 9200)
        assertEquals(PortalImageGeometry.Bounds(30, 100, 1190, 2600), PortalImageGeometry.visible(rect, 1220, 2600))
    }
    @Test fun bottomNavigationCropDoesNotMoveTheScreenshotOrigin() {
        val rect = PortalImageGeometry.Bounds(20, 300, 1200, 2630)
        assertEquals(PortalImageGeometry.Bounds(20, 300, 1200, 2562), PortalImageGeometry.visible(rect, 1220, 2562))
    }
    @Test fun imagePartlyAboveScreenKeepsOnlyVisibleContent() {
        assertEquals(PortalImageGeometry.Bounds(100, 0, 900, 500),
            PortalImageGeometry.visible(PortalImageGeometry.Bounds(100, -200, 900, 500), 1220, 2656))
    }
    @Test fun invalidAndOffScreenBoundsDoNotBecomeOnePixelCrops() {
        assertNull(PortalImageGeometry.visible(PortalImageGeometry.Bounds(1300, 0, 1500, 200), 1220, 2656))
        assertNull(PortalImageGeometry.visible(PortalImageGeometry.Bounds(100, 0, 90, 200), 1220, 2656))
        assertNull(PortalImageGeometry.visible(PortalImageGeometry.Bounds(0, 0, 100, 100), 0, 2656))
    }
}
