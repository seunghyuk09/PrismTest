package com.prismtest.scope

import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * 검사 영역의 밝기 분포에서 얼룩 지수를 뽑는다.
 *
 * 암시야 조명에서 정상면은 어둡고 얼룩·이물은 빛을 산란시켜 밝게 뜬다.
 * 따라서 **영역 안에서 가장 밝은 부분이 보통 부분보다 얼마나 밝은가**가
 * 곧 결함의 세기다.
 *
 *   얼룩 지수 = 상위 1% 밝기(p99) − 중앙값(median)
 *
 * 나눗셈이 아니라 뺄셈을 쓴다. 암시야에서는 중앙값이 2~3 까지 내려가는데
 * 그걸로 나누면 값이 폭주해 비교가 불가능해진다. 촬영 조건을 고정해 두면
 * 절대 밝기 차이가 그대로 비교 가능한 양이 된다.
 *
 * 값은 모두 YUV 의 Y 평면(휘도) 기준 0~255 다. 컬러를 쓰지 않는 이유는
 * 판정에 쓰는 것이 산란 광량이고, 폰 ISP 의 컬러 처리는 기기마다 다르기 때문이다.
 */
data class RoiStats(
    /** 중앙값 — 정상면의 밝기 */
    val median: Int,
    /** 상위 1% 밝기 — 가장 밝은 결함부 */
    val p99: Int,
    val mean: Double,
    val max: Int,
    /** 얼룩 지수 = p99 − median (0~255) */
    val stainIndex: Double,
    /** 밝은 화소가 차지하는 비율 (0~1) — 얼룩의 넓이 */
    val brightArea: Double,
    /** 250 이상 화소 비율 — 글레어·포화 감지 */
    val satRatio: Double,
    /** Laplacian 절대값 평균 — 초점 판정 */
    val focus: Double,
    val pixels: Int,
) {
    companion object {
        val EMPTY = RoiStats(0, 0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0)
    }
}

/**
 * 정사각 검사 영역. 좌표계는 **화면에 보이는 프레임** 기준 정규화(0~1)다.
 *
 * 분석 버퍼는 센서 방향(대개 가로)이고 화면은 세로라 90° 돌아가 있다.
 * 버퍼 좌표로 정의하면 사용자가 누른 위치와 실제 측정 위치가 어긋난다.
 *
 * @param size 짧은 변에 대한 비율
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

        fun px(x: Int, y: Int): Int = buf.get(y * rowStride + x * pixStride).toInt() and 0xFF

        // 8bit 히스토그램이면 백분위를 정확하게, 정렬 없이 구할 수 있다.
        val hist = IntArray(256)
        var sum = 0L
        var n = 0
        var y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                val v = px(x, y)
                hist[v]++
                sum += v
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return RoiStats.EMPTY

        fun percentile(p: Double): Int {
            val target = (n * p).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 255
        }

        val median = percentile(0.50)
        val p99 = percentile(0.99)
        var max = 0
        for (v in 255 downTo 0) if (hist[v] > 0) { max = v; break }
        var sat = 0
        for (v in 250..255) sat += hist[v]

        val stain = (p99 - median).toDouble()
        // 얼룩의 넓이: 정상면과 결함의 중간 밝기를 넘는 화소 비율
        val cut = median + (stain / 2.0).toInt()
        var bright = 0
        for (v in (cut + 1).coerceIn(0, 255)..255) bright += hist[v]

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

        return RoiStats(
            median = median,
            p99 = p99,
            mean = sum.toDouble() / n,
            max = max,
            stainIndex = stain,
            brightArea = bright.toDouble() / n,
            satRatio = sat.toDouble() / n,
            focus = if (lapN > 0) lapSum / lapN else 0.0,
            pixels = n,
        )
    }
}
