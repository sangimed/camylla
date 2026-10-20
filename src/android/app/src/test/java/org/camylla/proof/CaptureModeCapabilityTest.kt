package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class CaptureModeCapabilityTest {
    @Test fun sixtyModeRequiresExactSizeRateAndFrameDuration() {
        assertTrue(CaptureModeCapability.supported(true, listOf(30 to 60), 16_666_667, 60))
        assertFalse(CaptureModeCapability.supported(true, listOf(30 to 30), 16_666_667, 60))
        assertFalse(CaptureModeCapability.supported(false, listOf(30 to 60), 16_666_667, 60))
        assertFalse(CaptureModeCapability.supported(true, listOf(30 to 60), 33_333_334, 60))
    }

    @Test fun underperformingSixtyModeFallsBackOnlyOnceAfterStabilityWindow() {
        val policy = CaptureFallbackPolicy()
        assertFalse(policy.shouldFallback(4_999, 20.0, 60))
        assertFalse(policy.shouldFallback(5_000, 54.0, 60))
        assertTrue(policy.shouldFallback(5_000, 49.0, 60))
        assertFalse(policy.shouldFallback(10_000, 10.0, 60))
        assertFalse(CaptureFallbackPolicy().shouldFallback(5_000, 24.0, 30))
    }

    @Test fun encodedCadenceCountsOnlyDistinctIncreasingPresentationTimestamps() {
        val counter = FrameCounter()
        repeat(60) { counter.add(it * 16_667L, false) }
        repeat(60) { counter.add((it - 1).coerceAtLeast(0) * 16_667L, false) }
        assertEquals(60, counter.frames)
        assertEquals(59.998, counter.fps(), 0.02)
        assertEquals(60, counter.duplicateTimestamps)
    }
}
