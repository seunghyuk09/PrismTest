package com.prismtest.scope

/**
 * 어느 면을 봤는지.
 *
 * **역할 이름(전반사면·입사면·출사면)이 아니라 실측 치수로 라벨링한다.**
 * 어느 면이 전반사면(지문면)인지 아직 확정되지 않았고 — 기하 대칭은 짧은면(17.56),
 * 현장 호칭은 긴면(24.5)을 가리킨다 — 역할로 기록해두면 매핑이 뒤집히는 순간
 * 그동안 모은 데이터 전체를 다시 해석해야 한다. 치수는 뒤집히지 않는다.
 * 상세는 `docs/open-questions.md` §1.
 *
 * 긴면 두 개(24.5 × 24)는 따로 두지 않았다. 검사 시점에는 둘 다 투명하고 표식이
 * 없어서 검사자가 구분할 수 없다. 구분 못 하는 항목을 선택지로 주면 라벨이 오염된다.
 */
enum class Face(val key: String, val label: String, val hint: String) {
    /** 회전 스윕. 한 번에 3면을 다 훑으므로 면을 특정하지 않는다 — 기본값. */
    ALL("all", "3면 전부", "회전 스윕"),

    /** 24.5 × 24 mm. 두 개 있고 서로 구분하지 않는다. */
    LONG("long", "긴면", "24.5 mm"),

    /** 17.56 × 24 mm. 도면상 밑면. */
    SHORT("short", "짧은면", "17.56 mm");

    companion object {
        fun of(key: String?): Face = values().firstOrNull { it.key == key } ?: ALL
    }
}
