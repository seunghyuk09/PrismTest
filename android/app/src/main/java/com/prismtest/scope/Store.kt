package com.prismtest.scope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 측정 결과 저장.
 *
 * 이미지는 Pictures/PrismScope 에 무손실 PNG 로 남긴다. JPEG 은 재압축 아티팩트가
 * 미세 얼룩처럼 보일 수 있어 쓰지 않는다. 로그는 Documents/PrismScope 에 CSV 로 누적한다.
 * 둘 다 MediaStore 라 별도 저장소 권한이 필요 없고 갤러리·파일 앱에서 바로 보인다.
 */
object Store {

    private const val DIR_IMG = "Pictures/PrismScope"
    private const val DIR_DOC = "Documents/PrismScope"
    // Preserve the old four-type CSV; never append a five-type row to its old header.
    private const val CSV_NAME = "prismscope_log_v3.csv"

    fun newInspectionId(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
        "_" + java.util.UUID.randomUUID().toString().take(8)

    /**
     * 유형 열은 [DefectType] 에서 만든다. 유형을 추가하면 헤더와 행이 함께 늘어나므로
     * 둘이 어긋날 일이 없다 — 열 순서는 MainActivity 의 buildCsvRow 와 같다.
     */
    val csvHeader: String = (
        listOf("timestamp", "note", "mode", "face", "verdict") +
            DefectType.values().flatMap {
                listOf(
                    "${it.key}_score", "${it.key}_verdict",
                    "${it.key}_good_n", "${it.key}_bad_n",
                    "${it.key}_pass", "${it.key}_fail",
                )
            } +
            listOf(
                "median", "p99", "max", "contrast", "darkContrast",
                "brightArea", "linearity", "spot", "satRatio", "focus",
                "iso", "exposureNs", "focusDiopter", "locked", "zoom",
                "roi_cx", "roi_cy", "roi_size",
                "frameW", "frameH", "imageFile", "appVersion",
                "dig_score_current", "dig_candidate_count", "dig_masked_fraction", "dig_candidates_xy", "result_issue",
                "active_types",
                "analysis_crop_buffer_xyxy", "analysis_roi_full_xyxy",
            )
        ).joinToString(",")

    /** Save the full, same-frame camera color data as a lossless PNG. */
    fun saveFrame(context: Context, image: ImageProxy, tag: String): String? {
        val name = "prism_${newInspectionId()}_$tag.png"
        val snapshot = CameraSnapshots.copy(image)
        val bmp = NativeColor.bitmap(snapshot.image, snapshot.chroma)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR_IMG)
        }
        var created: android.net.Uri? = null
        return try {
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            created = uri
            val out = context.contentResolver.openOutputStream(uri) ?: error("사진 저장 위치를 열지 못했습니다")
            val written = out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (written) name else {
                context.contentResolver.delete(uri, null, null); created = null; null
            }
        } catch (e: Exception) {
            created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            null
        } catch (error: OutOfMemoryError) {
            created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            bmp.recycle()
        }
    }

    /**
     * CSV 한 줄을 덧붙인다.
     *
     * MediaStore 는 append 모드를 안정적으로 지원하지 않아, 세션 시작 시 만든
     * 파일에 "wa" 모드로 이어 쓴다. 실패하면 새 파일을 만든다.
     */
    fun appendCsv(context: Context, row: String) {
        val resolver = context.contentResolver
        val existing = findCsv(context)
        if (existing != null) {
            try {
                resolver.openOutputStream(existing, "wa")?.use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { it.write(row + "\n") }
                }
                return
            } catch (_: Exception) {
                // append 실패 시 아래에서 새로 만든다
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CSV_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR_DOC)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use {
                    it.write(csvHeader + "\n")
                    it.write(row + "\n")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun findCsv(context: Context): android.net.Uri? {
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(CSV_NAME, "$DIR_DOC%")
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"), proj, sel, args, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"), id
                )
            }
        }
        return null
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
