package com.prismtest.scope

import android.os.Build
import androidx.camera.core.ImageProxy

data class CameraSnapshot(val image: GraySnapshot, val chroma: ChromaSnapshot?)

object CameraSnapshots {
    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun copy(image: ImageProxy): CameraSnapshot {
        val w = image.width; val h = image.height
        val y = image.planes[0]
        val gray = GraySnapshot.copy(y.buffer, y.buffer.position(), y.rowStride, y.pixelStride,
            w, h, image.imageInfo.rotationDegrees)
        // Single-plane imported/legacy diagnostic fixtures retain their real grayscale data.
        // Live CameraX YUV_420_888 frames provide all three planes.
        if (image.planes.size == 1) return CameraSnapshot(gray, null)
        require(image.planes.size >= 3) { "카메라 색상 정보가 없습니다" }
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val u = image.planes[1]; val v = image.planes[2]
        val encoding = if (Build.VERSION.SDK_INT >= 33)
            YuvEncoding.fromDataSpace(image.image?.dataSpace ?: 0) else YuvEncoding()
        return CameraSnapshot(gray, ChromaSnapshot(cw, ch,
            ChromaSnapshot.plane(u.buffer, u.buffer.position(), u.rowStride, u.pixelStride, cw, ch),
            ChromaSnapshot.plane(v.buffer, v.buffer.position(), v.rowStride, v.pixelStride, cw, ch),
            encoding = encoding))
    }
}
