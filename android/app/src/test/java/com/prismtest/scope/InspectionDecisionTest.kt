package com.prismtest.scope

import org.junit.Assert.*
import org.junit.Test

class InspectionDecisionTest {
    private val good = listOf(9.0, 10.0, 11.0, 10.0, 10.0)
    @Test fun missingTypeCannotProduceOverallPass() {
        assertEquals(Verdict.NOT_READY, overallVerdict(listOf(Verdict.PASS, Verdict.NOT_READY)))
    }
    @Test fun allTypesMustPass() {
        assertEquals(Verdict.PASS, overallVerdict(List(5) { Verdict.PASS }))
        assertEquals(Verdict.RECHECK, overallVerdict(listOf(Verdict.PASS, Verdict.RECHECK)))
        assertEquals(Verdict.NOT_READY, overallVerdict(emptyList()))
    }
    @Test fun calibratedFailureCanRejectEvenIfAnotherTypeIsUnknown() {
        assertEquals(Verdict.FAIL, overallVerdict(listOf(Verdict.NOT_READY, Verdict.FAIL)))
    }
    @Test fun goodOnlyDoesNotClaimValidatedSeparation() {
        val m = DefectModel(good)
        assertEquals(ModelState.GOOD_ONLY, m.state)
        assertEquals(Verdict.RECHECK, m.judge(0.0))
        assertEquals(Verdict.RECHECK, m.judge(100.0))
    }
    @Test fun overlappingOrReversedLabelsCannotPass() {
        for (bad in listOf(listOf(9.0, 10.0, 11.0), listOf(2.0, 3.0, 4.0))) {
            val m = DefectModel(good, bad)
            assertEquals(ModelState.OVERLAP, m.state)
            assertEquals(Verdict.RECHECK, m.judge(0.0))
            assertEquals(Verdict.RECHECK, m.judge(20.0))
        }
    }
    @Test fun separatedSamplesGiveThreeWayDecision() {
        val m = DefectModel(good, listOf(99.0, 100.0, 101.0))
        assertEquals(ModelState.OK, m.state)
        assertEquals(Verdict.PASS, m.judge(1.0))
        assertEquals(Verdict.RECHECK, m.judge(50.0))
        assertEquals(Verdict.FAIL, m.judge(200.0))
    }
    @Test fun invalidOrMissingScoresDoNotPass() {
        val m = DefectModel(good, listOf(99.0, 100.0, 101.0))
        assertEquals(Verdict.RECHECK, m.judge(Double.NaN))
        assertEquals(Verdict.RECHECK, m.judge(Double.POSITIVE_INFINITY))
        assertEquals(Verdict.NOT_READY, DefectModel().judge(0.0))
    }
    @Test fun newDigChannelHasItsOwnKey() {
        assertEquals(5, DefectType.values().size)
        assertEquals("dig_v1", DefectType.DIG.key)
        assertNotEquals(DefectType.WHITE.key, DefectType.DIG.key)
    }
}
