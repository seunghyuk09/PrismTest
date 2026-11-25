package com.prismtest.scope

import java.util.Locale

/** Bind a learned threshold to repeatable camera controls and pixel sampling, not just a phone name. */
object CalibrationProfile {
    fun key(device: String, camera: String, width: Int, height: Int,
            iso: Int, exposure: Long, focus: Float, zoom: Float, whiteBalance: String,
            roiSize: Float = 0.65f, autoTrack: Boolean = false): String =
        listOf("profile-v2", device, camera, "${width}x$height", iso.toString(), exposure.toString(),
            String.format(Locale.US, "%.3f", focus), String.format(Locale.US, "%.3f", zoom), whiteBalance,
            "roi=" + String.format(Locale.US, "%.3f", roiSize), "track=$autoTrack")
            .joinToString("|")
}
