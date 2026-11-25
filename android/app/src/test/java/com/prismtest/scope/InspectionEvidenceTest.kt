package com.prismtest.scope

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class InspectionEvidenceTest {
    @Test fun retainsTheActualPeakFrameForEachTypeInsteadOfTheLastFrame() {
        val c = PeakFrameCollector<String, String>(setOf("dig", "scratch"))
        c.offer(mapOf("dig" to 8.0, "scratch" to 2.0)) { "frame-A: point at 0.2,0.4" }
        c.offer(mapOf("dig" to 1.0, "scratch" to 9.0)) { "frame-B: rotated piece" }
        c.offer(mapOf("dig" to 3.0, "scratch" to 3.0)) { error("Not a peak; must not copy") }
        val result = c.finish()
        assertEquals("frame-A: point at 0.2,0.4", result.peaks.getValue("dig").frame)
        assertEquals("frame-B: rotated piece", result.peaks.getValue("scratch").frame)
        assertEquals(8.0, result.peaks.getValue("dig").score, .001)
    }
    @Test fun copiesOnlyOnceWhenSeveralTypesPeakInOneFrame() {
        val c = PeakFrameCollector<String, Any>(setOf("a", "b")); var copies = 0
        c.offer(mapOf("a" to 1.0, "b" to 2.0)) { copies++; Any() }
        val r = c.finish()
        assertEquals(1, copies); assertSame(r.peaks.getValue("a").frame, r.peaks.getValue("b").frame)
    }
    @Test fun zeroScoreStillRetainsAnEvidencePhotoAndEqualScoresDoNotReplaceIt() {
        val c = PeakFrameCollector<String, Int>(setOf("dig"))
        c.offer(mapOf("dig" to 0.0)) { 1 }
        c.offer(mapOf("dig" to 0.0)) { error("Equal score must retain the first frame") }
        assertEquals(1, c.finish().peaks.getValue("dig").frame)
    }
    @Test fun finishingRejectsLateCameraFramesAndIsIdempotent() {
        val c = PeakFrameCollector<String, Int>(setOf("dig"))
        c.offer(mapOf("dig" to 1.0)) { 1 }; val saved = c.finish()
        assertFalse(c.offer(mapOf("dig" to 99.0)) { error("Late camera frame") })
        c.invalid { error("Closed") }
        assertSame(saved, c.finish()); assertEquals(1, saved.peaks.getValue("dig").frame)
    }
    @Test fun invalidFramesOnlyBecomeDiagnosticFallbackAndValidFramesReplaceThem() {
        val invalid = PeakFrameCollector<String, Int>(setOf("dig"))
        invalid.invalid { 3 }; invalid.invalid { error("Only one fallback") }
        assertTrue(invalid.finish().peaks.isEmpty()); assertEquals(3, invalid.finish().fallback)
        val valid = PeakFrameCollector<String, Int>(setOf("dig"))
        valid.invalid { 3 }; valid.offer(mapOf("dig" to 1.0)) { 4 }
        assertNull(valid.finish().fallback); assertEquals(4, valid.finish().peaks.getValue("dig").frame)
    }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteScoresCannotBecomePeakEvidence() {
        PeakFrameCollector<String, Int>(setOf("dig")).offer(mapOf("dig" to Double.NaN)) { 1 }
    }
    @Test fun unselectedTypesAreNotRetained() {
        val c = PeakFrameCollector<String, Int>(setOf("dig"))
        c.offer(mapOf("dig" to 2.0, "scratch" to 99.0)) { 1 }
        assertEquals(setOf("dig"), c.finish().peaks.keys)
    }
    @Test fun lumaCopyHonoursCropOffsetPaddingAndPixelStrideWithoutMovingTheBuffer() {
        val b = ByteBuffer.allocateDirect(70)
        for (y in 0..2) for (x in 0..3) b.put(9 + y * 17 + x * 2, (y * 10 + x).toByte())
        b.position(4)
        val copy = GraySnapshot.copy(b, 9, 17, 2, 4, 3, 90)
        assertArrayEquals(byteArrayOf(0,1,2,3,10,11,12,13,20,21,22,23), copy.pixels)
        assertEquals(4, b.position()); assertEquals(3, copy.displayWidth); assertEquals(4, copy.displayHeight)
        b.put(9, 99); assertEquals(0, copy.pixels[0].toInt())
    }
    @Test fun contiguousRowsWithPaddingAndRotationNormalize() {
        val b = ByteBuffer.wrap(byteArrayOf(99,1,2,3,99,99,4,5,6))
        val c = GraySnapshot.copy(b, 1, 5, 1, 3, 2, -90)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6), c.pixels); assertEquals(270, c.rotation)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedBuffersAreRejected() {
        GraySnapshot.copy(ByteBuffer.allocate(10), 0, 8, 2, 4, 2, 0)
    }
    @Test fun rotatedRoiOverlayUsesTheSameInclusiveBufferPixelsAsAnalysis() {
        val roi = Roi(.25f, .75f, .4f)
        assertEquals(PixelBounds(130, 55, 170, 95), RoiGeometry.buffer(roi, 200, 100, 90))
        val r = RoiGeometry.display(roi, 200, 100, 90)
        assertEquals(.04f, r.left, .00001f); assertEquals(.65f, r.top, .00001f)
        assertEquals(.45f, r.right, .00001f); assertEquals(.855f, r.bottom, .00001f)
    }
    @Test fun clippedRoiDoesNotPaintOutsideTheCapturedImage() {
        val r = RoiGeometry.display(Roi(0f, 0f, .8f), 200, 100, 0)
        assertEquals(0f, r.left, .00001f); assertEquals(0f, r.top, .00001f)
        assertEquals(.205f, r.right, .00001f); assertEquals(.41f, r.bottom, .00001f)
    }
}
