package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class ValidationReportTest {
    private val run = ValidationFrameRun(200, 200, ValidationRoi(0, 0, 160, 160), listOf(
        ValidationCandidate(40.0, 40.0, 2.0, 18.0, 5, 1),
        ValidationCandidate(80.0, 80.0, 2.0, 17.0, 6, 2)
    ), 2.0, .2, 10.0, 1.0, 2.0)
    private fun report(complete: Boolean, truth: List<ValidationPoint> = listOf(ValidationPoint(40.0, 40.0))) =
        ValidationReport.json("video.mp4", "one-physical-piece", 2000, run, truth, 5.0, complete,
            null, emptyList(), "test", "test")

    @Test fun partialLabelsKeepUnlabelledCandidatesAndNullPrecisionRecall() {
        val report = report(false)
        val c = report.getJSONObject("comparison")
        assertEquals("marked_points_only", report.getString("truth_scope"))
        assertEquals("unlabelled", c.getString("extra_interpretation"))
        assertTrue(c.isNull("precision_this_frame")); assertTrue(c.isNull("recall_this_frame"))
        assertEquals(1, c.getJSONArray("extra_candidate_indices").length())
        assertEquals(2, report.getJSONObject("frame").getJSONArray("candidates").length())
    }
    @Test fun completeLabelsAllowFramePrecisionAndRecall() {
        val c = report(true).getJSONObject("comparison")
        assertEquals("false_positive_candidates", c.getString("extra_interpretation"))
        assertEquals(.5, c.getDouble("precision_this_frame"), .00001)
        assertEquals(1.0, c.getDouble("recall_this_frame"), .00001)
    }
    @Test fun zeroDefectsDoNotProducePerfectRecall() {
        val c = report(true, emptyList()).getJSONObject("comparison")
        assertEquals(0.0, c.getDouble("precision_this_frame"), .00001)
        assertTrue(c.isNull("recall_this_frame"))
    }
    @Test fun sampledFramesNeverReuseSingleFrameLabels() {
        val report = ValidationReport.json("video.mp4", "one-piece", 2000, run,
            listOf(ValidationPoint(40.0, 40.0)), 5.0, true, null,
            listOf(SampledValidationFrame(0, run), SampledValidationFrame(1000, null, "decode error")), "test", "test")
        val b = report.getJSONObject("sampled_video")
        assertFalse(b.getBoolean("annotation_applied"))
        assertEquals(2, b.getInt("requested_frames")); assertEquals(1, b.getInt("successful_frames"))
        assertEquals(2.0, b.getDouble("core_p95_ms"), .00001)
        assertFalse(b.getJSONArray("frames").getJSONObject(0).has("comparison"))
    }
    @Test fun selfTestOnlyReportHasNoInventedFrameMetrics() {
        val report = ValidationReport.json("self test", "", null, null, emptyList(), 5.0,
            false, NativeSuiteResult(listOf(NativeCheck("check", true, "test", 1.0)), listOf(2.0, 3.0)),
            emptyList(), "test", "test")
        assertFalse(report.has("frame")); assertFalse(report.has("comparison"))
        assertTrue(report.getJSONObject("native_self_test").getBoolean("passed"))
    }
}
