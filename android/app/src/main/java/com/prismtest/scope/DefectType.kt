package com.prismtest.scope

/**
 * 검사하는 불량 유형.
 *
 * 누적 이력(n=61,488)에서 표면 얼룩·흰색 이물·스크래치·검정 이물이 불량의 97.9% 다.
 * 나머지(페인트 얼룩·Dig·파손)는 건수가 적거나 조립 이후 공정이라 여기서 다루지 않는다.
 *
 * 네 유형 모두 암시야에서 **밝게 뜨거나 어둡게 막는다**는 점은 같고, 다른 것은 모양이다.
 * 그래서 [RoiStats] 의 원시 특징을 유형별로 다르게 조합해 점수를 만든다.
 * 조합식은 "이렇게 생긴 것"이라는 물리적 정의이지 임계값이 아니다.
 * **임계값은 사용자가 등록한 양품·불량 샘플에서 나온다** — [DefectModel] 참조.
 */
enum class DefectType(
    val key: String,
    val label: String,
    /** 화면에 띄우는 한 줄 설명. 사용자가 무엇을 등록하는지 알아야 한다. */
    val hint: String,
) {
    STAIN("stain", "표면 얼룩", "넓게 퍼져 뿌옇게 밝은 것"),
    WHITE("white", "흰색 이물", "작고 아주 밝은 알갱이"),
    SCRATCH("scratch", "스크래치", "가늘고 긴 밝은 선"),
    BLACK("black", "검정 이물", "빛을 막아 검게 보이는 점"),
    ;

    /**
     * 이 유형의 점수. 클수록 그 유형에 가깝다.
     *
     * 곱셈을 쓰는 이유: 밝기만 보면 세 유형이 뒤섞이고, 모양만 보면 흐릿한 자국까지
     * 걸린다. **밝으면서 동시에 그 모양일 때만** 점수가 오르게 해야 유형이 갈린다.
     */
    fun score(s: RoiStats): Double = when (this) {
        // 넓게 퍼진 것: 대비가 있고, 넓이가 넓고, 선형이 아니다
        STAIN -> s.contrast * (1.0 - s.linearity) * areaGain(s.brightArea)
        // 작고 밝은 점: 최대값이 상위 1%보다 튀어나오고, 선형이 아니다
        WHITE -> s.spot * (1.0 - s.linearity)
        // 선: 대비가 있고 선형이다
        SCRATCH -> s.contrast * s.linearity
        // 빛을 막는 것: 아래쪽 대비로만 보인다
        BLACK -> s.darkContrast
    }

    companion object {
        /**
         * 넓이 가중. 검사 영역의 1% 이상을 덮으면 만점.
         * 알갱이 하나(수십 화소)와 퍼진 얼룩을 가르는 것이 이 항이다.
         */
        private fun areaGain(a: Double) = (a / 0.01).coerceIn(0.0, 1.0)
    }
}
