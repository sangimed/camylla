package org.camylla.proof

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCounterTest {
    @Test fun countsMeasuredCadenceAndTimestampGaps() {
        val counter = FrameCounter()
        repeat(61) { counter.add(it * 33_333L, it % 30 == 0) }
        assertEquals(61, counter.frames)
        assertEquals(3, counter.keyframes)
        assertEquals(0, counter.timestampGaps)
        assertEquals(30.0, counter.fps(), 0.01)
        counter.add(2_200_000, false)
        assertEquals(1, counter.timestampGaps)
    }
}
