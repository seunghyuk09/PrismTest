package com.prismtest.scope

import android.hardware.camera2.CaptureResult
import org.junit.Assert.*
import org.junit.Test

class FocusLockPolicyTest {
    private val caps = CameraCaps(supportsAutoFocus = true, minFocusDistance = 10f)
    private val focused = AppliedCamera(iso = 80, exposureNs = 4_000_000L, focusDiopter = 7.35f,
        afMode = CaptureResult.CONTROL_AF_MODE_AUTO, afState = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
    private val previous = ManualSettings(focusDiopter = 4f, focusControl = FocusControl.MANUAL)
    private fun lock(reading: AppliedCamera = focused, limits: CameraCaps = caps, id: String = "lock-1") =
        FocusLockPolicy.snapshot(previous, reading, limits, id)

    @Test fun fixedAndUnreportedMinimumDistanceAreDifferentCapabilities() {
        assertFalse(FocusLockPolicy.supportsAutoFocus(true, 0f))
        assertTrue(FocusLockPolicy.supportsAutoFocus(true, 10f))
        assertTrue(FocusLockPolicy.supportsAutoFocus(true, null))
        assertFalse(FocusLockPolicy.supportsAutoFocus(false, 10f))
    }

    @Test fun successfulAfKeepsTheHardwareLockInsteadOfReplayingAnOldManualDistance() {
        val snapshot = requireNotNull(lock())
        assertTrue(snapshot.locked)
        assertEquals(FocusControl.AUTO_LOCK, snapshot.focusControl)
        assertEquals(7.35f, requireNotNull(snapshot.focusDiopter), 0f)
        assertEquals(80, snapshot.iso)
        assertEquals(4_000_000L, snapshot.exposureNs)
        assertEquals("", snapshot.focusLockId)
    }

    @Test fun currentFocusHoldRequiresALockedContinuousModeWithoutAnotherSweep() {
        val reading = focused.copy(afMode = CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val held = requireNotNull(FocusLockPolicy.snapshot(previous, reading, caps, "current", FocusControl.CURRENT_LOCK))
        assertEquals(FocusControl.CURRENT_LOCK, held.focusControl)
        assertTrue(reading.focusHeld(held, caps))
        assertFalse(focused.focusHeld(held, caps))
        assertNull(FocusLockPolicy.snapshot(previous,
            reading.copy(afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED), caps, "current", FocusControl.CURRENT_LOCK))
        assertEquals(reading.focusDiopter, held.focusDiopter)
    }

    @Test fun missingDistanceNeverFallsBackToThePreviousSetting() {
        val snapshot = requireNotNull(lock(focused.copy(focusDiopter = null)))
        assertNull(snapshot.focusDiopter)
        assertEquals(FocusControl.AUTO_LOCK, snapshot.focusControl)
        assertEquals("lock-1", snapshot.focusLockId)
        assertNotEquals(snapshot.focusLockId,
            requireNotNull(lock(focused.copy(focusDiopter = null), id = "lock-2")).focusLockId)
    }

    @Test fun infinityIsARealDistanceAndDoesNotMeanUnknown() {
        val snapshot = requireNotNull(lock(focused.copy(focusDiopter = 0f)))
        assertEquals(0f, requireNotNull(snapshot.focusDiopter), 0f)
        assertEquals("", snapshot.focusLockId)
    }

    @Test fun invalidDistancesStayUnknownWithoutDiscardingAConfirmedHardwareLock() {
        for (distance in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            val snapshot = requireNotNull(lock(focused.copy(focusDiopter = distance)))
            assertNull(snapshot.focusDiopter)
            assertEquals("lock-1", snapshot.focusLockId)
        }
    }

    @Test fun searchingPassiveOrFailedLocksCannotStartInspection() {
        for (state in listOf(null, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
            assertNull("AF state $state must not count as a lock", lock(focused.copy(afState = state)))
        }
        assertNull(lock(focused.copy(lensMoving = true)))
        assertNull(lock(focused.copy(afMode = CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE)))
    }

    @Test fun missingExposureReadingsCannotBeReplacedWithUnrelatedDefaults() {
        assertNull(lock(focused.copy(iso = null)))
        assertNull(lock(focused.copy(exposureNs = null)))
        assertNull(lock(focused.copy(iso = 0)))
        assertNull(lock(focused.copy(exposureNs = -1L)))
    }

    @Test fun fixedFocusDoesNotWaitForAnImpossibleAutofocusState() {
        val fixed = focused.copy(afMode = CaptureResult.CONTROL_AF_MODE_OFF, afState = null, focusDiopter = null)
        val snapshot = requireNotNull(lock(fixed, CameraCaps()))
        assertNull(snapshot.focusDiopter)
        assertEquals("", snapshot.focusLockId)
        assertTrue(fixed.focusHeld(snapshot, CameraCaps()))
        assertNull(lock(fixed.copy(lensMoving = true), CameraCaps()))
    }

    @Test fun manualFocusNeedsTheRequestedDistanceToActuallyBeApplied() {
        val manual = ManualSettings(locked = true, focusControl = FocusControl.MANUAL, focusDiopter = 4f)
        val reading = focused.copy(afMode = CaptureResult.CONTROL_AF_MODE_OFF, focusDiopter = 4f)
        assertTrue(reading.focusHeld(manual, caps))
        assertFalse(reading.copy(focusDiopter = null).focusHeld(manual, caps))
        assertFalse(reading.copy(focusDiopter = 0f).focusHeld(manual, caps))
        assertFalse(reading.copy(lensMoving = true).focusHeld(manual, caps))
        assertFalse(reading.focusHeld(manual.copy(locked = false), caps))
    }
}
