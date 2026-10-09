package org.camylla.proof

/** Preview feedback is separate from command acknowledgement and never sent to the encoder. */
internal class FocusFeedback {
    enum class Phase { ACQUIRING, SUCCEEDED, FAILED, LOCKED }
    data class Target(val revision: Long, val x: Float, val y: Float, val width: Int, val height: Int,
        val phase: Phase, val changedAt: Long)
    private var target: Target? = null
    private var triggerFrame: Long? = null

    fun begin(revision: Long, x: Double, y: Double, width: Int, height: Int, now: Long) {
        target = Target(revision, x.toFloat(), y.toFloat(), width, height, Phase.ACQUIRING, now)
        triggerFrame = null
    }
    fun triggered(revision: Long, frame: Long) {
        if (target?.revision == revision) triggerFrame = frame
    }
    fun result(revision: Long, frame: Long, matchesTarget: Boolean, success: Boolean?, now: Long) {
        val current = target ?: return
        val first = triggerFrame ?: return
        if (current.phase != Phase.ACQUIRING || revision < current.revision || frame < first || !matchesTarget || success == null) return
        if (now - current.changedAt >= 5_000) { fail(now); return }
        target = current.copy(phase = if (success) Phase.SUCCEEDED else Phase.FAILED, changedAt = now)
    }
    fun fail(now: Long) {
        val current = target ?: return
        if (current.phase == Phase.ACQUIRING) target = current.copy(phase = Phase.FAILED, changedAt = now)
    }
    fun locked(now: Long) { target = target?.copy(phase = Phase.LOCKED, changedAt = now) }
    fun clear() { target = null; triggerFrame = null }
    fun visible(now: Long): Target? {
        val current = target ?: return null
        val lifetime = when (current.phase) {
            Phase.SUCCEEDED -> 1_000L
            Phase.FAILED -> 2_000L
            else -> Long.MAX_VALUE
        }
        return current.takeIf { now - it.changedAt < lifetime }
    }
}
