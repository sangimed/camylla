package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class FocusCoordinatesTest {
    private val image = FocusCoordinates.Rect(100.0, 200.0, 500.0, 400.0)
    private val crop = FocusCoordinates.Rect(0.25, 0.25, 0.75, 0.75)
    @Test fun letterboxAndNonfiniteTapsAreRejected() {
        assertNull(FocusCoordinates.sensorPoint(99.0, 300.0, image, crop, 0, false))
        assertNull(FocusCoordinates.sensorPoint(300.0, 401.0, image, crop, 0, false))
        assertNull(FocusCoordinates.sensorPoint(Double.NaN, 300.0, image, crop, 0, false))
    }
    @Test fun allRotationsAndMirrorsInvertToTheSameSensorTarget() {
        // Sensor target (0.35, 0.60) is crop-relative (0.2, 0.7).
        val displayed = mapOf(0 to Pair(0.2, 0.7), 90 to Pair(0.3, 0.2),
            180 to Pair(0.8, 0.3), 270 to Pair(0.7, 0.8))
        for ((rotation, point) in displayed) for (mirror in listOf(false, true)) {
            val u = if (mirror) 1 - point.first else point.first
            val actual = FocusCoordinates.sensorPoint(100 + u * 400, 200 + point.second * 200,
                image, crop, rotation, mirror)!!
            assertEquals(0.35, actual.x, 0.000001)
            assertEquals(0.60, actual.y, 0.000001)
        }
    }
}
