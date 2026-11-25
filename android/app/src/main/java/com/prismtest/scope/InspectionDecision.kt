package com.prismtest.scope

/** A single calibrated failure can reject a piece; every enabled type must pass to accept it. */
fun overallVerdict(verdicts: List<Verdict>): Verdict = when {
    verdicts.isEmpty() -> Verdict.NOT_READY
    verdicts.any { it == Verdict.FAIL } -> Verdict.FAIL
    verdicts.any { it == Verdict.NOT_READY } -> Verdict.NOT_READY
    verdicts.any { it == Verdict.RECHECK } -> Verdict.RECHECK
    else -> Verdict.PASS
}
