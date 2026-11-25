package com.prismtest.scope

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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

    private fun assertRed(pixel: Int) = assertTrue("Expected red, RGB=${Color.red(pixel)},${Color.green(pixel)},${Color.blue(pixel)}",
        Color.red(pixel) >= 250 && Color.green(pixel) < 3 && Color.blue(pixel) < 3)

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

    private data class YuvFixture(val image: ImageProxy, val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer,
                                  val yStride: Int)

    private fun colorFixture(w: Int, h: Int, rotation: Int, crop: Rect): YuvFixture {
        val ys = w + 16; val cs = ((w + 1) / 2) * 2 + 16
        val y = ByteBuffer.allocateDirect(5 + ys * h)
        val u = ByteBuffer.allocateDirect(3 + cs * ((h + 1) / 2))
        val v = ByteBuffer.allocateDirect(3 + cs * ((h + 1) / 2))
        for (row in 0 until h) for (x in 0 until w) y.put(5 + row * ys + x, 81.toByte())
        for (row in 0 until (h + 1) / 2) for (x in 0 until (w + 1) / 2) {
            u.put(3 + row * cs + x * 2, 90.toByte()); v.put(3 + row * cs + x * 2, 240.toByte())
        }
        y.position(5); u.position(3); v.position(3)
        fun plane(data: ByteBuffer, stride: Int, pixel: Int) = Proxy.newProxyInstance(
            ImageProxy.PlaneProxy::class.java.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when(method.name) { "getBuffer" -> data; "getRowStride" -> stride; "getPixelStride" -> pixel
                else -> error(method.name) }
        } as ImageProxy.PlaneProxy
        val planes = arrayOf(plane(y,ys,1),plane(u,cs,2),plane(v,cs,2))
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when(method.name) { "getRotationDegrees" -> rotation; "getTimestamp" -> 987654321L; else -> error(method.name) }
        } as ImageInfo
        val image = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when(method.name) { "getPlanes" -> planes; "getWidth" -> w; "getHeight" -> h; "getCropRect" -> crop
                "getImageInfo" -> info; "getFormat" -> ImageFormat.YUV_420_888; "getImage", "close" -> null
                else -> error(method.name) }
        } as ImageProxy
        return YuvFixture(image,y,u,v,ys)
    }

    @Test fun full4kColorKeepsSourcePixelsAndReprojectsCroppedCandidateCoordinates() {
        val source = colorFixture(3840,2160,90,Rect(384,216,3456,1944))
        source.y.put(5 + 321 * source.yStride + 123,145.toByte())
        val stats = RoiStats.EMPTY.copy(pixels = 100, dig = DigResult(3.0,
            spots = listOf(DigSpot(.25f,.75f,.01f,.02f,70.0,4))))
        val frame = EvidenceImages.capture(source.image,Roi(.5f,.5f,1f),stats,
            AppliedCamera(),ManualSettings(),"color4k-test")
        source.image.close()
        source.y.put(5,16.toByte()); source.u.put(3,128.toByte()); source.v.put(3,128.toByte())
        val saved = InspectionStorage.save(context,record(frame).copy(requestedQuality="4k",boundQuality="4k"))
        try {
            val type = read(saved).getJSONArray("types").getJSONObject(0)
            val f = type.getJSONObject("frame")
            assertEquals("YUV_420_888",f.getString("pixel_format"))
            assertEquals(2160,f.getInt("display_width")); assertEquals(3840,f.getInt("display_height"))
            assertEquals(384,f.getJSONArray("analysis_crop_buffer_xyxy").getInt(0))
            val point = f.getJSONArray("dig_candidates").getJSONObject(0)
            assertEquals(.3,point.getDouble("cx"),.00001); assertEquals(.7,point.getDouble("cy"),.00001)
            val uri = Uri.parse(type.getJSONObject("photos").getString("original_uri"))
            val bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            try {
                assertEquals(2160,bitmap.width); assertEquals(3840,bitmap.height)
                val red = bitmap.getPixel(2159,0)
                assertRed(red)
                val changed = bitmap.getPixel(1838,123)
                assertEquals(74,Color.green(changed)); assertEquals(74,Color.blue(changed))
            } finally { bitmap.recycle() }
        } finally { cleanup(saved) }
    }

    @Test fun nativeColorConversionHonorsRotationMatrixRangeAndChromaPhase() {
        for (rotation in listOf(0,90,180,270)) {
            val frame = GraySnapshot(2,2,rotation,byteArrayOf(81,145.toByte(),81,145.toByte()))
            val bitmap = NativeColor.bitmap(frame,ChromaSnapshot(1,1,byteArrayOf(90),byteArrayOf(240.toByte())))
            try {
                val x = if (rotation == 90 || rotation == 180) 1 else 0
                val y = if (rotation == 180 || rotation == 270) 1 else 0
                val red = bitmap.getPixel(x,y)
                assertRed(red)
            } finally { bitmap.recycle() }
        }
        for ((encoding, values) in listOf(YuvEncoding(1,true) to listOf(54,99,255),
                                         YuvEncoding(2,true) to listOf(67,92,255))) {
            val frame = GraySnapshot(2,2,0,ByteArray(4) { values[0].toByte() })
            val bitmap = NativeColor.bitmap(frame,ChromaSnapshot(1,1,byteArrayOf(values[1].toByte()),
                byteArrayOf(values[2].toByte()),encoding=encoding))
            try {
                val red = bitmap.getPixel(0,0)
                assertRed(red)
            } finally { bitmap.recycle() }
        }
        val phased = NativeColor.bitmap(GraySnapshot(2,2,0,ByteArray(4) {81}),
            ChromaSnapshot(2,1,byteArrayOf(90,128.toByte()),byteArrayOf(240.toByte(),128.toByte()),phaseX=1))
        try {
            assertTrue(Color.red(phased.getPixel(0,0))>250)
            val neutral = phased.getPixel(1,0)
            assertEquals(Color.red(neutral),Color.green(neutral)); assertEquals(Color.green(neutral),Color.blue(neutral))
        } finally { phased.recycle() }
    }

    @Test fun manualSnapshotAlsoSavesFullFrameColor() {
        val source = colorFixture(4,2,270,Rect(1,0,3,2))
        val name = requireNotNull(Store.saveFrame(context,source.image,"test_color"))
        source.image.close()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(collection,arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",arrayOf(name),null)!!.use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        val uri = android.content.ContentUris.withAppendedId(collection,id)
        try {
            val bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            try {
                assertEquals(2,bitmap.width); assertEquals(4,bitmap.height)
                val red = bitmap.getPixel(0,0)
                assertRed(red)
            } finally { bitmap.recycle() }
        } finally { context.contentResolver.delete(uri,null,null) }
    }
}
