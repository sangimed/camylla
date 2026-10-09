package org.camylla.proof

import kotlin.math.abs

/** Camera2 producers orient/mirror SurfaceTexture buffers independently of Camylla's
 * preview/output transforms. Recover the raw sensor buffer's top-down coordinates,
 * retaining the producer's crop/filtering inset. Geometry and focus then share the
 * same unrotated stream dimensions, including on front cameras.
 */
internal object CameraTextureTransform {
    fun canonical(matrix: FloatArray): FloatArray {
        require(matrix.size == 16 && matrix.all { it.isFinite() })
        val epsilon = 0.00001f
        require((abs(matrix[0]) < epsilon && abs(matrix[5]) < epsilon) ||
            (abs(matrix[1]) < epsilon && abs(matrix[4]) < epsilon)) { "Unsupported camera texture transform" }
        val xs = listOf(matrix[12], matrix[12] + matrix[0], matrix[12] + matrix[4], matrix[12] + matrix[0] + matrix[4])
        val ys = listOf(matrix[13], matrix[13] + matrix[1], matrix[13] + matrix[5], matrix[13] + matrix[1] + matrix[5])
        val left = xs.min(); val right = xs.max(); val bottom = ys.min(); val top = ys.max()
        require(right > left && top > bottom) { "Empty camera texture crop" }
        return FloatArray(16).apply {
            this[0] = right - left; this[5] = bottom - top
            this[10] = 1f; this[12] = left; this[13] = top; this[15] = 1f
        }
    }
}
