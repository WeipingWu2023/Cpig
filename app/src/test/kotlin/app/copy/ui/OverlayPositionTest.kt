package app.copy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test fun dockAndClampSurvivePanelResizeAndRotation() {
        assertEquals(OverlayPosition(352, 100), fitOverlay(1, 100, 48, 48, 400, 800, true))
        assertEquals(OverlayPosition(168, 100), fitOverlay(352, 100, 232, 180, 400, 800, true))
        assertEquals(OverlayPosition(752, 100), fitOverlay(168, 100, 48, 48, 800, 400, true))
        assertEquals(OverlayPosition(0, 352), fitOverlay(752, 900, 48, 48, 800, 400, false))
        assertEquals(OverlayPosition(0, 0), fitOverlay(-100, -50, 232, 180, 200, 100))
        assertEquals(OverlayPosition(352, 752), fitOverlay(999, 999, 48, 48, 400, 800))
    }
}
