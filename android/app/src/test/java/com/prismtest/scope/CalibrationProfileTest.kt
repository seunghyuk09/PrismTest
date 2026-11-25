package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class CalibrationProfileTest {
    private fun key(iso: Int = 100, exposure: Long = 10_000_000L, focus: Float = 4f,
                    zoom: Float = 2f, width: Int = 720, white: String = "1,1,1,1") =
        CalibrationProfile.key("Fold7", "0", width, 960, iso, exposure, focus, zoom, white)
    @Test fun sameControlsAreRepeatable() { assertEquals(key(), key()) }
    @Test fun cameraControlChangesInvalidateOldCriteria() {
        for (changed in listOf(key(iso = 200), key(exposure = 20_000_000), key(focus = 5f),
                               key(zoom = 3f), key(width = 960), key(white = "2,1,1,1"))) {
            assertNotEquals(key(), changed)
        }
    }
    @Test fun changingCoverageOrTrackingModeRequiresMatchingCalibration() {
        fun roiKey(size: Float, tracking: Boolean) = CalibrationProfile.key(
            "Fold7", "0", 720, 960, 100, 10_000_000L, 4f, 2f, "1,1,1,1", size, tracking)
        assertNotEquals(roiKey(.45f, false), roiKey(.65f, false))
        assertNotEquals(roiKey(.65f, false), roiKey(.65f, true))
        assertEquals(roiKey(.65f, true), roiKey(.65f, true))
    }
}
