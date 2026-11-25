package com.prismtest.scope

data class FrameSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** Requests, not promises: CameraX negotiates supported sizes and the shared viewport crops them. */
enum class CaptureQuality(val key: String, val label: String, val target: FrameSize) {
    HD("hd", "HD", FrameSize(1280, 720)),
    FHD("fhd", "FHD", FrameSize(1920, 1080)),
    QHD("qhd", "QHD", FrameSize(2560, 1440)),
    UHD("4k", "4K", FrameSize(3840, 2160));

    fun bounded(supported: List<FrameSize>): List<FrameSize> {
        val maximum = target.pixels
        val valid = supported.filter { it.width > 0 && it.height > 0 && it.pixels <= UHD.target.pixels }
        val bounded = valid.filter { it.pixels <= maximum }
        // Preserve CameraX's aspect-ratio/order preference. Never invent an unsupported size.
        return bounded.ifEmpty { valid.minByOrNull { it.pixels }?.let(::listOf) ?: emptyList() }
    }

    fun lower(): CaptureQuality? = values().getOrNull(ordinal - 1)

    companion object {
        fun of(key: String?): CaptureQuality = when (key) {
            "standard" -> HD
            "detail" -> UHD
            else -> values().firstOrNull { it.key == key } ?: FHD
        }
    }
}
