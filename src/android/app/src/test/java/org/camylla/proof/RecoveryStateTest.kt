package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecoveryStateTest {
    @Test fun streamingClearsCurrentErrorButRetainsHistory() {
        val previous = NetworkProgress(state = "Recovering", error = "Control read: Truncated control message",
            networkErrors = 17, resets = 3, consecutiveFailures = 2)
        val current = previous.streamed(2)
        assertEquals("Streaming", current.state)
        assertEquals("", current.error)
        assertEquals(17, current.networkErrors)
        assertEquals(3, current.resets)
        assertEquals(0, current.consecutiveFailures)
        assertEquals(2, current.successfulRecoveries)
    }

    @Test fun unavailableVideoTransportDropsWithoutQueueOrRepeatedSyncRequests() {
        val transport = VideoTransportGate()
        val queue = RecoveryQueue<ByteArray>({ it.size }, { false })
        val sync = SyncGate()
        var syncRequests = 0
        repeat(313) {
            assertFalse(transport.enqueue(queue, byteArrayOf(1)))
        }
        assertTrue(queue.isEmpty)
        assertEquals(0, syncRequests)
        transport.ready { syncRequests++ }
        transport.ready { syncRequests++ }
        sync.reset()
        assertFalse(sync.accept(false, true))
        assertTrue(sync.accept(true, true))
        assertTrue(transport.enqueue(queue, byteArrayOf(1)))
        assertEquals(1, queue.size)
        transport.unavailable()
        queue.clear()
        repeat(313) {
            assertFalse(transport.enqueue(queue, byteArrayOf(1)))
        }
        assertTrue(queue.isEmpty)
        assertEquals(1, syncRequests)
        transport.ready { syncRequests++ }
        assertEquals(2, syncRequests)
    }

    @Test fun temporaryFailuresEventuallyStreamAndResetBackoff() {
        val recovery = RecoveryState()
        repeat(5) { recovery.attempting(); recovery.failed() }
        assertEquals(5, recovery.consecutiveFailures)
        assertEquals(4_000L, recovery.failed())
        recovery.attempting()
        recovery.streaming()
        assertEquals(0, recovery.consecutiveFailures)
        assertEquals(250L, recovery.failed())
        recovery.attempting()
        recovery.streaming()
        assertEquals(1L, recovery.successfulRecoveries)
    }

    @Test fun moreThanTwentyLifetimeFailuresDoNotEndCapture() {
        val recovery = RecoveryState()
        repeat(4) {
            repeat(7) { recovery.attempting(); assertTrue(recovery.failed() in 250L..4_000L) }
            recovery.attempting()
            recovery.streaming()
        }
        assertEquals(32L, recovery.connectionAttempts)
        assertEquals(3L, recovery.successfulRecoveries)
        assertEquals(0, recovery.consecutiveFailures)
    }

    @Test fun stopWakesRecoveryPromptly() {
        val waiter = RetryWaiter()
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread { entered.countDown(); waiter.waitFor(4_000); done.countDown() }.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        waiter.cancel()
        assertTrue(done.await(500, TimeUnit.MILLISECONDS))
    }

    @Test fun resetDropsStalePayloadsAndRequiresFreshConfigAndKey() {
        val queue = RecoveryQueue<ByteArray>({ it.size }, { it[0] == 2.toByte() })
        val gate = SyncGate()
        assertFalse(gate.accept(false, true))
        assertFalse(gate.accept(true, false))
        assertTrue(gate.accept(true, true))
        queue.add(byteArrayOf(2, 1))
        queue.add(byteArrayOf(1, 2, 3))
        queue.add(byteArrayOf(4, 5))
        assertEquals(7, queue.bytes)
        assertEquals(2, queue.clear())
        gate.reset()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.bytes)
        assertFalse(gate.accept(false, true))
        assertFalse(gate.accept(true, false))
        assertTrue(gate.accept(true, true))
    }
}
