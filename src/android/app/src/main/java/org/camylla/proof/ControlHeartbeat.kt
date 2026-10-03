package org.camylla.proof

import java.io.IOException

/** One cancellable, monotonic heartbeat schedule for one authenticated control session. */
internal class ControlHeartbeat(
    private val intervalMs: Long,
    private val timeoutMs: Long,
    private val nowMs: () -> Long,
    private val isActive: () -> Boolean,
    private val lastPongMs: () -> Long,
    private val sendPing: () -> Unit,
    private val waitOnMonitor: (Object, Long) -> Unit = { monitor, delay -> monitor.wait(delay) },
) {
    private val monitor = Object()
    private var cancelled = false

    fun run() {
        var deadline = nowMs() + intervalMs
        while (isActive()) {
            synchronized(monitor) {
                while (!cancelled && isActive()) {
                    val remaining = deadline - nowMs()
                    if (remaining <= 0) break
                    waitOnMonitor(monitor, remaining)
                }
                if (cancelled || !isActive()) return
            }
            if (!isActive()) return
            if (nowMs() - lastPongMs() > timeoutMs) throw IOException("Control heartbeat timed out")
            if (!isActive()) return
            sendPing()
            // A delayed iteration sends only one ping; it never catches up in a burst.
            deadline = nowMs() + intervalMs
        }
    }

    fun cancel() = synchronized(monitor) {
        cancelled = true
        monitor.notifyAll()
    }
}
