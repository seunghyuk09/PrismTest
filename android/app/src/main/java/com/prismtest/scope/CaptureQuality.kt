package com.prismtest.scope

data class FrameSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** Requests, not promises: CameraX negotiates supported sizes and the shared viewport crops them. */
enum class CaptureQuality(val key: String, val label: String, val target: FrameSize) {
    STANDARD("standard", "표준", FrameSize(1280, 960)),
    DETAIL("detail", "정밀", FrameSize(2560, 1920));

    fun bounded(supported: List<FrameSize>, preview: Boolean = false): List<FrameSize> {
        val maximum = if (preview || this == STANDARD) 1920L * 1440 else target.pixels
        val valid = supported.filter { it.width > 0 && it.height > 0 }
        val bounded = valid.filter { it.pixels <= maximum }
        // Preserve CameraX's aspect-ratio/order preference. Never invent an unsupported size.
        return bounded.ifEmpty { valid.minByOrNull { it.pixels }?.let(::listOf) ?: emptyList() }
    }

    companion object {
        fun of(key: String?): CaptureQuality = values().firstOrNull { it.key == key } ?: STANDARD
    }
}
