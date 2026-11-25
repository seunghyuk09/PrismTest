package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class ValidationMetricsTest {
    private fun p(x: Double, y: Double = 0.0) = ValidationPoint(x, y)
    @Test fun exactLocationsMatchOneToOne() {
        val r = ValidationMetrics.compare(listOf(p(1.0), p(8.0)), listOf(p(1.5), p(7.5)), 1.0, true)
        assertEquals(2, r.matches.size); assertTrue(r.missed.isEmpty()); assertTrue(r.extra.isEmpty())
        assertEquals(1.0, r.precision!!, .0001); assertEquals(1.0, r.recall!!, .0001)
    }
    @Test fun oneCandidateCannotExplainTwoLabels() {
        val r = ValidationMetrics.compare(listOf(p(1.0), p(1.1)), listOf(p(1.05)), 1.0, true)
        assertEquals(1, r.matches.size); assertEquals(1, r.missed.size)
    }
    @Test fun ambiguousNearestPairUsesMaximumMatching() {
        val r = ValidationMetrics.compare(listOf(p(1.0), p(0.0)), listOf(p(.5), p(2.0)), 1.1, true)
        assertEquals(2, r.matches.size)
    }
    @Test fun outsideToleranceIsMissedAndExtra() {
        val r = ValidationMetrics.compare(listOf(p(0.0)), listOf(p(2.0)), 1.0, true)
        assertTrue(r.matches.isEmpty()); assertEquals(listOf(0), r.missed); assertEquals(listOf(0), r.extra)
    }
    @Test fun partialLabelsDoNotInventFalsePositiveRate() {
        val r = ValidationMetrics.compare(listOf(p(1.0)), listOf(p(1.0), p(9.0)), 1.0, false)
        assertEquals(1, r.extra.size); assertNull(r.precision); assertNull(r.recall)
        assertEquals(1.0, r.labelledPointAgreement!!, .0001)
    }
    @Test fun emptyTruthNeverMeansHundredPercentRecall() {
        val r = ValidationMetrics.compare(emptyList(), emptyList(), 1.0, true)
        assertNull(r.precision); assertNull(r.recall); assertNull(r.labelledPointAgreement)
        val extra = ValidationMetrics.compare(emptyList(), listOf(p(2.0)), 1.0, true)
        assertEquals(0.0, extra.precision!!, .0001); assertNull(extra.recall)
    }
    @Test fun emptyOutputMissesEveryLabel() {
        val r = ValidationMetrics.compare(listOf(p(1.0), p(5.0)), emptyList(), 1.0, true)
        assertEquals(2, r.missed.size); assertEquals(0.0, r.recall!!, .0001)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCoordinatesAreRejected() {
        ValidationMetrics.compare(listOf(p(Double.NaN)), emptyList(), 1.0, true)
    }
    @Test(expected = IllegalArgumentException::class) fun zeroToleranceIsRejected() {
        ValidationMetrics.compare(emptyList(), emptyList(), 0.0, true)
    }
    @Test fun roiMustBeInsideImageAndBounded() {
        assertTrue(ValidationRoi(20, 20, 180, 180).validFor(200, 200))
        assertFalse(ValidationRoi(-1, 0, 100, 100).validFor(200, 200))
        assertFalse(ValidationRoi(0, 0, 201, 100).validFor(200, 200))
        assertFalse(ValidationRoi(0, 0, 10, 100).validFor(200, 200))
        assertFalse(ValidationRoi(0, 0, 2000, 2000).validFor(3000, 3000))
    }
    @Test fun percentilesFilterInvalidDurations() {
        assertEquals(20.0, ValidationMetrics.percentile((1..40).map { it.toDouble() }, .5)!!, .001)
        assertEquals(38.0, ValidationMetrics.percentile((1..40).map { it.toDouble() }, .95)!!, .001)
        assertEquals(3.0, ValidationMetrics.percentile(listOf(-1.0, Double.NaN, 3.0), .95)!!, .001)
        assertNull(ValidationMetrics.percentile(emptyList(), .95))
    }
    @Test fun videoSamplingIsCappedAndNeverRequestsPastEnd() {
        assertEquals(listOf(0L), ValidationMetrics.sampleTimes(1))
        val times = ValidationMetrics.sampleTimes(3_600_000)
        assertEquals(30, times.size); assertEquals(0L, times.first()); assertEquals(3_599_999L, times.last())
        assertEquals(times.distinct(), times)
    }
}
