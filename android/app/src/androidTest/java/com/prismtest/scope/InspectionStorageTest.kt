package com.prismtest.scope

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class InspectionStorageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): EvidenceFrame {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.dig_reference)
        val pixels = IntArray(160 * 160)
        bitmap.getPixels(pixels, 0, 160, 220, 230, 160, 160); bitmap.recycle()
        val buffer = ByteBuffer.allocateDirect(pixels.size)
        pixels.forEach { p -> buffer.put((((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8).toByte()) }
        buffer.rewind()
        val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,
            arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ -> when (method.name) {
                "getBuffer" -> buffer; "getRowStride" -> 160; "getPixelStride" -> 1
                else -> error(method.name)
            } } as ImageProxy.PlaneProxy
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) { "getRotationDegrees" -> 0; "getTimestamp" -> 123456789L; else -> error(method.name) }
        } as ImageInfo
        val image = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlanes" -> arrayOf(plane); "getCropRect" -> Rect(0, 0, 160, 160)
                "getWidth", "getHeight" -> 160; "getImageInfo" -> info
                "getFormat" -> ImageFormat.YUV_420_888; "close", "getImage" -> null
                else -> error(method.name)
            }
        } as ImageProxy
        val roi = Roi(.5f, .5f, 1f)
        val stats = Stats.analyze(image, roi)
        val owned = EvidenceImages.capture(image, roi, stats, AppliedCamera(), ManualSettings(), "test-profile")
        image.close()
        val first = owned.image.pixels[0]
        buffer.put(0, (first.toInt() xor 255).toByte())
        assertEquals(first, owned.image.pixels[0])
        val found = ValidationMetrics.compare(listOf(ValidationPoint(71.4, 26.2), ValidationPoint(68.3, 85.0)),
            stats.dig.spots.map { ValidationPoint(it.cx * 160.0, it.cy * 160.0) }, 5.0, false)
        assertEquals(2, found.matches.size)
        return owned
    }

    private fun record(frame: EvidenceFrame?, score: Double = frame?.stats?.dig?.score ?: 0.0): InspectionRecord {
        val issue = if (frame == null) "촬영 프레임 없음" else null
        return InspectionRecord("test_" + Store.newInspectionId(), Store.timestamp(), Store.timestamp(), 8,
            Face.ALL, "자동 저장 회귀 검증용 · 제품 성능 평가 아님", "test", "test-profile",
            if (frame == null) Verdict.RECHECK else Verdict.NOT_READY, issue, if (frame == null) 0 else 30, 100.0,
            listOf(InspectionTypeResult(DefectType.DIG, score, Verdict.NOT_READY, Verdict.NOT_READY,
                DefectModel(), frame, frame != null)))
    }
    private fun read(saved: SavedInspection): JSONObject = context.contentResolver.openInputStream(Uri.parse(saved.reportUri))!!
        .bufferedReader().use { JSONObject(it.readText()) }
    private fun cleanup(saved: SavedInspection) {
        val r = read(saved)
        val list = r.getJSONArray("types")
        for (i in 0 until list.length()) list.getJSONObject(i).optJSONObject("photos")?.let {
            context.contentResolver.delete(Uri.parse(it.getString("original_uri")), null, null)
            context.contentResolver.delete(Uri.parse(it.getString("annotated_uri")), null, null)
        }
        context.contentResolver.delete(Uri.parse(saved.reportUri), null, null)
    }
    private fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return true
        for (i in 0 until node.childCount) if (contains(node.getChild(i), text)) return true
        return false
    }

    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return listOf("<no active window>")
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::labels) ?: emptyList() }
    }

    @Test fun savesPeakFrameOriginalAndMarkedPhotosAndOpensResultWithoutCameraPermission() {
        val frame = fixture()
        val saved = InspectionStorage.save(context, record(frame))
        try {
            assertEquals(2, saved.imageCount)
            val json = read(saved); val type = json.getJSONArray("types").getJSONObject(0)
            val storedFrame = type.getJSONObject("frame")
            assertEquals(123456789L, storedFrame.getLong("camera_timestamp_ns"))
            assertEquals(frame.stats.dig.spots.size, storedFrame.getJSONArray("dig_candidates").length())
            val files = type.getJSONObject("photos")
            val original = context.contentResolver.openInputStream(Uri.parse(files.getString("original_uri")))!!.use { BitmapFactory.decodeStream(it) }
            val marked = context.contentResolver.openInputStream(Uri.parse(files.getString("annotated_uri")))!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(160, original.width); assertEquals(160, original.height)
            assertEquals(original.width, marked.width); assertTrue(marked.height > original.height)
            val expected = frame.image.pixels[85 * 160 + 68].toInt() and 255
            assertEquals(expected, original.getPixel(68, 85) and 255)
            original.recycle(); marked.recycle()
            val intent = Intent(context, InspectionResultActivity::class.java).putExtra("report_uri", saved.reportUri)
            ActivityScenario.launch<InspectionResultActivity>(intent).use {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val until = SystemClock.elapsedRealtime() + 30_000
                var opened = false
                while (SystemClock.elapsedRealtime() < until) {
                    val root = automation.rootInActiveWindow
                    if (contains(root, "저장된 검사 사진") && contains(root, "전체: 기준 없음")) { opened = true; break }
                    SystemClock.sleep(200)
                }
                val visible = labels(automation.rootInActiveWindow).joinToString(" | ")
                Log.i("PrismStorageTest", "Result UI: $visible")
                automation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "result-viewer-test.png").outputStream().use { stream ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    bitmap.recycle()
                }
                assertTrue("Saved result and image must be shown; UI=$visible", opened)
            }
        } finally { cleanup(saved) }
    }

    @Test fun missingCameraFrameSavesRecheckRecordWithoutInventingAPhoto() {
        val saved = InspectionStorage.save(context, record(null))
        try {
            val json = read(saved)
            assertEquals(0, saved.imageCount); assertEquals("RECHECK", json.getString("verdict"))
            assertTrue(json.getJSONArray("types").getJSONObject(0).isNull("photos"))
        } finally { cleanup(saved) }
    }

    @Test fun failedReportSerializationRollsBackAllNewMediaRows() {
        val bad = record(fixture(), Double.NaN)
        try { InspectionStorage.save(context, bad); fail("Nonfinite scores must fail") }
        catch (_: org.json.JSONException) { }
        context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("prism_${bad.id}%"), null)!!.use {
            assertEquals("Failed save must leave no partial images/report", 0, it.count)
        }
    }

    @Test fun detailSourceIsSavedWithoutDownscalingAndRotationPreservesItsPixels() {
        val pixels = ByteArray(2560 * 1920) { 28 }
        pixels[70 * 2560 + 90] = 220.toByte()
        val image = GraySnapshot(2560, 1920, 90, pixels)
        val frame = EvidenceFrame(image, 999L, Store.timestamp(), Roi(.5f,.5f,.4f),
            RoiStats.EMPTY.copy(pixels = pixels.size), AppliedCamera(), ManualSettings(), "detail-test-profile")
        val request = record(frame, 0.0).copy(requestedQuality = "detail", boundQuality = "detail")
        val saved = InspectionStorage.save(context, request)
        try {
            val json = read(saved)
            assertEquals("detail", json.getString("capture_quality_requested"))
            val t = json.getJSONArray("types").getJSONObject(0)
            val stored = t.getJSONObject("frame")
            assertEquals(1920, stored.getInt("display_width")); assertEquals(2560, stored.getInt("display_height"))
            val files = t.getJSONObject("photos")
            val original = context.contentResolver.openInputStream(Uri.parse(files.getString("original_uri")))!!.use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
            assertEquals(1920, original.width); assertEquals(2560, original.height)
            assertEquals(220, original.getPixel(1919 - 70, 90) and 255)
            original.recycle()
        } finally { cleanup(saved) }
    }
}
