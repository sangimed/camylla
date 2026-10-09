package org.camylla.proof

/** Quick actions use confirmed values; a pending request never masquerades as applied. */
internal object CameraQuickControls {
    data class Presentation(val label: String, val enabled: Boolean, val reason: String? = null)

    // Camera2 continuous-video = 3, continuous-picture = 4. Never select manual
    // or a one-shot AF mode merely because it happens to be the first descriptor.
    fun autoMode(choices: Map<Int, String>): Int? = listOf(3, 4).firstOrNull { it in choices }

    fun present(key: String, status: CameraStatus): Presentation {
        val option = status.options.find { it.key == key }
        val name = when (key) {
            "focusMode" -> "Focus Auto"
            "focusLock" -> "Focus lock"
            "torch" -> "Torch"
            "zoom" -> "Digital zoom"
            else -> "Centre AF"
        }
        if (option == null) return Presentation("$name\nStart first", false)
        val reason = option.reason ?: when {
            key == "focusMode" && autoMode(option.choices) == null -> "Continuous autofocus unavailable on this lens"
            key == "zoom" && option.max <= option.min -> "No digital zoom range exposed by this lens"
            else -> null
        }
        if (reason != null) return Presentation("$name\nUnavailable", false, "$name: $reason")
        val value = status.applied[key]
        val applied = when (key) {
            "focusMode" -> if (value == 1.0) "Tap AF · Auto" else option.choices[value?.toInt()] ?: "Awaiting camera"
            "focusLock" -> when (value) { 1.0 -> "Locked · Unlock"; 0.0 -> "Off"; else -> "Awaiting camera" }
            "torch" -> when (value) { 1.0 -> "On"; 0.0 -> "Off"; else -> "Awaiting camera" }
            "zoom" -> value?.let { java.lang.String.format(java.util.Locale.US, "%.2f×", it) } ?: "Awaiting camera"
            else -> "Tap to focus"
        }
        val pending = key in status.pending || (key in setOf("focusMode", "focusLock") &&
            status.pending.any { it in setOf("focusMode", "focusLock", "focusDistance", "focusPoint") })
        return Presentation("$name\n$applied${if (pending) " · Applying" else ""}", !pending && (value != null || key == "focusPoint"))
    }
}
