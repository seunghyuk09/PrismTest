package com.prismtest.scope

import kotlin.math.hypot

data class ValidationPoint(val x: Double, val y: Double)
data class ValidationRoi(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun validFor(width: Int, height: Int): Boolean = left >= 0 && top >= 0 &&
        right <= width && bottom <= height && this.width >= 20 && this.height >= 20 &&
        this.width.toLong() * this.height <= 1_000_000
    fun contains(p: ValidationPoint) = p.x >= left && p.x < right && p.y >= top && p.y < bottom
}
data class ValidationMatch(val truthIndex: Int, val candidateIndex: Int, val errorPx: Double)
data class ValidationComparison(val matches: List<ValidationMatch>, val missed: List<Int>,
    val extra: List<Int>, val completeTruth: Boolean) {
    val labelledPointAgreement: Double?
        get() = (matches.size + missed.size).takeIf { it > 0 }?.let { matches.size.toDouble() / it }
    val precision: Double?
        get() = if (!completeTruth) null else (matches.size + extra.size).takeIf { it > 0 }
            ?.let { matches.size.toDouble() / it }
    val recall: Double? get() = if (completeTruth) labelledPointAgreement else null
}

object ValidationMetrics {
    /** Maximum-cardinality one-to-one matching. An output point cannot explain two labels. */
    fun compare(truth: List<ValidationPoint>, candidates: List<ValidationPoint>, tolerance: Double,
                completeTruth: Boolean): ValidationComparison {
        require(tolerance.isFinite() && tolerance > 0)
        require(truth.size <= 256 && candidates.size <= 256)
        require((truth + candidates).all { it.x.isFinite() && it.y.isFinite() })
        val neighbours = truth.map { target -> candidates.indices.filter {
            hypot(target.x - candidates[it].x, target.y - candidates[it].y) <= tolerance
        }.sortedBy { hypot(target.x - candidates[it].x, target.y - candidates[it].y) } }
        val owner = IntArray(candidates.size) { -1 }
        fun assign(label: Int, visited: BooleanArray): Boolean {
            for (candidate in neighbours[label]) {
                if (visited[candidate]) continue
                visited[candidate] = true
                if (owner[candidate] == -1 || assign(owner[candidate], visited)) {
                    owner[candidate] = label
                    return true
                }
            }
            return false
        }
        truth.indices.sortedBy { neighbours[it].size }.forEach { assign(it, BooleanArray(candidates.size)) }
        val matched = owner.indices.filter { owner[it] >= 0 }.map {
            val t = owner[it]
            ValidationMatch(t, it, hypot(truth[t].x - candidates[it].x, truth[t].y - candidates[it].y))
        }.sortedBy { it.truthIndex }
        return ValidationComparison(matched, truth.indices.filter { t -> matched.none { it.truthIndex == t } },
            owner.indices.filter { owner[it] == -1 }, completeTruth)
    }

    fun percentile(samples: List<Double>, quantile: Double): Double? {
        require(quantile in 0.0..1.0)
        val finite = samples.filter { it.isFinite() && it >= 0 }.sorted()
        if (finite.isEmpty()) return null
        return finite[(kotlin.math.ceil(quantile * finite.size).toInt() - 1).coerceIn(finite.indices)]
    }

    /** Decoded video sampling is capped and is not a full-FPS camera performance test. */
    fun sampleTimes(durationMs: Long, maximum: Int = 30): List<Long> {
        require(durationMs > 0 && maximum in 2..100)
        val end = (durationMs - 1).coerceAtLeast(0)
        val count = minOf(maximum.toLong(), end / 500 + 1).toInt().coerceAtLeast(1)
        return if (count == 1) listOf(0) else (0 until count).map { end * it / (count - 1) }.distinct()
    }
}
