package org.camylla.proof

/** Present the current attempt; cumulative diagnostic counters never imply an active failure. */
internal object ConnectionStatus {
    fun message(capture: StatsSnapshot, desktop: String): String {
        if (capture.state == "Error") return "Capture error: ${capture.error.ifBlank { "capture stopped" }}. Check the message, then try Start again."
        if (capture.state == "Stopped") return "Paired with $desktop. Ready to start."
        if (capture.state == "Starting" || capture.state == "Binding") return "Starting camera…"
        val network = capture.network
        return when (network.state) {
            "Connecting" -> "Connecting to $desktop… Check that the PC app is open and both devices are on the same local network."
            "Authenticating" -> "Authenticating with $desktop…"
            "Negotiating" -> "Preparing the video connection…"
            "Streaming" -> "Streaming to $desktop"
            "Recovering" -> "Recovering connection to $desktop. ${guidance(network.error)} Retry now or wait for automatic retry."
            else -> "Waiting for $desktop…"
        }
    }

    private fun guidance(error: String): String {
        val cause = error.lowercase()
        return when {
            cause.contains("revoked") || cause.contains("untrusted") || cause.contains("certificate") ||
                cause.contains("identity_mismatch") || cause.contains("pair_rejected") ->
                "Trust was rejected; check the paired PC or stop and pair again."
            cause.contains("permission") || cause.contains("camera") ->
                "Check camera permission and whether another app is using the camera."
            cause.contains("refused") || cause.contains("connect") || cause.contains("route") ||
                cause.contains("timed out") || cause.contains("timeout") ->
                "Check the PC listener, firewall and local network."
            else -> "Check the local network and PC app."
        }
    }
}
