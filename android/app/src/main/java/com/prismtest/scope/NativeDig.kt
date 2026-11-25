package com.prismtest.scope

import java.nio.ByteBuffer

/** Locations are display-frame coordinates; radius is an image annotation, not millimetres. */
data class DigSpot(val cx: Float, val cy: Float, val rx: Float, val ry: Float,
                   val contrast: Double, val pixels: Int)
data class DigResult(val score: Double = 0.0, val maskedFraction: Double = 0.0,
                     val threshold: Double = 0.0, val spots: List<DigSpot> = emptyList())

object NativeDig {
    init { System.loadLibrary("prismvision") }

    private external fun analyze(buffer: ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int,
                                 width: Int, height: Int): DoubleArray

    fun detect(buffer: ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int,
               left: Int, top: Int, width: Int, height: Int,
               frameWidth: Int, frameHeight: Int, rotation: Int): DigResult {
        // CameraX normally supplies a direct buffer. Preserve offsets for vendor buffers too.
        val direct = if (buffer.isDirect) buffer else ByteBuffer.allocateDirect(buffer.capacity()).apply {
            put(buffer.duplicate().apply { clear() }); rewind()
        }
        val data = analyze(direct, offset + top * rowStride + left * pixelStride,
                           rowStride, pixelStride, width, height)
        val swap = rotation == 90 || rotation == 270
        val spots = (0 until data[3].toInt()).map { index ->
            val at = 4 + index * 5
            val (x, y) = FrameTransform.toDisplay(
                ((left + data[at] + 0.5) / frameWidth).toFloat(),
                ((top + data[at + 1] + 0.5) / frameHeight).toFloat(), rotation)
            val radius = data[at + 2].toFloat()
            DigSpot(x, y, radius / if (swap) frameHeight else frameWidth,
                    radius / if (swap) frameWidth else frameHeight,
                    data[at + 3], data[at + 4].toInt())
        }
        return DigResult(data[0], data[1], data[2], spots)
    }
}
