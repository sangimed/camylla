package org.camylla.proof

internal object CaptureModeCapability {
    fun supported(sizeSupported: Boolean, ranges: List<Pair<Int, Int>>, minFrameDurationNs: Long, fps: Int): Boolean =
        sizeSupported && ranges.any { (lower, upper) -> lower <= fps && upper >= fps } &&
            minFrameDurationNs <= 1_000_000_000L / fps + 1
}

/** One-way downgrade per selected 60-FPS capture generation. */
internal class CaptureFallbackPolicy {
    private var used = false
    fun shouldFallback(elapsedMs: Long, measuredFps: Double, requestedFps: Int): Boolean {
        if (used || requestedFps != 60 || elapsedMs < 5_000 || measuredFps <= 0.0 || measuredFps >= requestedFps * 0.85) return false
        return claimFallback()
    }
    fun claimFallback(): Boolean { if (used) return false; used = true; return true }
}
