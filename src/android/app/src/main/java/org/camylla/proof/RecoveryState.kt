package org.camylla.proof

/** Retry state belongs to an explicit capture, and has no lifetime failure limit. */
internal class RecoveryState {
    var consecutiveFailures = 0
        private set
    var connectionAttempts = 0L
        private set
    var successfulRecoveries = 0L
        private set
    private var hasStreamed = false

    fun attempting() { connectionAttempts++ }

    fun streaming() {
        if (hasStreamed && consecutiveFailures > 0) successfulRecoveries++
        hasStreamed = true
        consecutiveFailures = 0
    }

    fun failed(): Long {
        if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++
        val shift = (consecutiveFailures - 1).coerceAtMost(4)
        return (250L shl shift).coerceAtMost(4_000L)
    }
}

/** A new transport session must begin with codec configuration and a keyframe. */
internal class SyncGate {
    var waitingForKey = true
        private set

    fun reset() { waitingForKey = true }

    fun accept(key: Boolean, hasConfig: Boolean): Boolean {
        if (!waitingForKey) return true
        if (!key || !hasConfig) return false
        waitingForKey = false
        return true
    }
}

/** Encoded output is useful only after a video channel has completed binding. */
internal class VideoTransportGate {
    var isReady = false
        private set

    fun ready(requestSync: () -> Unit) {
        if (isReady) return
        isReady = true
        requestSync()
    }
    fun unavailable() { isReady = false }

    fun <T> enqueue(queue: RecoveryQueue<T>, unit: T): Boolean {
        if (!isReady) return false
        queue.add(unit)
        return true
    }
}

/** Caller synchronizes access; clear releases every queued payload immediately. */
internal class RecoveryQueue<T>(private val sizeOf: (T) -> Int, private val isConfig: (T) -> Boolean) {
    private val items = java.util.ArrayDeque<T>()
    var bytes = 0
        private set
    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val first: T get() = items.first

    fun add(unit: T) { items.addLast(unit); bytes += sizeOf(unit) }
    fun remove(): T = items.removeFirst().also { bytes -= sizeOf(it) }
    fun clear(): Int {
        val dropped = items.count { !isConfig(it) }
        items.clear()
        bytes = 0
        return dropped
    }
}

internal class RetryWaiter {
    private val monitor = Object()
    private var cancelled = false
    private var retryRequested = false
    fun waitFor(delayMs: Long) = synchronized(monitor) {
        if (!cancelled && !retryRequested) monitor.wait(delayMs)
        retryRequested = false
    }
    fun retryNow() = synchronized(monitor) {
        retryRequested = true
        monitor.notifyAll()
    }
    fun cancel() = synchronized(monitor) {
        cancelled = true
        monitor.notifyAll()
    }
}
