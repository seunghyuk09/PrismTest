package com.prismtest.scope

import android.content.Context
import kotlin.math.sqrt

/** 판정 결과. */
enum class Verdict(val label: String, val mark: String) {
    NOT_READY("기준 없음", "–"),
    PASS("양품", "OK"),
    RECHECK("재검", "?"),
    FAIL("불량", "NG"),
}

/** 기준선의 상태. 사용자에게 "지금 이 유형을 믿어도 되는가"를 알려준다. */
enum class ModelState(val label: String) {
    NEED_GOOD("양품 샘플 부족"),
    GOOD_ONLY("불량 샘플 없음"),
    OVERLAP("양품·불량이 겹침"),
    OK("구분 양호"),
}

/** 합격/불량 경계. 사이는 재검이다. */
data class Band(val pass: Double, val fail: Double)

/**
 * 유형별 판정 기준.
 *
 * 임계값을 내가 임의로 정하지 않는다. **사용자가 양품·불량이라고 아는 개체를 등록하면**
 * 그 두 분포 사이에서 경계를 계산한다. 조명·거리·기기가 바뀌면 숫자가 통째로
 * 달라지므로, 이 공정에서 실제로 나온 값에서 뽑는 것 말고는 방법이 없다.
 *
 *   불량 샘플이 있으면  → 두 분포 사이의 경계
 *   양품만 있으면       → 평균+3σ 합격, 평균+6σ 불량 (한쪽만 아는 보수적 기준)
 *
 * 두 분포가 겹치면 [ModelState.OVERLAP] 을 띄운다. 이건 알고리즘의 실패가 아니라
 * **지금 조명으로는 그 유형이 안 보인다**는 사실이고, 숨기면 안 되는 정보다.
 */
data class DefectModel(
    val good: List<Double> = emptyList(),
    val bad: List<Double> = emptyList(),
) {
    val state: ModelState
        get() = when {
            good.size < MIN_GOOD -> ModelState.NEED_GOOD
            bad.size < MIN_BAD -> ModelState.GOOD_ONLY
            separated -> ModelState.OK
            else -> ModelState.OVERLAP
        }

    val ready: Boolean get() = good.size >= MIN_GOOD

    /** 불량 쪽이 확실히 위에 있고, 3σ 구간이 서로 떨어져 있는가. */
    private val separated: Boolean
        get() = bad.size >= MIN_BAD &&
            mean(bad) > mean(good) &&
            mean(bad) - 3 * sd(bad) > mean(good) + 3 * sd(good)

    val band: Band?
        get() {
            if (good.size < MIN_GOOD) return null
            val gm = mean(good); val gs = sd(good)
            val gHi = gm + 3 * gs
            if (bad.size < MIN_BAD) return Band(gHi, gm + 6 * gs)

            val bm = mean(bad); val bs = sd(bad)
            // 불량이 양품보다 낮게 나온다 = 이 점수로는 그 유형을 못 본다.
            // 억지로 경계를 만들면 거짓 판정이 되므로 양품 분포만 쓴다.
            if (bm <= gm) return Band(gHi, gm + 6 * gs)

            val bLo = bm - 3 * bs
            if (bLo > gHi) return Band(gHi, bLo)
            // 겹치는 구간은 재검으로 넓게 남긴다. 미검이 과검보다 훨씬 비싸다.
            return Band(gm + (bm - gm) * 0.35, gm + (bm - gm) * 0.65)
        }

    fun judge(score: Double): Verdict {
        val b = band ?: return Verdict.NOT_READY
        return when {
            score < b.pass -> Verdict.PASS
            score < b.fail -> Verdict.RECHECK
            else -> Verdict.FAIL
        }
    }

    fun plusGood(v: Double) = copy(good = good + v)
    fun plusBad(v: Double) = copy(bad = bad + v)

    companion object {
        const val MIN_GOOD = 5
        const val MIN_BAD = 3

        private const val PREF = "prismscope"

        fun mean(x: List<Double>) = if (x.isEmpty()) 0.0 else x.sum() / x.size

        /** σ 가 0 에 붙으면 경계가 평균에 달라붙는다. 최소 폭을 준다. */
        fun sd(x: List<Double>): Double {
            if (x.size < 2) return 1.0
            val m = mean(x)
            return maxOf(sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1)), 1.0)
        }

        fun load(context: Context, type: DefectType): DefectModel {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            fun read(k: String) = (p.getString("m_${type.key}_$k", "") ?: "")
                .split(",").mapNotNull { it.trim().toDoubleOrNull() }
            return DefectModel(read("good"), read("bad"))
        }

        fun save(context: Context, type: DefectType, m: DefectModel) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("m_${type.key}_good", m.good.joinToString(","))
                .putString("m_${type.key}_bad", m.bad.joinToString(","))
                .apply()
        }
    }
}
