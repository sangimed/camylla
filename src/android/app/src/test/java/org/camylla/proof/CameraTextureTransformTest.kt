package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class CameraTextureTransformTest {
    @Test fun reproducesTheOldCircleStretchAndRemovesItBeforeFittingSixteenByNine() {
        val rotated = FloatArray(16).apply {
            this[1] = 1f; this[4] = 1f; this[10] = 1f; this[15] = 1f
        }
        fun circleAxisRatio(matrix: FloatArray): Double {
            // Source pixels per destination pixel along each screen axis. A
            // circle stays round only if both axes have the same metric.
            val x = kotlin.math.hypot(matrix[0].toDouble(), 720.0 * matrix[1] / 1280)
            val y = kotlin.math.hypot(1280.0 * matrix[4] / 720, matrix[5].toDouble())
            return y / x
        }
        assertEquals(256.0 / 81, circleAxisRatio(rotated), 0.00001)
        assertEquals(1.0, circleAxisRatio(CameraTextureTransform.canonical(rotated)), 0.00001)
    }

    private fun producer(rotation: Int, mirror: Boolean): FloatArray {
        fun point(x: Float, y: Float): Pair<Float, Float> {
            val u = if (mirror) 1f - x else x
            val p = when (rotation) {
                90 -> 1f - y to u
                180 -> 1f - u to 1f - y
                270 -> y to 1f - u
                else -> u to y
            }
            // Asymmetric crop: normalization must keep translation and scales.
            return 0.1f + 0.8f * p.first to 0.9f - 0.6f * p.second
        }
        val origin = point(0f, 0f); val x = point(1f, 0f); val y = point(0f, 1f)
        return FloatArray(16).apply {
            this[0] = x.first - origin.first; this[1] = x.second - origin.second
            this[4] = y.first - origin.first; this[5] = y.second - origin.second
            this[10] = 1f; this[12] = origin.first; this[13] = origin.second; this[15] = 1f
        }
    }

    @Test fun removesEveryProducerQuarterTurnAndMirrorButRetainsCrop() {
        val expected = producer(0, false)
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true))
            assertArrayEquals("rotation=$rotation mirror=$mirror", expected,
                CameraTextureTransform.canonical(producer(rotation, mirror)), 0.00001f)
    }

    @Test fun normalizationIsIdempotentAndDoesNotUndoTheGlYFlip() {
        val matrix = producer(90, true)
        val canonical = CameraTextureTransform.canonical(matrix)
        assertTrue(canonical[0] > 0 && canonical[5] < 0)
        assertArrayEquals(canonical, CameraTextureTransform.canonical(canonical), 0.00001f)
    }

    @Test fun invalidOrNonQuarterTurnProducerMatricesAreRejected() {
        for (matrix in listOf(FloatArray(16), producer(0, false).apply { this[0] = Float.NaN },
            producer(0, false).apply { this[4] = 0.2f })) {
            try { CameraTextureTransform.canonical(matrix); fail("Invalid transform accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
