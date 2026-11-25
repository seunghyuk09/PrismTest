package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class InspectionJsonTest {
    private fun frame(timestamp: Long, x: Float) = EvidenceFrame(GraySnapshot(40, 60, 90, ByteArray(2400)),
        timestamp, "test-time", Roi(.5f, .5f, .5f),
        RoiStats.EMPTY.copy(dig = DigResult(10.0, 0.0, 10.0, listOf(DigSpot(x, .4f, .02f, .02f, 20.0, 4)))),
        AppliedCamera(), ManualSettings(), "test-profile")
    private fun record(types: List<InspectionTypeResult>, issue: String? = null) = InspectionRecord("test", "start", "end", 8,
        Face.ALL, "test-note", "test", "test-profile", if (issue == null) Verdict.NOT_READY else Verdict.RECHECK,
        issue, 20, 100.0, types)
    private fun result(type: DefectType, f: EvidenceFrame?) = InspectionTypeResult(type, 10.0,
        Verdict.NOT_READY, Verdict.NOT_READY, DefectModel(), f, f != null)

    @Test fun eachTypeCarriesItsOwnPeakTimestampCoordinatesAndPhotos() {
        val dig = result(DefectType.DIG, frame(123, .2f))
        val scratch = result(DefectType.SCRATCH, frame(456, .8f))
        val photos = SavedEvidenceFiles("content://media/raw", "content://media/marked", "raw.png", "marked.png")
        val json = InspectionJson.encode(record(listOf(dig, scratch)), mapOf(DefectType.DIG to photos))
        val a = json.getJSONArray("types").getJSONObject(0)
        val b = json.getJSONArray("types").getJSONObject(1)
        assertEquals(123, a.getJSONObject("frame").getLong("camera_timestamp_ns"))
        assertEquals(456, b.getJSONObject("frame").getLong("camera_timestamp_ns"))
        assertEquals(.2, a.getJSONObject("frame").getJSONArray("dig_candidates").getJSONObject(0).getDouble("cx"), .0001)
        assertEquals("raw.png", a.getJSONObject("photos").getString("original_name"))
        assertEquals("dig_candidates", a.getString("location_mode"))
        assertEquals("analysis_roi_only", b.getString("location_mode"))
        assertEquals(60, a.getJSONObject("frame").getInt("display_width"))
        assertEquals(40, a.getJSONObject("frame").getInt("display_height"))
    }
    @Test fun missingFramesDoNotCreateFakePhotosOrValidPeaks() {
        val json = InspectionJson.encode(record(listOf(result(DefectType.DIG, null)), "no-frame"), emptyMap())
        val t = json.getJSONArray("types").getJSONObject(0)
        assertEquals("RECHECK", json.getString("verdict")); assertFalse(t.getBoolean("valid_peak"))
        assertTrue(t.isNull("photos")); assertFalse(t.has("frame"))
    }
    @Test fun failedQualityKeepsDiagnosticFallbackDistinctFromValidPeak() {
        val fallback = result(DefectType.DIG, frame(99, .3f)).copy(validPeak = false, verdict = Verdict.RECHECK)
        val json = InspectionJson.encode(record(listOf(fallback), "glare"), emptyMap())
        val t = json.getJSONArray("types").getJSONObject(0)
        assertEquals("glare", json.getString("issue")); assertFalse(t.getBoolean("valid_peak"))
        assertEquals("RECHECK", t.getString("verdict")); assertEquals(99, t.getJSONObject("frame").getLong("camera_timestamp_ns"))
    }
}
