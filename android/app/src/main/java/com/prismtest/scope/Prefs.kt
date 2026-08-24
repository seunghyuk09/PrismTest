package com.prismtest.scope

import android.content.Context

/**
 * 사용자가 고른 운영 설정.
 *
 * 판정 기준([DefectModel])과 달리 이쪽은 "어떻게 검사할지"에 대한 취향이다.
 * 앱을 껐다 켜도 유지되어야 한다 — 매번 다시 맞추게 하면 결국 기본값으로 쓰게 되고,
 * 그러면 설정이 있으나 마나다.
 */
object Prefs {
    private const val PREF = "prismscope"

    /** 검사 시간 후보. 짧으면 각도를 못 훑고, 길면 손이 흔들린다. */
    val INSPECT_CHOICES = listOf(5, 8, 12, 20)

    /** 등록 시간 후보. 기준을 만드는 작업이라 검사보다 넉넉하게 훑는다. */
    val ENROLL_CHOICES = listOf(10, 20, 30, 45)

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun inspectSec(c: Context) = p(c).getInt("sec_inspect", 8)
    fun setInspectSec(c: Context, v: Int) = p(c).edit().putInt("sec_inspect", v).apply()

    fun enrollSec(c: Context) = p(c).getInt("sec_enroll", 20)
    fun setEnrollSec(c: Context, v: Int) = p(c).edit().putInt("sec_enroll", v).apply()

    fun autoTrack(c: Context) = p(c).getBoolean("auto_track", true)
    fun setAutoTrack(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_track", v).apply()
}
