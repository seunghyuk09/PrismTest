package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class CaptureQualityTest {
    @Test fun detailRequestsFourTimesTheStandardPixels() {
        assertEquals(FrameSize(1280, 960), CaptureQuality.STANDARD.target)
        assertEquals(FrameSize(2560, 1920), CaptureQuality.DETAIL.target)
        assertEquals(CaptureQuality.STANDARD.target.pixels * 4, CaptureQuality.DETAIL.target.pixels)
    }
    @Test fun boundsRetainOnlySupportedSizesWithoutReorderingThem() {
        val input = listOf(FrameSize(2560,1920), FrameSize(4000,3000), FrameSize(1920,1440), FrameSize(1280,960))
        assertEquals(listOf(input[0],input[2],input[3]), CaptureQuality.DETAIL.bounded(input))
        assertEquals(listOf(input[2],input[3]), CaptureQuality.STANDARD.bounded(input))
    }
    @Test fun previewDoesNotRequestTheFullDetailAnalysisBudget() {
        val input = listOf(FrameSize(2560,1920), FrameSize(1920,1440), FrameSize(640,480))
        assertEquals(input.drop(1), CaptureQuality.DETAIL.bounded(input, preview = true))
    }
    @Test fun smallerCameraIsNotPretendedToSupportTheRequestedSize() {
        val only = listOf(FrameSize(640,480))
        assertEquals(only, CaptureQuality.DETAIL.bounded(only))
    }
    @Test fun emptyOrInvalidListsStayEmptyAndOversizedOnlyCameraKeepsItsSmallestOption() {
        assertTrue(CaptureQuality.DETAIL.bounded(emptyList()).isEmpty())
        assertTrue(CaptureQuality.DETAIL.bounded(listOf(FrameSize(0,100))).isEmpty())
        assertEquals(listOf(FrameSize(4000,3000)), CaptureQuality.DETAIL.bounded(listOf(FrameSize(8000,6000),FrameSize(4000,3000))))
    }
    @Test fun unknownPersistedModesUseStandard() {
        assertEquals(CaptureQuality.STANDARD, CaptureQuality.of(null))
        assertEquals(CaptureQuality.STANDARD, CaptureQuality.of("unknown"))
        assertEquals(CaptureQuality.DETAIL, CaptureQuality.of("detail"))
    }
}
