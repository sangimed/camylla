package org.camylla.proof

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Flat preference rows used for real settings destinations and their actions. */
internal object PreferenceRows {
    data class Category(val title: String)

    val categories = listOf(
        Category("Video"),
        Category("Camera"),
        Category("Security"),
        Category("Diagnostics"),
    )

    fun row(context: Context, title: String, subtitle: String? = null, categoryIcon: String? = null, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = MobileUi.dp(context, 64)
            setPadding(MobileUi.dp(context, 16), MobileUi.dp(context, 8), MobileUi.dp(context, 16), MobileUi.dp(context, 8))
            isClickable = true
            isFocusable = true
            val selectableBackground = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)
            background = context.getDrawable(selectableBackground.resourceId)
            categoryIcon?.let { addView(CategoryIconView(context, it), LinearLayout.LayoutParams(MobileUi.dp(context, 28), MobileUi.dp(context, 28)).apply { marginEnd = MobileUi.dp(context, 16) }) }
            val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(TextView(context).apply { text = title; MobileUi.text(this, 17f) })
            subtitle?.let { textColumn.addView(TextView(context).apply { text = it; MobileUi.text(this, 14f, muted = true) }) }
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = "›"; MobileUi.text(this, 24f, muted = true) })
            setOnClickListener { onClick() }
        }

    private class CategoryIconView(context: Context, private val category: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MobileUi.TEXT
            style = Paint.Style.STROKE
            strokeWidth = MobileUi.dp(context, 2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            val l = (width - s) / 2; val t = (height - s) / 2
            canvas.save(); canvas.translate(l, t); canvas.scale(s / 28f, s / 28f)
            when (category) {
                "Video" -> {
                    canvas.drawRoundRect(3f, 6f, 25f, 22f, 2f, 2f, paint)
                    canvas.drawLine(11f, 10f, 18f, 14f, paint); canvas.drawLine(18f, 14f, 11f, 18f, paint); canvas.drawLine(11f, 18f, 11f, 10f, paint)
                }
                "Camera" -> {
                    canvas.drawRoundRect(3f, 8f, 25f, 23f, 3f, 3f, paint)
                    canvas.drawRoundRect(8f, 5f, 14f, 9f, 1f, 1f, paint)
                    canvas.drawCircle(15f, 15.5f, 4.5f, paint)
                }
                "Security" -> {
                    val shield = Path().apply { moveTo(14f, 3f); lineTo(24f, 7f); lineTo(23f, 16f); lineTo(19f, 22f); lineTo(14f, 25f); lineTo(9f, 22f); lineTo(5f, 16f); lineTo(4f, 7f); close() }
                    canvas.drawPath(shield, paint); canvas.drawLine(10f, 14f, 13f, 17f, paint); canvas.drawLine(13f, 17f, 19f, 11f, paint)
                }
                else -> {
                    canvas.drawRoundRect(4f, 4f, 24f, 24f, 4f, 4f, paint)
                    canvas.drawLine(8f, 19f, 12f, 14f, paint); canvas.drawLine(12f, 14f, 16f, 17f, paint); canvas.drawLine(16f, 17f, 21f, 9f, paint)
                }
            }
            canvas.restore()
        }
    }
}
