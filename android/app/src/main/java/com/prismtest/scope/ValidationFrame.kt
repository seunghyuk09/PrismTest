package com.prismtest.scope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.system.measureNanoTime

data class ValidationMedia(val uri: Uri?, val name: String, val isVideo: Boolean,
                           val durationMs: Long = 0)
data class ValidationCandidate(val x: Double, val y: Double, val radius: Double,
                               val contrast: Double, val pixels: Int, val rank: Int)
data class ValidationFrameRun(val width: Int, val height: Int, val roi: ValidationRoi,
    val candidates: List<ValidationCandidate>, val score: Double, val maskedFraction: Double,
    val threshold: Double, val grayMs: Double, val coreMs: Double)

object ValidationFrames {
    val referenceMedia = ValidationMedia(null, "사용자 디그 영상 · 2초 참고 장면", false)
    val referenceRoi = ValidationRoi(220, 230, 380, 390)
    // Approximate labels supplied by the user; the rest of the visible points are unlabelled.
    val referenceTruth = listOf(ValidationPoint(291.4, 256.2), ValidationPoint(288.3, 315.0))

    fun open(context: Context, uri: Uri): ValidationMedia {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "선택한 파일"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val video = mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "mkv", "mov", "webm", "3gp")
        if (!video) return ValidationMedia(uri, name, false)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("영상 길이를 읽을 수 없습니다")
            require(duration > 0) { "비어 있는 영상입니다" }
            return ValidationMedia(uri, name, true, duration)
        } finally { reader.release() }
    }

    fun decode(context: Context, media: ValidationMedia, timeMs: Long): Bitmap {
        val uri = media.uri ?: return BitmapFactory.decodeResource(context.resources, R.drawable.dig_reference)
        if (!media.isVideo) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > 2048) {
                    val factor = 2048.0 / largest
                    decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1),
                        (info.size.height * factor).toInt().coerceAtLeast(1))
                }
            }
        }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val target = timeMs.coerceIn(0, media.durationMs - 1)
            return reader.getScaledFrameAtTime(target * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                1920, 1920) ?: error("이 시각의 영상 프레임을 읽을 수 없습니다")
        } finally { reader.release() }
    }

    fun analyze(bitmap: Bitmap, roi: ValidationRoi): ValidationFrameRun {
        require(roi.validFor(bitmap.width, bitmap.height)) { "검사면만 선택하세요. 한 변 20px 이상, 최대 100만 화소입니다" }
        val pixels = IntArray(roi.width * roi.height)
        val gray = ByteBuffer.allocateDirect(pixels.size)
        val grayMs = measureNanoTime {
            bitmap.getPixels(pixels, 0, roi.width, roi.left, roi.top, roi.width, roi.height)
            pixels.forEach { p -> gray.put((((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 +
                (p and 255) * 29) shr 8).toByte()) }
            gray.rewind()
        } / 1e6
        lateinit var result: DigResult
        val coreMs = measureNanoTime {
            result = NativeDig.detect(gray, 0, roi.width, 1, 0, 0, roi.width, roi.height,
                roi.width, roi.height, 0)
        } / 1e6
        return ValidationFrameRun(bitmap.width, bitmap.height, roi, result.spots.mapIndexed { index, spot ->
            ValidationCandidate(roi.left + spot.cx * roi.width.toDouble(),
                roi.top + spot.cy * roi.height.toDouble(), max(spot.rx * roi.width, spot.ry * roi.height).toDouble(),
                spot.contrast, spot.pixels, index + 1)
        }, result.score, result.maskedFraction, result.threshold, grayMs, coreMs)
    }
}
