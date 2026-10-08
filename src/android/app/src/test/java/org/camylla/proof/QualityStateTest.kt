package org.camylla.proof

import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test

class QualityStateTest {
    @Test fun healthyCaptureReportsSelectedQuality() {
        val state = QualityState.current(StatsSnapshot(state = "Capturing", requestedMode = "1280x720@30",
            fps = 29.8, elapsedSeconds = 8, selectedBitrate = 3_000_000, bitrate = 3_000_000))
        assertEquals("1280x720@30", state.effectiveMode)
        assertFalse(state.networkPressure)
        assertFalse(state.thermalPressure)
        assertEquals("Selected quality is active.", state.reason)
    }

    @Test fun pressureHasOneCurrentReasonWithoutReadingHistoricalTotals() {
        val network = QualityState.current(StatsSnapshot(state = "Capturing", requestedMode = "1280x720@30",
            elapsedSeconds = 10, fps = 30.0, selectedBitrate = 3_000_000, bitrate = 2_250_000,
            network = NetworkProgress(state = "Recovering", networkErrors = 99)))
        assertTrue(network.networkPressure)
        assertTrue(network.reason.contains("Network recovery"))
        assertEquals(2_250_000, network.effectiveBitrate)
        val thermal = QualityState.current(StatsSnapshot(state = "Capturing", requestedMode = "1920x1080@30",
            elapsedSeconds = 10, fps = 20.0, selectedBitrate = 6_000_000, bitrate = 4_500_000,
            thermalStatus = PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(thermal.thermalPressure)
        assertTrue(thermal.reason.contains("bitrate is temporarily reduced"))
    }
}
