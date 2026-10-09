package org.camylla.proof

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Original, code-native visual system for the Android product surfaces. */
internal object MobileUi {
    const val BACKGROUND = 0xFF101319.toInt()
    const val SURFACE = 0xFF1A2029.toInt()
    const val SURFACE_RAISED = 0xFF252D38.toInt()
    const val TEXT = 0xFFF4F7FB.toInt()
    const val MUTED = 0xFFB6C2D1.toInt()
    const val BLUE = 0xFF59A6FF.toInt()
    const val GREEN = 0xFF54D69A.toInt()
    const val RED = 0xFFFF6E78.toInt()

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int = SURFACE, radius: Float = 16f) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }
    fun text(view: TextView, size: Float = 15f, muted: Boolean = false) {
        view.setTextColor(if (muted) MUTED else TEXT); view.textSize = size
    }
    fun button(view: Button, prominent: Boolean = false, destructive: Boolean = false) {
        view.isAllCaps = false
        view.minHeight = dp(view.context, 48)
        view.setTextColor(if (prominent || destructive) BACKGROUND else TEXT)
        view.background = surface(if (destructive) RED else if (prominent) BLUE else SURFACE_RAISED)
        view.setPadding(dp(view.context, 12), 0, dp(view.context, 12), 0)
    }
    fun card(view: View) { view.background = surface() }
    fun input(view: EditText) {
        view.setTextColor(TEXT); view.setHintTextColor(MUTED); view.background = surface(SURFACE_RAISED, 12f)
        view.setPadding(dp(view.context, 12), dp(view.context, 8), dp(view.context, 12), dp(view.context, 8))
    }
}
