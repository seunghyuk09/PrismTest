package com.prismtest.scope

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.camera.core.ImageProxy
import java.util.Locale
import kotlin.math.max

object EvidenceImages {
    const val MAX_DIG_MARKERS = 10
    const val DIG_MARKER_STYLE = "rank-colors-top10-v1"

    fun capture(image: ImageProxy, roi: Roi, stats: RoiStats, camera: AppliedCamera,
                settings: ManualSettings, profile: String): EvidenceFrame {
        val c = image.cropRect
        val crop = FrameCrop(c.left, c.top, c.right, c.bottom)
        val snapshot = CameraSnapshots.copy(image)
        val w = image.width; val h = image.height; val rot = snapshot.image.rotation
        return EvidenceFrame(snapshot.image, image.imageInfo.timestamp, Store.timestamp(),
            EvidenceCoordinates.roi(roi, crop, w, h, rot), EvidenceCoordinates.stats(stats, crop, w, h, rot),
            camera, settings, profile, snapshot.chroma, crop, EvidenceCoordinates.bounds(roi, crop, w, h, rot))
    }

    fun original(frame: GraySnapshot): Bitmap {
        return NativeColor.bitmap(frame, null)
    }

    fun original(frame: EvidenceFrame): Bitmap = NativeColor.bitmap(frame.image, frame.chroma)

    /** Footer is outside the image, so its pixels and normalized candidate coordinates stay aligned. */
    fun annotated(original: Bitmap, record: InspectionRecord, result: InspectionTypeResult): Bitmap {
        val frame = requireNotNull(result.frame)
        val w = original.width; val h = original.height
        val font = (w / 34f).coerceAtLeast(14f)
        val line = font * 1.6f
        val out = Bitmap.createBitmap(w, h + (line * 5.4f).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out); canvas.drawColor(Color.rgb(13, 20, 26)); canvas.drawBitmap(original, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = (w / 360f).coerceAtLeast(2f); color = Color.rgb(255, 200, 87)
        }
        val roi = frame.analysisBounds ?: RoiGeometry.display(frame.roi, frame.image.width, frame.image.height, frame.image.rotation)
        canvas.drawRect(roi.left * w, roi.top * h, roi.right * w, roi.bottom * h, paint)
        if (result.type == DefectType.DIG) frame.stats.dig.spots.take(MAX_DIG_MARKERS).forEachIndexed { i, p ->
            paint.color = when (i) {
                in 0..2 -> Color.rgb(255, 59, 48)
                in 3..5 -> Color.rgb(255, 149, 0)
                else -> Color.rgb(255, 214, 10)
            }
            paint.style = Paint.Style.STROKE
            val x = p.cx * w; val y = p.cy * h
            val radius = max(w / 100f, max(p.rx * w, p.ry * h) + w / 250f)
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL; paint.textSize = font * .85f; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("${i + 1}", (x + radius + 2).coerceAtMost(w - font * 1.3f), y.coerceAtLeast(font), paint)
        }
        paint.style = Paint.Style.FILL; paint.textSize = font; paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = when (result.verdict) {
            Verdict.FAIL -> Color.rgb(255, 99, 115)
            Verdict.PASS -> Color.rgb(99, 221, 176)
            else -> Color.rgb(255, 200, 87)
        }
        val x = font * .6f
        canvas.drawText("${result.type.label} · ${result.verdict.label} · 점수 ${String.format(Locale.US, "%.2f", result.score)}", x, h + line, paint)
        paint.typeface = Typeface.DEFAULT; paint.textSize = font * .8f; paint.color = Color.WHITE
        val lines = listOf(
            if (result.validPeak) "${record.face.label} · 해당 유형의 최고 점수 장면" else "진단용 장면 · 유효한 최고 점수 영상 없음",
            if (result.type == DefectType.DIG) "후보 ${minOf(frame.stats.dig.spots.size, MAX_DIG_MARKERS)}곳 표시 · 1–3 빨강 / 4–6 주황 / 7–10 노랑"
                else "사각형 = 점수 분석 영역 · 개별 결함 위치는 산출하지 않음",
            record.issue?.let { "재확인: $it" } ?: "전체 검사: ${record.verdict.label} · ${frame.capturedAt}",
            record.id
        )
        lines.forEachIndexed { i, text ->
            var label = text
            while (label.length > 1 && paint.measureText(label) > w - 2 * x) label = label.dropLast(2) + "…"
            canvas.drawText(label, x, h + line * (i + 2), paint)
        }
        return out
    }
}
