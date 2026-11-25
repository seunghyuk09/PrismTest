package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class CaptureQualityTest {
    @Test fun explicitVideoTiersReachRealUhdSourcePixels() {
        assertEquals(listOf(FrameSize(1280,720),FrameSize(1920,1080),FrameSize(2560,1440),FrameSize(3840,2160)),
            CaptureQuality.values().map { it.target })
        assertEquals(8_294_400L, CaptureQuality.UHD.target.pixels)
    }
    @Test fun tierFiltersPreserveSupportedOrderWithoutAn1080pPreviewCap() {
        val sizes = listOf(FrameSize(3840,2160),FrameSize(4000,3000),FrameSize(1920,1080),FrameSize(1280,720))
        assertEquals(listOf(sizes[0],sizes[2],sizes[3]), CaptureQuality.UHD.bounded(sizes))
        assertEquals(sizes.drop(2), CaptureQuality.FHD.bounded(sizes))
        assertEquals(listOf(sizes[3]), CaptureQuality.HD.bounded(sizes))
    }
    @Test fun smallerCameraIsNotPretendedToSupportUhd() {
        val only = listOf(FrameSize(640,480))
        assertEquals(only, CaptureQuality.UHD.bounded(only))
    }
    @Test fun globalPixelBudgetRejectsOversizedAndInvalidBuffers() {
        assertTrue(CaptureQuality.UHD.bounded(emptyList()).isEmpty())
        assertTrue(CaptureQuality.UHD.bounded(listOf(FrameSize(0,100),FrameSize(8000,6000))).isEmpty())
        assertEquals(listOf(FrameSize(2560,1440)), CaptureQuality.HD.bounded(listOf(FrameSize(2560,1440))))
    }
    @Test fun hardwareFailureCanFallBackOneTierAtATime() {
        assertEquals(CaptureQuality.QHD, CaptureQuality.UHD.lower())
        assertEquals(CaptureQuality.FHD, CaptureQuality.QHD.lower())
        assertEquals(CaptureQuality.HD, CaptureQuality.FHD.lower())
        assertNull(CaptureQuality.HD.lower())
    }
    @Test fun existingPreferencesMigrateAndNewUsersStartAtFhd() {
        assertEquals(CaptureQuality.HD, CaptureQuality.of("standard"))
        assertEquals(CaptureQuality.UHD, CaptureQuality.of("detail"))
        assertEquals(CaptureQuality.UHD, CaptureQuality.of("4k"))
        assertEquals(CaptureQuality.FHD, CaptureQuality.of(null))
        assertEquals(CaptureQuality.FHD, CaptureQuality.of("unknown"))
    }
}
