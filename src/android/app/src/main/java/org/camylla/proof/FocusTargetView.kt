package org.camylla.proof

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** A sibling of the preview SurfaceView, outside both renderer and encoder surfaces. */
internal class FocusTargetView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var target: FocusFeedback.Target? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }

    fun update(value: FocusFeedback.Target?) {
        if (target == value) return
        target = value
        contentDescription = when (value?.phase) {
            FocusFeedback.Phase.ACQUIRING -> "Focusing"
            FocusFeedback.Phase.SUCCEEDED -> "Focus acquired"
            FocusFeedback.Phase.FAILED -> "Could not acquire focus"
            FocusFeedback.Phase.LOCKED -> "Focus locked"
            null -> null
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val value = target ?: return
        if (value.width != width || value.height != height) return
        val density = resources.displayMetrics.density
        val elapsed = (SystemClock.elapsedRealtime() - value.changedAt).coerceAtLeast(0)
        val pulse = if (value.phase == FocusFeedback.Phase.ACQUIRING) 1f + 0.06f * sin(elapsed / 180.0).toFloat() else 1f
        val half = 26f * density * pulse
        val left = value.x - half; val top = value.y - half
        val right = value.x + half; val bottom = value.y + half
        // Dark outline keeps the fine square legible over both light and dark subjects.
        paint.color = Color.BLACK; paint.alpha = 160; paint.strokeWidth = 3f * density
        canvas.drawRect(left, top, right, bottom, paint)
        paint.alpha = 255; paint.strokeWidth = 1.5f * density
        paint.color = when (value.phase) {
            FocusFeedback.Phase.SUCCEEDED -> 0xFF8CE99A.toInt()
            FocusFeedback.Phase.FAILED -> 0xFFFFCB77.toInt()
            FocusFeedback.Phase.LOCKED -> 0xFFFFD166.toInt()
            else -> Color.WHITE
        }
        canvas.drawRect(left, top, right, bottom, paint)
        // A check, cross or lock label distinguishes states without relying only on color.
        val x = value.x; val y = value.y
        when (value.phase) {
            FocusFeedback.Phase.SUCCEEDED -> {
                canvas.drawLine(x - 6*density, y, x - 1*density, y + 5*density, paint)
                canvas.drawLine(x - 1*density, y + 5*density, x + 8*density, y - 5*density, paint)
            }
            FocusFeedback.Phase.FAILED -> {
                canvas.drawLine(x - 5*density, y - 5*density, x + 5*density, y + 5*density, paint)
                canvas.drawLine(x - 5*density, y + 5*density, x + 5*density, y - 5*density, paint)
            }
            FocusFeedback.Phase.LOCKED -> {
                paint.style = Paint.Style.FILL; paint.textSize = 10*density; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("LOCK", x, y + 4*density, paint); paint.style = Paint.Style.STROKE
            }
            else -> postInvalidateOnAnimation()
        }
    }
}
