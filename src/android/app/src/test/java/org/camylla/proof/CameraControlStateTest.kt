package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class CameraControlStateTest {
    private val binding = CameraControlState.Binding("phone", "session", 1)
    private val capabilities = mapOf("zoom" to CameraControlState.Capability(1.0, 4.0, null),
        "focus" to CameraControlState.Capability(null, null, "Fixed-focus lens"))
    private fun command(id: Long, value: Double = 2.0) = CameraControlState.Command(id, binding, "zoom", value)
    private fun gate() = CameraControlState().also { it.begin(binding, capabilities) }

    @Test fun receiptIsNotAppliedAndCameraMayReject() {
        val gate = gate()
        assertTrue(gate.submit(command(1), 0).isEmpty())
        assertTrue(gate.appliedValues().isEmpty())
        assertEquals("Camera failure", gate.complete(command(1), null, "Camera failure", 10)?.error)
        assertTrue(gate.appliedValues().isEmpty())
        gate.submit(command(2), 20)
        assertEquals(1.9, gate.complete(command(2), 1.9, null, 30)!!.applied!!, 0.0)
        assertEquals(1.9, gate.appliedValues().getValue("zoom"), 0.0)
    }
    @Test fun newerSliderRequestCancelsOlderAndRejectsLateCompletion() {
        val gate = gate()
        gate.submit(command(1), 0)
        assertEquals("Superseded", gate.submit(command(2, 3.0), 1).single().error)
        assertNull(gate.complete(command(1), 2.0, null, 2))
        assertEquals("Duplicate or out-of-order request", gate.submit(command(1), 3).single().error)
    }
    @Test fun unavailableAndInvalidValuesNeverBecomePending() {
        val gate = gate()
        assertEquals("Fixed-focus lens", gate.submit(CameraControlState.Command(1, binding, "focus", 0.0), 0).single().error)
        for ((index, value) in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.9, 4.1).withIndex()) {
            assertEquals("Value outside supported range", gate.submit(command(index + 2L, value), 0).single().error)
        }
        assertTrue(gate.expire(10_000).isEmpty())
    }
    @Test fun generationAndStopCancelPendingAndInvalidateOldResults() {
        val gate = gate()
        gate.submit(command(1), 0)
        val next = binding.copy(generation = 2)
        assertEquals("Camera changed", gate.reconfigure(next, capabilities).single().error)
        assertNull(gate.complete(command(1), 2.0, null, 1))
        assertNotNull(gate.submit(command(2), 2).single().error)
        val newCommand = command(3).copy(binding = next)
        gate.submit(newCommand, 3)
        assertEquals("Capture stopped", gate.stop().single().error)
        assertNull(gate.complete(newCommand, 2.0, null, 4))
        assertNotNull(gate.submit(newCommand.copy(id = 4), 5).single().error)
        assertTrue(gate.appliedValues().isEmpty())
    }
    @Test fun wrongPeerSessionAndExpiredResultsAreRejected() {
        val gate = gate()
        assertNotNull(gate.submit(command(1).copy(binding = binding.copy(peer = "other")), 0).single().error)
        assertNotNull(gate.submit(command(1).copy(binding = binding.copy(session = "other")), 0).single().error)
        gate.submit(command(1), 0)
        assertEquals("Timed out", gate.complete(command(1), 2.0, null, 3_000)?.error)
        gate.submit(command(2), 5_000)
        assertTrue(gate.expire(7_999).isEmpty())
        assertEquals("Timed out", gate.expire(8_000).single().error)
        assertNull(gate.complete(command(2), 2.0, null, 8_001))
    }
    @Test fun cancellationReleasesPendingSlotAndIgnoresLateCameraCompletion() {
        val gate = gate()
        gate.submit(command(1), 0)
        assertEquals("Cancelled", gate.cancel(command(1), "Cancelled")?.error)
        assertNull(gate.complete(command(1), 2.0, null, 1))
        assertTrue(gate.expire(4_000).isEmpty())
        assertTrue(gate.submit(command(2), 4_001).isEmpty())
        assertEquals(3.0, gate.complete(command(2), 3.0, null, 4_002)!!.applied!!, 0.0)
    }
}
