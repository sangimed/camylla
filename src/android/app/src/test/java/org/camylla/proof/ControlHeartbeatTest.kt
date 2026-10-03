package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControlHeartbeatTest {
    @Test fun normalCadenceUsesMonotonicDeadlines() {
        var time = 0L
        val pings = mutableListOf<Long>()
        ControlHeartbeat(2_000, 7_000, { time }, { pings.size < 3 }, { time },
            { pings.add(time) }, { _, delay -> time += delay }).run()
        assertEquals(listOf(2_000L, 4_000L, 6_000L), pings)
    }

    @Test fun videoQueueNotificationsDoNotAdvanceHeartbeat() {
        var time = 0L
        val videoQueueMonitor = Object()
        val pings = mutableListOf<Long>()
        ControlHeartbeat(2_000, 7_000, { time }, { pings.isEmpty() }, { time },
            { pings.add(time) }, { _, delay ->
                if (time < 1_900) {
                    synchronized(videoQueueMonitor) { videoQueueMonitor.notifyAll() }
                    time += 100
                } else time += delay
            }).run()
        assertEquals(listOf(2_000L), pings)
    }

    @Test fun spuriousWakeDoesNotSendEarly() {
        var time = 0L
        var waits = 0
        val pings = mutableListOf<Long>()
        ControlHeartbeat(2_000, 7_000, { time }, { pings.isEmpty() }, { time },
            { pings.add(time) }, { _, delay ->
                waits++
                if (waits == 1) time += 200 else time += delay
            }).run()
        assertEquals(2, waits)
        assertEquals(listOf(2_000L), pings)
    }

    @Test fun cancellationWakesWaitPromptly() {
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val clock = { System.nanoTime() / 1_000_000 }
        val heartbeat = ControlHeartbeat(60_000, 70_000, clock,
            { true }, clock, { fail("Cancelled heartbeat sent a ping") },
            { monitor, delay -> entered.countDown(); monitor.wait(delay) })
        val thread = Thread { heartbeat.run(); done.countDown() }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            heartbeat.cancel()
            assertTrue(done.await(500, TimeUnit.MILLISECONDS))
        } finally {
            heartbeat.cancel()
            thread.join(1_000)
        }
    }

    @Test fun closedOrReplacedSessionStopsOldLoop() {
        for (invalidation in listOf("closed", "replaced")) {
            var time = 0L
            var current = true
            var open = true
            var pings = 0
            ControlHeartbeat(2_000, 7_000, { time }, { current && open }, { time },
                { pings++ }, { _, _ ->
                    if (invalidation == "closed") open = false else current = false
                    time = 2_000
                }).run()
            assertEquals(0, pings)
        }
    }

    @Test fun delayedExecutionSendsOnlyOnePing() {
        var time = 0L
        val pings = mutableListOf<Long>()
        ControlHeartbeat(2_000, 20_000, { time }, { pings.size < 2 }, { time },
            { pings.add(time) }, { _, delay ->
                time += if (pings.isEmpty()) 10_000 else delay
            }).run()
        assertEquals(listOf(10_000L, 12_000L), pings)
    }

    @Test fun missingPongFailsSessionForRecovery() {
        var time = 0L
        val recovery = RecoveryState()
        recovery.attempting()
        var pings = 0
        val heartbeat = ControlHeartbeat(2_000, 7_000, { time }, { true }, { 0L },
            { pings++ }, { _, delay -> time += delay })
        val error = assertThrows(IOException::class.java) { heartbeat.run() }
        assertEquals("Control heartbeat timed out", error.message)
        assertEquals(3, pings)
        assertEquals(250L, recovery.failed())
        recovery.attempting()
        assertEquals(2L, recovery.connectionAttempts)
    }
}
