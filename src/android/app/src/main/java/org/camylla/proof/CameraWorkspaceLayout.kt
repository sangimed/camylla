package org.camylla.proof

/** Layout policy based on the space the camera workspace can actually use. */
internal object CameraWorkspaceLayout {
    enum class Mode { COMPACT, STACKED, SPLIT }

    fun choose(widthDp: Int, heightDp: Int, fontScale: Float): Mode = when {
        widthDp >= 840 && heightDp >= 320 && fontScale < 1.25f -> Mode.SPLIT
        widthDp < 520 || heightDp < 460 || fontScale >= 1.35f -> Mode.COMPACT
        widthDp >= 560 && heightDp >= 520 -> Mode.STACKED
        else -> Mode.COMPACT
    }
}
