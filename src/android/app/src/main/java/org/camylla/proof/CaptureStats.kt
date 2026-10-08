package org.camylla.proof

import java.util.ArrayDeque

data class StatsSnapshot(
    val state: String = "Stopped",
    val requestedMode: String = "",
    val camera: String = "",
    val resolution: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val selectedBitrate: Int = 0,
    val frames: Long = 0,
    val keyframes: Long = 0,
    val fps: Double = 0.0,
    val timestampUs: Long = -1,
    val timestampGaps: Long = 0,
    val elapsedSeconds: Long = 0,
    val rotationDegrees: Int = 0,
    val zoomRatio: Float = 1f,
    val autofocus: String = "Unknown",
    val thermalStatus: Int = -1,
    val sinkBytes: Long = 0,
    val sinkLimitReached: Boolean = false,
    val outputFile: String = "",
    val error: String = "",
    val network: NetworkProgress = NetworkProgress(),
)

data class QualityState(val selectedMode: String, val effectiveMode: String, val selectedBitrate: Int, val effectiveBitrate: Int, val deliveredFps: Double,
    val networkPressure: Boolean, val thermalPressure: Boolean, val reason: String) {
    companion object {
        fun current(snapshot: StatsSnapshot): QualityState {
            val network = snapshot.network.state == "Recovering"
            val thermal = snapshot.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
            val slow = snapshot.elapsedSeconds >= 5 && snapshot.fps in 0.1..<27.0
            val reduced = snapshot.selectedBitrate > 0 && snapshot.bitrate in 1..<snapshot.selectedBitrate
            val reason = when {
                thermal && reduced -> "Thermal pressure; bitrate is temporarily reduced."
                thermal -> "Thermal pressure; automatic quality adaptation is unavailable."
                network && reduced -> "Network recovery in progress; bitrate is temporarily reduced."
                network -> "Network recovery in progress; waiting for a fresh video connection."
                slow -> "Delivered frame rate is below the 30 FPS target; automatic quality adaptation is unavailable."
                else -> "Selected quality is active."
            }
            return QualityState(snapshot.requestedMode, snapshot.requestedMode, snapshot.selectedBitrate, snapshot.bitrate, snapshot.fps,
                network, thermal, reason)
        }
    }
}

/** Counts encoded access units, not requested camera frames. The gap count is diagnostic, not proof of a dropped frame. */
class FrameCounter(private val windowUs: Long = 5_000_000L) {
    private val recent = ArrayDeque<Long>()
    var frames = 0L
        private set
    var keyframes = 0L
        private set
    var timestampGaps = 0L
        private set
    var lastTimestampUs = -1L
        private set

    fun add(timestampUs: Long, keyframe: Boolean) {
        if (lastTimestampUs >= 0 && timestampUs - lastTimestampUs > 50_000) timestampGaps++
        lastTimestampUs = timestampUs
        frames++
        if (keyframe) keyframes++
        recent.addLast(timestampUs)
        while (recent.isNotEmpty() && (timestampUs - recent.first() > windowUs || recent.size > 600)) recent.removeFirst()
    }

    fun fps(): Double = if (recent.size < 2) 0.0 else {
        val span = recent.last() - recent.first()
        if (span <= 0) 0.0 else (recent.size - 1) * 1_000_000.0 / span
    }
}
