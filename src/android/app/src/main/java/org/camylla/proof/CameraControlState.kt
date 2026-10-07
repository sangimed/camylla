package org.camylla.proof

/** Camera-owned command admission. All callers (local UI and authenticated peer) use this gate.
 * This does not grant capture authorization or claim that the camera applied a request.
 */
internal class CameraControlState {
    data class Binding(val peer: String, val session: String, val generation: Long) {
        init { require(peer.isNotBlank() && session.isNotBlank() && generation > 0) }
    }
    data class Capability(val minimum: Double?, val maximum: Double?, val unavailableReason: String?) {
        init {
            require((unavailableReason != null && unavailableReason.isNotBlank() && minimum == null && maximum == null) ||
                (unavailableReason == null && minimum != null && maximum != null &&
                    minimum.isFinite() && maximum.isFinite() && minimum <= maximum))
        }
    }
    data class Command(val id: Long, val binding: Binding, val control: String, val value: Double)
    data class Result(val command: Command, val applied: Double?, val error: String?)
    private data class Pending(val command: Command, val deadline: Long)
    private var binding: Binding? = null
    private var capabilities: Map<String, Capability> = emptyMap()
    private val pending = mutableMapOf<String, Pending>()
    private val applied = mutableMapOf<String, Double>()
    private var lastId = 0L

    @Synchronized fun begin(value: Binding, supported: Map<String, Capability>): List<Result> {
        require(binding == null) { "Stop the previous session first" }
        binding = value
        capabilities = supported.toMap()
        lastId = 0
        applied.clear()
        return emptyList()
    }

    /** A new camera generation invalidates all in-flight requests and previous applied values. */
    @Synchronized fun reconfigure(value: Binding, supported: Map<String, Capability>): List<Result> {
        val current = checkNotNull(binding)
        require(value.peer == current.peer && value.session == current.session && value.generation > current.generation)
        val cancelled = cancel("Camera changed")
        binding = value
        capabilities = supported.toMap()
        applied.clear()
        return cancelled
    }

    /** At most one outstanding request per control; newer slider values supersede older ones.
     * IDs increase globally in the session, across local and remote callers.
     */
    @Synchronized fun submit(command: Command, nowMs: Long): List<Result> {
        fun reject(reason: String) = listOf(Result(command, null, reason))
        if (command.binding != binding) return reject("Inactive session or obsolete camera generation")
        if (command.id <= lastId || command.id <= 0) return reject("Duplicate or out-of-order request")
        lastId = command.id
        val capability = capabilities[command.control] ?: return reject("Unknown control")
        capability.unavailableReason?.let { return reject(it) }
        if (!command.value.isFinite() || command.value < capability.minimum!! || command.value > capability.maximum!!)
            return reject("Value outside supported range")
        require(nowMs >= 0 && nowMs <= Long.MAX_VALUE - TIMEOUT_MS)
        if (!pending.containsKey(command.control) && pending.size >= MAX_PENDING) return reject("Too many pending commands")
        val previous = pending.put(command.control, Pending(command, nowMs + TIMEOUT_MS))
        return previous?.let { listOf(Result(it.command, null, "Superseded")) } ?: emptyList()
    }

    /** Call only after a camera result/future confirms application, using its actual value.
     * AF acquisition/completion needs a separate event; accepting a focus target is not AF success.
     */
    @Synchronized fun complete(command: Command, actual: Double?, error: String?, nowMs: Long): Result? {
        val request = pending[command.control] ?: return null
        if (request.command != command || binding != command.binding) return null
        require((actual == null) != (error == null))
        pending.remove(command.control)
        if (nowMs >= request.deadline) return Result(command, null, "Timed out")
        if (error != null) return Result(command, null, error)
        val capability = capabilities.getValue(command.control)
        if (!actual!!.isFinite() || actual < capability.minimum!! || actual > capability.maximum!!)
            return Result(command, null, "Camera returned invalid applied value")
        applied[command.control] = actual
        return Result(command, actual, null)
    }

    @Synchronized fun expire(nowMs: Long): List<Result> {
        val expired = pending.values.filter { nowMs >= it.deadline }
        expired.forEach { pending.remove(it.command.control) }
        return expired.map { Result(it.command, null, "Timed out") }
    }
    @Synchronized fun cancel(command: Command, reason: String): Result? {
        if (pending[command.control]?.command != command || binding != command.binding) return null
        pending.remove(command.control)
        return Result(command,null,reason)
    }

    @Synchronized fun stop(): List<Result> {
        val cancelled = cancel("Capture stopped")
        binding = null
        capabilities = emptyMap()
        applied.clear()
        return cancelled
    }
    @Synchronized fun appliedValues(): Map<String, Double> = applied.toMap()
    private fun cancel(reason: String): List<Result> = pending.values.map {
        Result(it.command, null, reason)
    }.also { pending.clear() }
    companion object { const val TIMEOUT_MS = 3_000L; const val MAX_PENDING = 32 }
}
