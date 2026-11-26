package com.prismtest.scope

enum class FocusControl(val key: String) { AUTO_LOCK("af-lock"), MANUAL("manual") }

/** Snapshot only a confirmed lens hold. Unknown focus distance never becomes a lens request. */
object FocusLockPolicy {
    fun snapshot(base: ManualSettings, reading: AppliedCamera, caps: CameraCaps,
                 lockId: String): ManualSettings? {
        val locked = base.copy(locked = true, focusControl = FocusControl.AUTO_LOCK)
        if (!reading.focusHeld(locked, caps)) return null
        val iso = reading.iso?.takeIf { it > 0 } ?: return null
        val exposure = reading.exposureNs?.takeIf { it > 0 } ?: return null
        val distance = reading.focusDiopter?.takeIf { it.isFinite() && it >= 0f }
        return locked.copy(iso = iso, exposureNs = exposure, focusDiopter = distance,
            // If the device cannot report lens distance, do not reuse criteria from another lock.
            focusLockId = if (distance == null && caps.supportsAutoFocus) lockId else "")
    }
}
