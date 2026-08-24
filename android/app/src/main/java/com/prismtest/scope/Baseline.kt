package com.prismtest.scope

import android.content.Context
import kotlin.math.sqrt

/** 판정 결과. */
enum class Verdict(val label: String) {
    NOT_READY("기준 미설정"),
    PASS("양품"),
    RECHECK("재검"),
    FAIL("불량"),
}

/**
 * 양품 기준선.
 *
 * 임계값을 내가 임의로 정하지 않는다. **사용자가 양품이라고 아는 개체를 등록하면**
 * 그 분포에서 임계값을 계산한다. 남의 공정에 맞는 숫자를 지어내는 것보다
 * 이 공정에서 실제로 나온 값에서 뽑는 편이 옳다.
 *
 *   합격 상한 = 평균 + 3σ
 *   불량 하한 = 평균 + 6σ
 *   그 사이는 재검 — 사람이 본다
 *
 * 초기 운영은 재검 구간을 넓게 두고, 데이터가 쌓이면 좁힌다.
 * 미검(불량을 양품으로 흘림)이 과검보다 훨씬 비싸기 때문이다.
 */
data class Baseline(
    val samples: List<Double> = emptyList(),
) {
    val n: Int get() = samples.size
    val mean: Double get() = if (n == 0) 0.0 else samples.sum() / n
    val sd: Double
        get() {
            if (n < 2) return 0.0
            val m = mean
            return sqrt(samples.sumOf { (it - m) * (it - m) } / (n - 1))
        }

    /** 표본이 너무 적으면 판정하지 않는다. σ 가 신뢰할 수 없어서다. */
    val ready: Boolean get() = n >= MIN_SAMPLES

    /** σ 가 0 에 가까울 때 임계값이 평균에 붙어버리지 않도록 최소 폭을 준다. */
    private val effSd: Double get() = maxOf(sd, 1.0)

    val passLimit: Double get() = mean + 3 * effSd
    val failLimit: Double get() = mean + 6 * effSd

    fun judge(stainIndex: Double): Verdict = when {
        !ready -> Verdict.NOT_READY
        stainIndex < passLimit -> Verdict.PASS
        stainIndex < failLimit -> Verdict.RECHECK
        else -> Verdict.FAIL
    }

    fun plus(v: Double) = copy(samples = samples + v)
    fun cleared() = Baseline()

    companion object {
        const val MIN_SAMPLES = 5

        private const val PREF = "prismscope"

        /** 얼룩과 스크래치는 척도가 달라 기준선을 따로 둔다. */
        const val KIND_STAIN = "stain"
        const val KIND_SCRATCH = "scratch"

        fun load(context: Context, kind: String): Baseline {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("baseline_$kind", "") ?: ""
            val list = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            return Baseline(list)
        }

        fun save(context: Context, kind: String, b: Baseline) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("baseline_$kind", b.samples.joinToString(",")).apply()
        }
    }
}
