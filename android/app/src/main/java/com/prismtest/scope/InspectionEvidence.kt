package com.prismtest.scope

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class NormalizedBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Inclusive camera-buffer bounds shared by analysis and the saved evidence overlay. */
object RoiGeometry {
    fun buffer(roi: Roi, width: Int, height: Int, rotation: Int): PixelBounds {
        require(width > 2 && height > 2)
        val (x, y) = FrameTransform.toBuffer(roi.cx, roi.cy, rotation)
        val half = (roi.size * min(width, height) / 2f).coerceAtLeast(2f)
        val l = (x * width - half).toInt().coerceIn(0, width - 2)
        val t = (y * height - half).toInt().coerceIn(0, height - 2)
        return PixelBounds(l, t, (x * width + half).toInt().coerceIn(l + 1, width - 1),
            (y * height + half).toInt().coerceIn(t + 1, height - 1))
    }
    fun display(roi: Roi, width: Int, height: Int, rotation: Int): NormalizedBounds {
        val b = buffer(roi, width, height, rotation)
        val a = FrameTransform.toDisplay(b.left.toFloat() / width, b.top.toFloat() / height, rotation)
        val z = FrameTransform.toDisplay((b.right + 1f) / width, (b.bottom + 1f) / height, rotation)
        return NormalizedBounds(min(a.first, z.first), min(a.second, z.second),
            max(a.first, z.first), max(a.second, z.second))
    }
}

/** Owns a copy of the cropped Y plane; it never retains a camera-owned ImageProxy/buffer. */
data class GraySnapshot(val width: Int, val height: Int, val rotation: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(rotation in listOf(0, 90, 180, 270))
    }
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    companion object {
        fun copy(buffer: ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int,
                 width: Int, height: Int, rotation: Int): GraySnapshot {
            require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216)
            require(offset >= 0 && pixelStride > 0 && rowStride > 0)
            require((width - 1L) * pixelStride + 1 <= rowStride)
            val last = offset.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride
            require(last < buffer.limit()) { "영상 버퍼 범위를 벗어났습니다" }
            val bytes = ByteArray(width * height)
            val source = buffer.duplicate()
            for (y in 0 until height) {
                if (pixelStride == 1) {
                    source.position(offset + y * rowStride)
                    source.get(bytes, y * width, width)
                } else for (x in 0 until width) bytes[y * width + x] = source.get(offset + y * rowStride + x * pixelStride)
            }
            return GraySnapshot(width, height, ((rotation % 360) + 360) % 360, bytes)
        }
    }
}

data class PeakFrame<T>(val score: Double, val frame: T)
data class FinishedEvidence<K, T>(val peaks: Map<K, PeakFrame<T>>, val fallback: T?)

/** One peak per selected type; all types peaking in one frame share the same owned snapshot. */
class PeakFrameCollector<K, T>(keys: Set<K>) {
    private val keys = keys.toSet()
    private val peaks = linkedMapOf<K, PeakFrame<T>>()
    private var fallback: T? = null
    private var finished: FinishedEvidence<K, T>? = null
    init { require(keys.isNotEmpty() && keys.size <= 32) }

    @Synchronized fun offer(scores: Map<K, Double>, capture: () -> T): Boolean {
        if (finished != null) return false
        require(keys.all { scores[it]?.let { s -> s.isFinite() && s >= 0 } == true })
        val improved = keys.filter { key -> peaks[key]?.let { scores.getValue(key) > it.score } ?: true }
        if (improved.isNotEmpty()) {
            val frame = capture()
            improved.forEach { peaks[it] = PeakFrame(scores.getValue(it), frame) }
            fallback = null
        }
        return true
    }

    /** Keep one diagnostic frame if no valid frame ever arrives; it cannot be a valid peak. */
    @Synchronized fun invalid(capture: () -> T) {
        if (finished == null && peaks.isEmpty() && fallback == null) fallback = capture()
    }
    @Synchronized fun scores(): Map<K, Double> = peaks.mapValues { it.value.score }
    @Synchronized fun finish(): FinishedEvidence<K, T> = finished ?: FinishedEvidence(peaks.toMap(), fallback)
        .also { finished = it }
}

data class EvidenceFrame(val image: GraySnapshot, val cameraTimestampNs: Long,
    val capturedAt: String, val roi: Roi, val stats: RoiStats, val camera: AppliedCamera,
    val settings: ManualSettings, val profile: String)

data class InspectionTypeResult(val type: DefectType, val score: Double, val verdict: Verdict,
    val modelVerdict: Verdict, val model: DefectModel, val frame: EvidenceFrame?, val validPeak: Boolean)

data class InspectionRecord(val id: String, val startedAt: String, val completedAt: String,
    val seconds: Int, val face: Face, val note: String, val appVersion: String,
    val profile: String, val verdict: Verdict, val issue: String?, val validFrames: Int,
    val motion: Double, val types: List<InspectionTypeResult>,
    val requestedQuality: String = CaptureQuality.STANDARD.key,
    val boundQuality: String = CaptureQuality.STANDARD.key)
