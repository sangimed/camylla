package org.camylla.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Draws only a UI overlay. No sensor or capture session is opened. */
@RunWith(AndroidJUnit4::class)
class FocusTargetViewTest {
    @Test fun markerStaysAtTouchedPositionAndDistinguishesAllResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = FocusTargetView(instrumentation.targetContext)
            view.layout(0, 0, 800, 1000)
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val density = view.resources.displayMetrics.density
            val descriptions = listOf("Focusing", "Focus acquired", "Could not acquire focus", "Focus locked")
            for ((index, phase) in FocusFeedback.Phase.entries.withIndex()) {
                bitmap.eraseColor(0)
                view.update(FocusFeedback.Target(1, 270f, 390f, 800, 1000, phase, SystemClock.elapsedRealtime()))
                view.draw(canvas)
                assertEquals(descriptions[index], view.contentDescription)
                var left = 800; var right = -1; var top = 1000; var bottom = -1
                for (y in 0 until 1000) for (x in 0 until 800) if (bitmap.getPixel(x, y) ushr 24 != 0) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
                assertTrue("No marker for $phase", right >= left)
                assertEquals(270f, (left + right + 1) / 2f, 1f)
                assertEquals(390f, (top + bottom + 1) / 2f, 1f)
                assertTrue("Marker too large", right - left < 60 * density)
                // Success/failure have different shapes at the centre, as well as different colors.
                if (phase == FocusFeedback.Phase.SUCCEEDED || phase == FocusFeedback.Phase.FAILED) {
                    val radius = (10*density).toInt()
                    assertTrue("Missing non-color result shape", (390-radius..390+radius).any { y ->
                        (270-radius..270+radius).any { x -> bitmap.getPixel(x, y) ushr 24 != 0 }
                    })
                }
            }
            view.update(null); bitmap.eraseColor(0); view.draw(canvas)
            assertNull(view.contentDescription)
            assertEquals(0, bitmap.getPixel(270, 390))
            view.update(FocusFeedback.Target(2, 270f, 390f, 400, 500, FocusFeedback.Phase.ACQUIRING, 0))
            view.draw(canvas)
            assertEquals("Old layout must not display a displaced target", 0, bitmap.getPixel(270, 390))
            bitmap.recycle()
        }
    }
}
