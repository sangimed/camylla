package org.camylla.proof

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Camylla's code-native dark workspace design system for Android product surfaces. */
internal object MobileUi {
    const val BACKGROUND = 0xFF18181B.toInt()
    const val SURFACE = 0xFF222225.toInt()
    const val SURFACE_RAISED = 0xFF2B2B2F.toInt()
    const val BORDER = 0xFF3A3A3F.toInt()
    const val TEXT = 0xFFF2F2F3.toInt()
    const val MUTED = 0xFFA1A1AA.toInt()
    const val BLUE = 0xFF59A6FF.toInt()
    const val GREEN = 0xFF4DD49B.toInt()
    const val WARNING = 0xFFF3A94B.toInt()
    const val RED = 0xFFFF6B75.toInt()

    const val TOUCH_TARGET_DP = 48
    const val CONTROL_RADIUS_DP = 8
    const val PANEL_RADIUS_DP = 10

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun surface(color: Int = SURFACE, radius: Float = 10f, bordered: Boolean = true) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (bordered) setStroke(1, BORDER)
        }

    fun text(view: TextView, size: Float = 15f, muted: Boolean = false) {
        view.setTextColor(if (muted) MUTED else TEXT)
        view.textSize = size
    }

    fun button(
        view: Button,
        prominent: Boolean = false,
        destructive: Boolean = false,
        selected: Boolean = false,
    ) {
        view.isAllCaps = false
        view.minHeight = dp(view.context, TOUCH_TARGET_DP)
        view.setTextColor(if (prominent || destructive || selected) BACKGROUND else TEXT)
        val fill = when {
            destructive -> RED
            prominent || selected -> BLUE
            else -> SURFACE_RAISED
        }
        val radius = dp(view.context, CONTROL_RADIUS_DP).toFloat()
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        view.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            surface(fill, radius, bordered = !(prominent || destructive || selected)),
            mask,
        )
        view.setPadding(dp(view.context, 12), 0, dp(view.context, 12), 0)
        view.isSelected = selected
    }

    fun seekBar(view: android.widget.SeekBar) {
        view.minimumHeight = dp(view.context, TOUCH_TARGET_DP)
        view.progressTintList = ColorStateList.valueOf(BLUE)
        view.thumbTintList = ColorStateList.valueOf(BLUE)
    }

    fun card(view: View) {
        view.background = surface(
            radius = dp(view.context, PANEL_RADIUS_DP).toFloat(),
        )
    }

    fun input(view: EditText) {
        view.setTextColor(TEXT)
        view.setHintTextColor(MUTED)
        view.background = surface(
            SURFACE_RAISED,
            dp(view.context, CONTROL_RADIUS_DP).toFloat(),
        )
        view.setPadding(dp(view.context, 12), dp(view.context, 8), dp(view.context, 12), dp(view.context, 8))
    }
}
