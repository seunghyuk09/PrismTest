package com.prismtest.scope

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 검사 영역의 밝기 분포에서 결함의 **모양 특징**을 뽑는다.
 *
 * 암시야 조명에서 정상면은 어둡고 결함은 빛을 산란시켜 밝게 뜬다.
 * 다만 "밝다"는 것만으로는 얼룩·이물·스크래치가 구분되지 않는다.
 * 세 가지는 **밝은 부분의 모양**이 다르다.
 *
 *   얼룩     넓게 퍼짐        → 대비 있음, 넓이 큼, 선형성 낮음
 *   흰색 이물  작고 아주 밝음   → 최대값이 상위 1%보다 훨씬 높음
 *   스크래치   가늘고 긴 선     → 선형성 높음
 *   검정 이물  빛을 막음        → 아래쪽 대비(중앙값 − 하위 1%)로만 보인다
 *
 * 그래서 여기서는 판정하지 않고 **원시 특징만** 뽑는다. 유형별 점수는
 * [DefectType] 이 이 값들을 조합해서 만든다.
 *
 * 값은 모두 YUV 의 Y 평면(휘도) 기준 0~255 다. 컬러를 쓰지 않는 이유는
 * 판정에 쓰는 것이 산란 광량이고, 폰 ISP 의 컬러 처리는 기기마다 다르기 때문이다.
 */
