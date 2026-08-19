package com.prismtest.scope

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 프레임 밝기·초점 통계.
 *
 * 모든 값은 YUV_420_888 의 Y 평면(휘도) 기준이며 0~255 스케일이다.
 * 컬러를 쓰지 않는 이유: 암시야 검사에서 판정에 쓰는 것은 산란 광량이고,
 * 폰 ISP 의 컬러 처리는 기기마다 달라 재현성을 떨어뜨린다.
 */
data class RoiStats(
    val mean: Double,
    val min: Int,
    val max: Int,
    val stdDev: Double,
    /** 250 이상 화소 비율 (0~1). 글레어·포화 감지용 */
    val satRatio: Double,
    /** Laplacian 절대값 평균. 클수록 선명하다. 초점 판정용 */
    val focus: Double,
    val pixels: Int,
) {
    companion object {
        val EMPTY = RoiStats(0.0, 0, 0, 0.0, 0.0, 0.0, 0)
    }
}

/**
 * 정사각 ROI. 좌표계는 **화면에 보이는 프레임** 기준 정규화(0~1)다.
 *
 * 분석 버퍼는 센서 방향(대개 가로)이고 화면은 세로라 90° 돌아가 있다.
 * ROI 를 버퍼 좌표로 정의하면 사용자가 탭한 위치와 실제 측정 위치가 어긋나므로,
 * 화면 기준으로 정의하고 버퍼로 변환하는 쪽을 택했다.
 *
 * @param size 짧은 변에 대한 비율. 화면에서도 버퍼에서도 정사각형이 된다.
 */
data class Roi(val cx: Float, val cy: Float, val size: Float)

object Stats {

    /** 화면 정규화 좌표 → 버퍼 정규화 좌표. rotation 은 ImageProxy 의 rotationDegrees. */
    private fun toBuffer(cx: Float, cy: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> cy to (1f - cx)
            180 -> (1f - cx) to (1f - cy)
            270 -> (1f - cy) to cx
            else -> cx to cy
        }

    /**
     * ROI 통계를 계산한다.
     *
     * @param step 서브샘플 간격. 1이면 전 화소, 2면 1/4만 본다.
     *             실시간 프리뷰는 2로도 충분하고, 확정 측정은 1을 쓴다.
     */
    fun analyze(image: ImageProxy, roi: Roi, step: Int = 2): RoiStats {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (w <= 2 || h <= 2) return RoiStats.EMPTY

        val (bx, by) = toBuffer(roi.cx, roi.cy, image.imageInfo.rotationDegrees)
        val halfPx = (roi.size * minOf(w, h) / 2f).coerceAtLeast(2f)
        val l = (bx * w - halfPx).toInt().coerceIn(0, w - 2)
        val t = (by * h - halfPx).toInt().coerceIn(0, h - 2)
        val r = (bx * w + halfPx).toInt().coerceIn(l + 1, w - 1)
        val b = (by * h + halfPx).toInt().coerceIn(t + 1, h - 1)

        var sum = 0L
        var sumSq = 0L
        var mn = 255
        var mx = 0
        var sat = 0
        var n = 0

        fun px(x: Int, y: Int): Int = buf.get(y * rowStride + x * pixStride).toInt() and 0xFF

        var y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                val v = px(x, y)
                sum += v
                sumSq += v.toLong() * v
                if (v < mn) mn = v
                if (v > mx) mx = v
                if (v >= 250) sat++
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return RoiStats.EMPTY

        val mean = sum.toDouble() / n
        val variance = (sumSq.toDouble() / n) - mean * mean
        val sd = if (variance > 0) sqrt(variance) else 0.0

        // Laplacian 절대값 평균 — 경계에서 한 칸 안쪽만 훑는다
        var lapSum = 0.0
        var lapN = 0
        y = t + step
        while (y <= b - step) {
            var x = l + step
            while (x <= r - step) {
                val c = px(x, y)
                val lap = px(x - step, y) + px(x + step, y) + px(x, y - step) + px(x, y + step) - 4 * c
                lapSum += abs(lap).toDouble()
                lapN++
                x += step
            }
            y += step
        }
        val focus = if (lapN > 0) lapSum / lapN else 0.0

        return RoiStats(mean, mn, mx, sd, sat.toDouble() / n, focus, n)
    }

    /**
     * 결함 대비 ΔI/I.
     *
     * 암시야에서 결함부(A)와 배경·무결함부(B)의 밝기 차를 배경으로 나눈 값이다.
     * 조명안을 비교하는 단일 지표이며 목표는 0.05 이상, 권장 0.15 이상이다.
     * 배경이 0에 가까우면 값이 발산하므로 분모에 하한을 둔다.
     */
    fun contrast(a: RoiStats, b: RoiStats): Double {
        val denom = maxOf(b.mean, 1.0)
        return (a.mean - b.mean) / denom
    }
}
