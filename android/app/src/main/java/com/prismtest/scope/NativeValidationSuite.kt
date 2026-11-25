package com.prismtest.scope

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.system.measureNanoTime

data class NativeCheck(val name: String, val passed: Boolean, val description: String,
                       val milliseconds: Double)
data class NativeSuiteResult(val checks: List<NativeCheck>, val benchmarkMs: List<Double>) {
    val passed: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }
}

/** On-device tests call precisely the same JNI/C++ implementation as live camera analysis. */
object NativeValidationSuite {
    fun run(): NativeSuiteResult {
        val w = 160; val h = 140
        fun base() = ByteArray(w * h) { 28 }
        fun spot(pixels: ByteArray, x: Int, y: Int) {
            for (dy in -1..1) for (dx in -1..1)
                pixels[(y + dy) * w + x + dx] = (130 - 18 * (abs(dx) + abs(dy))).toByte()
        }
        fun detect(pixels: ByteArray): DigResult {
            val buffer = ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); rewind() }
            return NativeDig.detect(buffer, 0, w, 1, 0, 0, w, h, w, h, 0)
        }
        val checks = mutableListOf<NativeCheck>()
        fun check(name: String, description: String, test: () -> Boolean) {
            var pass = false
            val time = measureNanoTime { pass = test() } / 1e6
            checks += NativeCheck(name, pass, description, time)
        }
        check("균일한 정상 배경", "점 후보가 없어야 함") { detect(base()).spots.isEmpty() }
        check("단일 핫픽셀", "1화소 잡음을 디그 점으로 세지 않음") {
            val p = base(); p[70 * w + 80] = 250.toByte(); detect(p).spots.isEmpty()
        }
        val two = base().apply { spot(this, 45, 60); spot(this, 110, 85) }
        check("서로 떨어진 두 점", "두 정답을 각각 하나의 후보로 연결") {
            val r = detect(two)
            val c = ValidationMetrics.compare(listOf(ValidationPoint(45.5, 60.5), ValidationPoint(110.5, 85.5)),
                r.spots.map { ValidationPoint(it.cx * w.toDouble(), it.cy * h.toDouble()) }, 3.0, true)
            c.matches.size == 2 && c.extra.isEmpty()
        }
        check("긴 반사선", "긴 선을 점 손상으로 분류하지 않음") {
            val p = base(); for (x in 20..134) p[70 * w + x] = 160.toByte()
            detect(p).spots.isEmpty()
        }
        check("밝은 띠와 옆의 점", "넓은 반사광을 가려도 떨어진 점은 찾아야 함") {
            val p = base()
            for (y in 20..39) for (x in 0 until w) p[y * w + x] = 250.toByte()
            spot(p, 80, 95)
            val r = detect(p)
            r.maskedFraction > .1 && r.spots.any { abs(it.cx * w - 80.5) < 3 && abs(it.cy * h - 95.5) < 3 }
        }
        check("완만한 밝기 변화", "균일하지 않은 배경만으로 점을 만들지 않음") {
            val p = ByteArray(w * h) { at -> (20 + (at % w) / 4 + (at / w) / 5).toByte() }
            detect(p).spots.isEmpty()
        }
        check("낮은 수준의 잡음", "고정된 작은 잡음에 후보가 없어야 함") {
            detect(ByteArray(w * h) { (26 + (it * 17 + it / w * 13) % 5).toByte() }).spots.isEmpty()
        }
        check("90도 표시 좌표", "세로 화면으로 회전해도 검출 위치가 대응") {
            val buffer = ByteBuffer.allocateDirect(two.size).apply { put(two); rewind() }
            val a = NativeDig.detect(buffer, 0, w, 1, 0, 0, w, h, w, h, 0)
            val b = NativeDig.detect(buffer, 0, w, 1, 0, 0, w, h, w, h, 90)
            a.spots.size == 2 && b.spots.size == a.spots.size && a.spots.zip(b.spots).all {
                (x, y) -> abs(1 - x.cy - y.cx) < .0001 && abs(x.cx - y.cy) < .0001
            }
        }
        check("Y 평면 패딩·픽셀 간격", "카메라 rowStride·pixelStride·시작 위치가 달라도 동일") {
            val row = w * 2 + 24; val start = 19
            val p = ByteBuffer.allocateDirect(start + (h - 1) * row + (w - 1) * 2 + 1)
            for (y in 0 until h) for (x in 0 until w) p.put(start + y * row + x * 2, two[y * w + x])
            val a = detect(two)
            val b = NativeDig.detect(p, start, row, 2, 0, 0, w, h, w, h, 0)
            a.spots.size == b.spots.size && abs(a.score - b.score) < .00001
        }
        check("잘못된 영상 버퍼", "범위를 벗어난 접근을 예외로 거부") {
            try { NativeDig.detect(ByteBuffer.allocateDirect(16), 0, w, 1, 0, 0, w, h, w, h, 0); false }
            catch (_: IllegalArgumentException) { true }
        }
        val buffer = ByteBuffer.allocateDirect(two.size).apply { put(two); rewind() }
        repeat(5) { NativeDig.detect(buffer, 0, w, 1, 0, 0, w, h, w, h, 0) }
        val times = List(40) {
            measureNanoTime { NativeDig.detect(buffer, 0, w, 1, 0, 0, w, h, w, h, 0) } / 1e6
        }
        return NativeSuiteResult(checks, times)
    }
}
