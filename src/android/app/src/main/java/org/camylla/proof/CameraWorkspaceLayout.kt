package org.camylla.proof

/** Layout policy based on the space the camera workspace can actually use. */
internal object CameraWorkspaceLayout {
    enum class Mode { COMPACT, STACKED, SPLIT }

    fun choose(widthDp: Int, heightDp: Int, fontScale: Float): Mode = when {
        fontScale >= 1.25f -> Mode.COMPACT
        widthDp >= 700 && widthDp > heightDp && heightDp >= 420 && fontScale < 1.25f -> Mode.SPLIT
        heightDp < 420 -> Mode.COMPACT
        widthDp < 480 -> Mode.COMPACT
        widthDp < 520 -> Mode.STACKED
        widthDp >= 560 && heightDp >= 520 -> Mode.STACKED
        else -> Mode.COMPACT
    }
}