data class RoiStats(
    /** 중앙값 — 정상면의 밝기 */
    val median: Int,
    /** 상위 1% 밝기 */
    val p99: Int,
    /** 최대 밝기 */
    val max: Int,
    val mean: Double,
    /** 위쪽 대비 = p99 − median. 밝게 뜨는 결함의 세기 */
    val contrast: Double,
    /** 아래쪽 대비 = median − p1. 빛을 막는 결함의 세기 */
    val darkContrast: Double,
    /** 밝은 화소가 차지하는 비율 (0~1) — 결함의 넓이 */
    val brightArea: Double,
    /** 밝은 화소 분포의 선형성 (0~1). 0이면 원형, 1이면 가늘고 긴 선 */
    val linearity: Double,
    /**
     * 점 세기 = max − p99.
     * 아주 작고 밝은 알갱이는 화소 수가 적어 p99 를 못 올리지만 max 는 올린다.
     * 넓은 얼룩은 반대로 둘이 붙는다. 그래서 이 차이가 곧 "작고 밝은 점"의 척도다.
     */
    val spot: Double,
    /** 250 이상 화소 비율 — 글레어·포화 감지 */
    val satRatio: Double,
    /** Laplacian 절대값 평균 — 초점 판정 */
    val focus: Double,
    val pixels: Int,
) {
    companion object {
        val EMPTY = RoiStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
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

    /** 버퍼 정규화 좌표 → 화면 정규화 좌표. [toBuffer] 의 역변환. */
    private fun fromBuffer(bx: Float, by: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> (1f - by) to bx
            180 -> (1f - bx) to (1f - by)
            270 -> by to (1f - bx)
            else -> bx to by
        }

    /**
     * 검사 영역을 프리즘에 맞춰 따라간다.
     *
     * 폰과 조명이 고정되어 있고 배경이 광트랩이면, **화면에서 검지 않은 것은 프리즘뿐**이다.
     * 그러면 사람이 사각형을 맞출 이유가 없다 — 밝은 화소의 무게중심과 퍼짐을 재서
     * 영역이 스스로 따라가면 된다. 손으로 돌리는 동안 프리즘이 움직여도 놓치지 않는다.
     *
     * 세 가지로 폭주를 막는다.
     *
     * - **국소 탐색.** 지금 영역의 1.8배 창 안에서만 찾는다. 멀리 있는 손가락이나
     *   조명으로 뛰지 않는다. 잘못 잡히면 화면을 눌러 다시 심으면 된다.
     * - **감쇠 이동.** 한 프레임에 목표의 25%만 따라간다. 잡음으로 떨지 않는다.
     * - **물체 없음 판정.** 창 안 대비가 8 미만이면 아무것도 없다고 보고 그대로 둔다.
     *   배경만 있는데 노이즈를 물체로 오인해 영역이 흘러가는 것을 막는다.
     *
     * @return 새 영역. 따라갈 근거가 없으면 null — 호출자는 기존 영역을 유지한다.
     */
    fun locate(image: ImageProxy, roi: Roi, step: Int = 5): Roi? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (w <= 4 || h <= 4) return null

        val rot = image.imageInfo.rotationDegrees
        val (bx, by) = toBuffer(roi.cx, roi.cy, rot)
        val shortSide = minOf(w, h)
        val halfPx = (roi.size * shortSide / 2f * 1.8f).coerceIn(8f, shortSide / 2f)
        val l = (bx * w - halfPx).toInt().coerceIn(0, w - 2)
        val t = (by * h - halfPx).toInt().coerceIn(0, h - 2)
        val r = (bx * w + halfPx).toInt().coerceIn(l + 1, w - 1)
        val b = (by * h + halfPx).toInt().coerceIn(t + 1, h - 1)

        fun px(x: Int, y: Int): Int = buf.get(y * rowStride + x * pixStride).toInt() and 0xFF

        val hist = IntArray(256)
        var n = 0
        var y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                hist[px(x, y)]++
                n++
                x += step
            }
            y += step
        }
        if (n < 40) return null

        fun percentile(p: Double): Int {
            val target = (n * p).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 255
        }

        val bg = percentile(0.30)
        val hi = percentile(0.995)
        if (hi - bg < 8) return null

        // 배경보다 확실히 밝은 화소만, 밝기를 가중치로 써서 1·2차 모멘트를 모은다.
        // 좌표는 창 좌상단 기준으로 줄여 큰 수의 뺄셈에서 정밀도가 깎이지 않게 한다.
        val thr = bg + maxOf(6, ((hi - bg) * 0.35).toInt())
        var m0 = 0.0
        var mx = 0.0; var my = 0.0
        var mxx = 0.0; var myy = 0.0
        y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                val v = px(x, y)
                if (v > thr) {
                    val g = (v - thr).toDouble()
                    val dx = (x - l).toDouble()
                    val dy = (y - t).toDouble()
                    m0 += g
                    mx += g * dx; my += g * dy
                    mxx += g * dx * dx; myy += g * dy * dy
                }
                x += step
            }
            y += step
        }
        if (m0 < 1.0) return null

        val cxLocal = mx / m0
        val cyLocal = my / m0
        val sx = sqrt((mxx / m0 - cxLocal * cxLocal).coerceAtLeast(0.0))
        val sy = sqrt((myy / m0 - cyLocal * cyLocal).coerceAtLeast(0.0))

        // 표준편차의 2.2배를 반폭으로 잡으면 물체를 대체로 덮으면서 배경은 덜 문다.
        val targetHalf = (2.2 * maxOf(sx, sy)).coerceAtLeast(shortSide * 0.05)
        val targetSize = (2.0 * targetHalf / shortSide).toFloat().coerceIn(0.10f, 0.80f)

        val (ncx, ncy) = fromBuffer(
            ((l + cxLocal) / w).toFloat(),
            ((t + cyLocal) / h).toFloat(),
            rot
        )
        return Roi(
            cx = (roi.cx + (ncx - roi.cx) * 0.25f).coerceIn(0.08f, 0.92f),
            cy = (roi.cy + (ncy - roi.cy) * 0.25f).coerceIn(0.08f, 0.92f),
            size = roi.size + (targetSize - roi.size) * 0.12f,
        )
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

        val p1 = percentile(0.01)
        val median = percentile(0.50)
        val p99 = percentile(0.99)
        var max = 0
        for (v in 255 downTo 0) if (hist[v] > 0) { max = v; break }
        var sat = 0
        for (v in 250..255) sat += hist[v]

        val contrast = (p99 - median).toDouble()
        // 결함의 넓이: 정상면과 결함의 중간 밝기를 넘는 화소 비율
        val cut = median + (contrast / 2.0).toInt()
        var bright = 0
        for (v in (cut + 1).coerceIn(0, 255)..255) bright += hist[v]

        // 밝은 화소의 2차 모멘트로 선형성을 잰다.
        // 좌표를 모아두지 않고 합만 누적하므로 메모리를 쓰지 않는다.
        val thr = median + (contrast * 0.6).toInt()
        var bn = 0L
        var sx = 0.0; var sy = 0.0
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                if (px(x, y) > thr) {
                    val dx = (x - l).toDouble()
                    val dy = (y - t).toDouble()
                    bn++
                    sx += dx; sy += dy
                    sxx += dx * dx; syy += dy * dy; sxy += dx * dy
                }
                x += step
            }
            y += step
        }
        // 화소가 너무 적으면 모멘트가 불안정하다. 노이즈를 선형이라 판정하지 않도록 막는다.
        val elong = if (bn >= 20) {
            val mx = sx / bn; val my = sy / bn
            val cxx = sxx / bn - mx * mx
            val cyy = syy / bn - my * my
            val cxy = sxy / bn - mx * my
            val tr = cxx + cyy
            val det = cxx * cyy - cxy * cxy
            val disc = (tr * tr / 4.0 - det).coerceAtLeast(0.0)
            val e1 = tr / 2.0 + sqrt(disc)
            val e2 = tr / 2.0 - sqrt(disc)
            if (e2 > 0.5) sqrt(e1 / e2).coerceAtMost(10.0) else 10.0
        } else 1.0

        // 선형성 0~1 로 정규화. 신장도 1이면 0점(원형), 5 이상이면 만점(선).
        val linearity = ((elong - 1.0) / 4.0).coerceIn(0.0, 1.0)

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
            max = max,
            mean = sum.toDouble() / n,
            contrast = contrast,
            darkContrast = (median - p1).toDouble(),
            brightArea = bright.toDouble() / n,
            linearity = linearity,
            spot = (max - p99).toDouble(),
            satRatio = sat.toDouble() / n,
            focus = if (lapN > 0) lapSum / lapN else 0.0,
            pixels = n,
        )
    }
}
