package com.prismtest.scope

/** Coordinates are normalized to the cropped camera frame, before PreviewView's center fill. */
object FrameTransform {
    fun toBuffer(x: Float, y: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }

    fun toDisplay(x: Float, y: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
}
