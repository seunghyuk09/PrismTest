package com.prismtest.scope

enum class FocusControl(val key: String) {
    CURRENT_LOCK("current-af-lock"), AUTO_LOCK("af-lock"), MANUAL("manual")
}

/** Snapshot only a confirmed lens hold. Unknown focus distance never becomes a lens request. */
object FocusLockPolicy {
    /** Zero explicitly means a fixed lens. A missing characteristic does not mean fixed. */
    fun supportsAutoFocus(autoModeAvailable: Boolean, minimumDistance: Float?): Boolean =
        autoModeAvailable && minimumDistance != 0f

    fun snapshot(base: ManualSettings, reading: AppliedCamera, caps: CameraCaps,
                 lockId: String, control: FocusControl = FocusControl.AUTO_LOCK): ManualSettings? {
        val locked = base.copy(locked = true, focusControl = control)
        if (!reading.focusHeld(locked, caps)) return null
        val iso = reading.iso?.takeIf { it > 0 } ?: return null
        val exposure = reading.exposureNs?.takeIf { it > 0 } ?: return null
        val distance = reading.focusDiopter?.takeIf { it.isFinite() && it >= 0f }
        return locked.copy(iso = iso, exposureNs = exposure, focusDiopter = distance,
            // If the device cannot report lens distance, do not reuse criteria from another lock.
            focusLockId = if (distance == null && caps.supportsAutoFocus) lockId else "")
    }
}
