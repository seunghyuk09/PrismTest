package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class FrameTransformTest {
    @Test fun everyRotationRoundTripsOffCenterPoints() {
        for (angle in listOf(0, 90, 180, 270, -90, 450)) {
            for ((x, y) in listOf(0f to 0f, 1f to 1f, 0.2f to 0.7f, 0.9f to 0.1f)) {
                val (bx, by) = FrameTransform.toBuffer(x, y, angle)
                val (rx, ry) = FrameTransform.toDisplay(bx, by, angle)
                assertEquals(x, rx, 0.00001f)
                assertEquals(y, ry, 0.00001f)
            }
        }
    }
    @Test fun portraitMappingMatchesClockwiseRotation() {
        val (x, y) = FrameTransform.toDisplay(0.2f, 0.7f, 90)
        assertEquals(0.3f, x, 0.00001f)
        assertEquals(0.2f, y, 0.00001f)
    }
}
