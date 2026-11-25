package com.prismtest.scope

import android.Manifest
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real CameraX binding on the test device, including the resized shared viewport.
 * Emulator dimensions are evidence of negotiation only, not physical-phone sharpness or speed. */
@RunWith(AndroidJUnit4::class)
class CameraQualityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun screen() = nodes(instrumentation.uiAutomation.rootInActiveWindow)

    private fun await(description: String, condition: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        val until = SystemClock.elapsedRealtime() + 60_000
        var last = emptyList<AccessibilityNodeInfo>()
        while (SystemClock.elapsedRealtime() < until) {
            last = screen()
            if (condition(last)) return last
            SystemClock.sleep(250)
        }
        error("Timed out: $description; UI=${last.mapNotNull { it.text?.toString() }}")
    }

    private fun click(text: String) {
        val node = await("enabled $text") { list ->
            list.any { it.text?.toString() == text && it.isEnabled }
        }.first { it.text?.toString() == text && it.isEnabled }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Click $text", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun stream(label: String? = null, previousCrop: List<Int>? = null): List<Int> {
        val pattern = Regex("연결 (표준|정밀) · 수신 (\\d+)×(\\d+) · 저장 (\\d+)×(\\d+)")
        val found = await("camera stream ${label.orEmpty()}") { list -> list.any {
            val match = pattern.matchEntire(it.text?.toString().orEmpty())
            match != null && (label == null || match.groupValues[1] == label) &&
                match.groupValues.drop(2).all { size -> size.toInt() > 0 } &&
                (previousCrop == null || match.groupValues.takeLast(2).map(String::toInt) != previousCrop)
        } }.mapNotNull { pattern.matchEntire(it.text?.toString().orEmpty()) }.first()
        Log.i("PrismQualityTest", found.value)
        return found.groupValues.drop(2).map(String::toInt)
    }

    @Test fun switchesQualityAndRebindsTheSharedViewportWhenPreviewGrows() {
        val context = instrumentation.targetContext
        val previous = Prefs.captureQuality(context)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        Prefs.setCaptureQuality(context, CaptureQuality.STANDARD)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val standard = stream("표준")
                click("정밀")
                await("detail request visible") { list ->
                    list.any { it.text?.toString()?.startsWith("정밀은 2560×1920 요청") == true }
                }
                val detail = stream()
                assertEquals(CaptureQuality.DETAIL, Prefs.captureQuality(context))
                assertTrue("Crop cannot exceed source", detail[2].toLong() * detail[3] <= detail[0].toLong() * detail[1])
                click("카메라 크게")
                await("large preview") { list -> list.any { it.text?.toString() == "기본 화면" } }
                val large = stream(previousCrop = detail.drop(2))
                assertNotEquals("Larger preview must renegotiate its cropped viewport", detail.drop(2), large.drop(2))
                click("기본 화면")
                stream()
                click("표준")
                await("standard selected") { list -> list.none { node ->
                    node.text?.toString()?.startsWith("정밀은 2560×1920 요청") == true
                } }
                val restored = stream("표준")
                assertEquals("Standard source restored", standard.take(2), restored.take(2))
            }
        } finally { Prefs.setCaptureQuality(context, previous) }
    }
}
