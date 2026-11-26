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

    private fun stream(previousCrop: List<Int>? = null): List<Int> {
        val full = Regex("수신 (\\d+)×(\\d+) · 저장 (\\d+)×(\\d+)")
        val view = Regex("프리뷰 (\\d+)×(\\d+) · 검사화면 (\\d+)×(\\d+) · 분석 (\\d+)fps")
        val found = await("full color camera stream") { list ->
            val a = list.mapNotNull { full.matchEntire(it.text?.toString().orEmpty()) }.firstOrNull()
            val b = list.mapNotNull { view.matchEntire(it.text?.toString().orEmpty()) }.firstOrNull()
            // The expanded camera leaves a 150dp scrolling controls panel. Focus guidance
            // can put the second resolution line below its visible area; reveal it rather
            // than interpreting an offscreen label as a stalled CameraX stream.
            if (a != null && b == null) list.firstOrNull {
                it.isScrollable && it.className?.toString() == "android.widget.ScrollView"
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            a != null && b != null && a.groupValues.drop(1).all { it.toInt() > 0 } &&
                b.groupValues.drop(1).take(4).all { it.toInt() > 0 } &&
                (previousCrop == null || b.groupValues.drop(3).take(2).map(String::toInt) != previousCrop)
        }
        val a=found.mapNotNull { full.matchEntire(it.text?.toString().orEmpty()) }.first()
        val b=found.mapNotNull { view.matchEntire(it.text?.toString().orEmpty()) }.first()
        Log.i("PrismQualityTest", a.value + " / " + b.value)
        val sizes=a.groupValues.drop(1).map(String::toInt)
        assertEquals("Saved frame must keep every source pixel", sizes[0].toLong()*sizes[1], sizes[2].toLong()*sizes[3])
        return sizes + b.groupValues.drop(3).take(2).map(String::toInt)
    }

    @Test fun switchesQualityAndRebindsTheSharedViewportWhenPreviewGrows() {
        val context = instrumentation.targetContext
        val previous = Prefs.captureQuality(context)
        val previousSize = Prefs.roiSize(context)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        Prefs.setCaptureQuality(context, CaptureQuality.FHD)
        Prefs.setRoiSize(context, .65f)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val standard = stream()
                fun areaIs(percent: Int) = await("ROI $percent%") { list ->
                    list.any { node -> node.contentDescription?.toString() == "검사 영역 $percent%" }
                }
                areaIs(65)
                click("+")
                areaIs(70)
                assertEquals(.70f, Prefs.roiSize(context), .001f)
                click("−")
                areaIs(65)
                click("4K")
                await("detail request visible") { list ->
                    list.any { it.text?.toString()?.startsWith("4K 3840×2160 요청") == true }
                }
                val detail = stream()
                for (mode in listOf(CaptureQuality.HD, CaptureQuality.FHD, CaptureQuality.QHD, CaptureQuality.UHD)) {
                    click(mode.label)
                    await("mode ${mode.label}") { list -> list.any {
                        it.text?.toString()?.startsWith("${mode.label} ${mode.target.width}×${mode.target.height} 요청") == true
                    } }
                    stream()
                    assertEquals(mode, Prefs.captureQuality(context))
                }
                assertEquals(CaptureQuality.UHD, Prefs.captureQuality(context))
                assertTrue("Crop cannot exceed source", detail[2].toLong() * detail[3] <= detail[0].toLong() * detail[1])
                click("카메라 크게")
                await("large preview") { list -> list.any { it.text?.toString() == "기본 화면" } }
                val large = stream(previousCrop = detail.takeLast(2))
                assertNotEquals("Larger preview must renegotiate its cropped viewport", detail.takeLast(2), large.takeLast(2))
                click("기본 화면")
                stream()
                click("FHD")
                await("standard selected") { list -> list.none { node ->
                    node.text?.toString()?.startsWith("4K 3840×2160 요청") == true
                } }
                val restored = stream()
                assertEquals("Standard source restored", standard.take(2), restored.take(2))
            }
        } finally {
            Prefs.setCaptureQuality(context, previous)
            Prefs.setRoiSize(context, previousSize)
        }
    }
}
