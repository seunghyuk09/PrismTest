package com.prismtest.scope

import java.nio.ByteBuffer
import kotlin.math.min

/** Packed, owned chroma planes. No camera-owned buffer survives ImageProxy.close(). */
data class ChromaSnapshot(val width: Int, val height: Int, val u: ByteArray, val v: ByteArray,
    val phaseX: Int = 0, val phaseY: Int = 0,
    val encoding: YuvEncoding = YuvEncoding()) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 4_194_304)
        require(u.size.toLong() == width.toLong() * height && v.size == u.size)
        require(phaseX in 0..1 && phaseY in 0..1)
    }
    companion object {
        fun plane(buffer: ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int,
                  width: Int, height: Int): ByteArray =
            GraySnapshot.copy(buffer, offset, rowStride, pixelStride, width, height, 0).pixels
    }
}

/** Camera dataspace standard/range, with Android's usual SDR YUV fallback for older devices. */
data class YuvEncoding(val matrix: Int = 0, val fullRange: Boolean = false) {
    val label: String get() = "BT${when (matrix) { 1 -> "709"; 2 -> "2020"; else -> "601" }}_${if (fullRange) "full" else "limited"}"
    companion object {
        fun fromDataSpace(dataSpace: Int): YuvEncoding {
            val matrix = when ((dataSpace ushr 16) and 63) { 1 -> 1; 6 -> 2; else -> 0 }
            return YuvEncoding(matrix, ((dataSpace ushr 27) and 7) == 1)
        }
    }
}

/** Buffer-space crop, right/bottom exclusive. Saved images retain the full source frame. */
data class FrameCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && right > left && bottom > top) }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object EvidenceCoordinates {
    fun point(x: Float, y: Float, crop: FrameCrop, width: Int, height: Int, rotation: Int): Pair<Float, Float> {
        require(crop.right <= width && crop.bottom <= height)
        val p = FrameTransform.toBuffer(x, y, rotation)
        return FrameTransform.toDisplay((crop.left + p.first * crop.width) / width,
            (crop.top + p.second * crop.height) / height, rotation)
    }
    fun roi(roi: Roi, crop: FrameCrop, width: Int, height: Int, rotation: Int): Roi {
        val p = point(roi.cx, roi.cy, crop, width, height, rotation)
        return Roi(p.first, p.second, roi.size * min(crop.width, crop.height) / min(width, height))
    }
    fun bounds(roi: Roi, crop: FrameCrop, width: Int, height: Int, rotation: Int): NormalizedBounds {
        val b = RoiGeometry.display(roi, crop.width, crop.height, rotation)
        val a = point(b.left, b.top, crop, width, height, rotation)
        val z = point(b.right, b.bottom, crop, width, height, rotation)
        return NormalizedBounds(a.first, a.second, z.first, z.second)
    }
    fun stats(stats: RoiStats, crop: FrameCrop, width: Int, height: Int, rotation: Int): RoiStats {
        val swap = rotation == 90 || rotation == 270
        val sx = if (swap) crop.height.toFloat() / height else crop.width.toFloat() / width
        val sy = if (swap) crop.width.toFloat() / width else crop.height.toFloat() / height
        return stats.copy(dig = stats.dig.copy(spots = stats.dig.spots.map { spot ->
            val p = point(spot.cx, spot.cy, crop, width, height, rotation)
            spot.copy(cx = p.first, cy = p.second, rx = spot.rx * sx, ry = spot.ry * sy)
        }))
    }
}
