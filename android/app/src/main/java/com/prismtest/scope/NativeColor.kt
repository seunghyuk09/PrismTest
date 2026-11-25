package com.prismtest.scope

import android.graphics.Bitmap

object NativeColor {
    init { System.loadLibrary("prismvision") }
    private external fun render(y: ByteArray, u: ByteArray?, v: ByteArray?, width: Int, height: Int,
        chromaWidth: Int, chromaHeight: Int, phaseX: Int, phaseY: Int, rotation: Int,
        matrix: Int, fullRange: Boolean, output: Bitmap)

    /** Convert directly into the rotated bitmap; no 4K IntArray or intermediate bitmap copy. */
    fun bitmap(image: GraySnapshot, chroma: ChromaSnapshot?): Bitmap {
        val out = Bitmap.createBitmap(image.displayWidth, image.displayHeight, Bitmap.Config.ARGB_8888)
        try {
            render(image.pixels, chroma?.u, chroma?.v, image.width, image.height,
                chroma?.width ?: 0, chroma?.height ?: 0, chroma?.phaseX ?: 0, chroma?.phaseY ?: 0,
                image.rotation, chroma?.encoding?.matrix ?: 0, chroma?.encoding?.fullRange ?: false, out)
            return out
        } catch (error: Throwable) { out.recycle(); throw error }
    }
}
