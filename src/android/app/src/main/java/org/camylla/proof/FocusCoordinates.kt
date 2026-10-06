package org.camylla.proof

/** Inverse of the displayed image transform. Rectangles use the same coordinate space as taps.
 * crop is the camera's actual normalized sensor crop, including digital zoom. It must come
 * from the preview/camera transform, not an assumed phone aspect ratio.
 */
internal object FocusCoordinates {
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        init { require(listOf(left, top, right, bottom).all { it.isFinite() } && left < right && top < bottom) }
    }
    data class Point(val x: Double, val y: Double)
    fun sensorPoint(x: Double, y: Double, image: Rect, crop: Rect, rotation: Int, mirrored: Boolean): Point? {
        require(rotation in setOf(0, 90, 180, 270))
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= 1 && crop.bottom <= 1)
        if (!x.isFinite() || !y.isFinite() || x < image.left || x > image.right || y < image.top || y > image.bottom) return null
        var u = (x - image.left) / (image.right - image.left)
        val v = (y - image.top) / (image.bottom - image.top)
        if (mirrored) u = 1 - u
        val p = when (rotation) {
            90 -> Point(v, 1 - u)
            180 -> Point(1 - u, 1 - v)
            270 -> Point(1 - v, u)
            else -> Point(u, v)
        }
        return Point(crop.left + p.x * (crop.right - crop.left), crop.top + p.y * (crop.bottom - crop.top))
    }
}
