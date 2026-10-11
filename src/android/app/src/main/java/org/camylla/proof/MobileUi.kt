package org.camylla.proof

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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
    fun button(view: Button, prominent: Boolean = false, destructive: Boolean = false, selected: Boolean = false) {
        view.isAllCaps = false
        view.minHeight = dp(view.context, 48)
        view.setTextColor(if (prominent || destructive || selected) BACKGROUND else TEXT)
        val fill = if (destructive) RED else if (prominent || selected) BLUE else SURFACE_RAISED
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(view.context, 12).toFloat() }
        view.background = RippleDrawable(
            ColorStateList.valueOf(0x66FFFFFF),
            surface(fill, dp(view.context, 12).toFloat()),
            mask
        )
        view.setPadding(dp(view.context, 12), 0, dp(view.context, 12), 0)
        view.isSelected = selected
    }
    fun seekBar(view: android.widget.SeekBar) {
        view.minimumHeight = dp(view.context, 48)
        view.progressTintList = ColorStateList.valueOf(BLUE)
        view.thumbTintList = ColorStateList.valueOf(BLUE)
    }
    fun card(view: View) { view.background = surface() }
    fun input(view: EditText) {
        view.setTextColor(TEXT); view.setHintTextColor(MUTED); view.background = surface(SURFACE_RAISED, dp(view.context, 12).toFloat())
        view.setPadding(dp(view.context, 12), dp(view.context, 8), dp(view.context, 12), dp(view.context, 8))
    }
}
