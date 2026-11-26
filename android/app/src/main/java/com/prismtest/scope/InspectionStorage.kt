package com.prismtest.scope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class SavedEvidenceFiles(val originalUri: String, val annotatedUri: String,
    val originalName: String, val annotatedName: String)
data class SavedInspection(val reportUri: String, val imageCount: Int)

object InspectionJson {
    fun encode(record: InspectionRecord, images: Map<DefectType, SavedEvidenceFiles>): JSONObject {
        fun nullable(value: Any?) = value ?: JSONObject.NULL
        return JSONObject().put("schema", "prism-inspection-v1").put("id", record.id)
            .put("started_at", record.startedAt).put("completed_at", record.completedAt)
            .put("duration_seconds", record.seconds).put("face", record.face.key).put("face_label", record.face.label)
            .put("note", record.note).put("app_version", record.appVersion).put("camera_profile", record.profile)
            .put("capture_quality_requested", record.requestedQuality).put("capture_quality_bound", record.boundQuality)
            .put("verdict", record.verdict.name).put("verdict_label", record.verdict.label)
            .put("issue", nullable(record.issue)).put("valid_frames", record.validFrames).put("motion", record.motion)
            .put("coordinate_space", "Normalized saved display frame, before the annotated image footer; live captures retain the full source frame. Not millimetres.")
            .put("scope_note", "Each type retains its own peak frame. Dig circles are candidates, not confirmed defect identities. Other types show only the analysis ROI. Different angles are not merged.")
            .put("types", JSONArray(record.types.map { result ->
                val type = JSONObject().put("key", result.type.key).put("label", result.type.label)
                    .put("score", result.score).put("verdict", result.verdict.name).put("verdict_label", result.verdict.label)
                    .put("model_verdict", result.modelVerdict.name).put("valid_peak", result.validPeak)
                    .put("good_samples", result.model.good.size).put("bad_samples", result.model.bad.size)
                    .put("pass_below", nullable(result.model.band?.pass)).put("fail_at_or_above", nullable(result.model.band?.fail))
                    .put("location_mode", if (result.type == DefectType.DIG) "dig_candidates" else "analysis_roi_only")
                result.frame?.let { f ->
                    val roi = f.analysisBounds ?: RoiGeometry.display(f.roi, f.image.width, f.image.height, f.image.rotation)
                    type.put("frame", JSONObject().put("camera_timestamp_ns", f.cameraTimestampNs).put("captured_at", f.capturedAt)
                        .put("display_width", f.image.displayWidth).put("display_height", f.image.displayHeight)
                        .put("pixel_format", if (f.chroma == null) "GRAY_Y8" else "YUV_420_888")
                        .put("color_encoding", nullable(f.chroma?.encoding?.label))
                        .put("analysis_crop_buffer_xyxy", f.analysisCrop?.let {
                            JSONArray(listOf(it.left, it.top, it.right, it.bottom))
                        } ?: JSONObject.NULL)
                        .put("rotation", f.image.rotation).put("camera_profile", f.profile)
                        .put("roi_xyxy", JSONArray(listOf(roi.left, roi.top, roi.right, roi.bottom)))
                        .put("median", f.stats.median).put("sat_ratio", f.stats.satRatio)
                        .put("dig_masked_fraction", f.stats.dig.maskedFraction).put("dig_score", f.stats.dig.score)
                        .put("iso", nullable(f.camera.iso)).put("exposure_ns", nullable(f.camera.exposureNs))
                        .put("focus_diopter", nullable(f.camera.focusDiopter)).put("zoom", f.settings.zoom)
                        .put("focus_control", f.settings.focusControl.key)
                        .put("af_mode", nullable(f.camera.afMode)).put("af_state", nullable(f.camera.afState))
                        .put("lens_moving", f.camera.lensMoving)
                        .put("locked", f.settings.locked)
                        .put("dig_candidates", JSONArray(f.stats.dig.spots.mapIndexed { i, p ->
                            JSONObject().put("number", i + 1).put("cx", p.cx).put("cy", p.cy)
                                .put("rx", p.rx).put("ry", p.ry).put("contrast", p.contrast).put("pixels", p.pixels)
                        })))
                }
                val files = images[result.type]
                type.put("photos", files?.let { JSONObject().put("original_uri", it.originalUri)
                    .put("annotated_uri", it.annotatedUri).put("original_name", it.originalName)
                    .put("annotated_name", it.annotatedName) } ?: JSONObject.NULL)
            }))
    }
}

/** Publishes a complete result or rolls back its newly-created rows, so retry cannot leave partial photos. */
object InspectionStorage {
    const val IMAGE_DIR = "Pictures/PrismScope/Results"
    const val REPORT_DIR = "Documents/PrismScope/Results"

    fun save(context: Context, record: InspectionRecord): SavedInspection {
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        fun insert(collection: Uri, name: String, mime: String, dir: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return (resolver.insert(collection, values) ?: error("저장 파일을 만들지 못했습니다")).also { created += it }
        }
        fun writeImage(bitmap: Bitmap, name: String): Uri {
            val uri = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/png", IMAGE_DIR)
            val stream = resolver.openOutputStream(uri, "w") ?: error("사진 저장 위치를 열지 못했습니다")
            stream.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG 저장에 실패했습니다" } }
            return uri
        }
        try {
            val images = linkedMapOf<DefectType, SavedEvidenceFiles>()
            for (type in record.types) {
                val frame = type.frame ?: continue
                val prefix = "prism_${record.id}_${type.type.key}"
                val originalName = "${prefix}_original.png"; val annotatedName = "${prefix}_marked.png"
                val original = EvidenceImages.original(frame)
                try {
                    val rawUri = writeImage(original, originalName)
                    val marked = EvidenceImages.annotated(original, record, type)
                    val markedUri = try { writeImage(marked, annotatedName) } finally { marked.recycle() }
                    images[type.type] = SavedEvidenceFiles(rawUri.toString(), markedUri.toString(), originalName, annotatedName)
                } finally { original.recycle() }
            }
            val report = insert(MediaStore.Files.getContentUri("external"), "prism_${record.id}.json", "application/json", REPORT_DIR)
            val stream = resolver.openOutputStream(report, "w") ?: error("검사 기록을 열지 못했습니다")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(InspectionJson.encode(record, images).toString(2)) }
            created.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            }
            return SavedInspection(report.toString(), images.size * 2)
        } catch (error: Throwable) {
            created.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw error
        }
    }
}
