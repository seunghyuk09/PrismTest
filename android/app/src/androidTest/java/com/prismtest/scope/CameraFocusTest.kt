package com.prismtest.scope

import android.Manifest
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Validates the real CameraX/CaptureResult lock path, not optical focus on an emulated lens. */
@RunWith(AndroidJUnit4::class)
class CameraFocusTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun await(description: String, condition: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        val until = SystemClock.elapsedRealtime() + 60_000
        var last = emptyList<AccessibilityNodeInfo>()
        while (SystemClock.elapsedRealtime() < until) {
            last = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            if (condition(last)) return last
            SystemClock.sleep(200)
        }
        error("Timed out: $description; UI=${last.mapNotNull { it.text?.toString() }}")
    }
    private fun click(text: String) {
        await("click enabled $text") { list ->
            var target = list.firstOrNull { it.text?.toString() == text }
            while (target != null && !target.isClickable) target = target.parent
            // A disabled Compose button's child Text can still report isEnabled=true.
            // Check the clickable ancestor and retry an action invalidated by recomposition.
            target?.let { it.isEnabled && it.isVisibleToUser &&
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }
    private fun locked() = await("confirmed hardware lock and manual exposure/WB") { list ->
        list.any { it.text?.toString() in listOf("초점 잠금 확인됨", "고정 초점 렌즈 확인됨") } &&
            list.any { it.text?.toString() == "촬영 조건 고정됨" }
    }

    private fun withCamera(block: () -> Unit) {
        val context = instrumentation.targetContext
        val previous = Prefs.captureQuality(context)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        Prefs.setCaptureQuality(context, CaptureQuality.FHD)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                await("first received camera frame") { list -> list.any {
                    Regex("수신 [1-9]\\d*×[1-9]\\d* · 저장 [1-9]\\d*×[1-9]\\d*").matches(it.text?.toString().orEmpty())
                } }
                block()
            }
        } finally { Prefs.setCaptureQuality(context, previous) }
    }

    @Test fun currentFocusHoldPersistsUntilAutomaticAndMustBeReacquiredAfterRebind() = withCamera {
                click("현재 초점 고정")
                locked()
                // CameraX's default auto-cancel is 5 seconds. Inspection commonly runs 8–20s.
                SystemClock.sleep(6_500)
                locked()
                click("자동")
                await("unlocked") { list -> list.any { it.text?.toString() == "현재 초점 고정" && it.isEnabled } &&
                    list.none { it.text?.toString() in listOf("초점 잠금 확인됨", "고정 초점 렌즈 확인됨") } }
                click("현재 초점 고정")
                locked()
                click("4K")
                await("rebind releases the old lock") { list ->
                    list.any { it.text?.toString() == "현재 초점 고정" && it.isEnabled } &&
                        list.none { it.text?.toString() in listOf("초점 잠금 확인됨", "고정 초점 렌즈 확인됨") }
                }
                click("현재 초점 고정")
                locked()
    }

    @Test fun explicitRefocusReportsItsActualOutcomeAndCanRecoverFromAFailure() = withCamera {
        click("현재 초점 고정")
        locked()
        click("초점 다시 맞춤")
        await("new refocus operation starts") { list ->
            list.any { it.text?.toString() == "초점 고정 중…" || it.text?.toString() == "고정 초점 렌즈 확인됨" }
        }
        val terminal = await("refocus confirmed or honest AF failure") { list ->
            list.any { it.text?.toString()?.startsWith("초점이 잡히지 않았습니다") == true } ||
                (list.any { it.text?.toString() in listOf("초점 잠금 확인됨", "고정 초점 렌즈 확인됨") } &&
                    list.any { it.text?.toString() == "촬영 조건 고정됨" })
        }
        if (terminal.any { it.text?.toString()?.startsWith("초점이 잡히지 않았습니다") == true }) {
            assertFalse(terminal.any { it.text?.toString() == "촬영 조건 고정됨" })
            click("현재 초점 고정")
            locked()
        } else locked()
    }
}
