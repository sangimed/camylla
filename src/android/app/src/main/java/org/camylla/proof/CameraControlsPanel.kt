package org.camylla.proof

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.*
import android.view.View
import java.util.Locale

/** All displayed values are camera results, with pending/error text separate from the slider. */
internal class CameraControlsPanel(context: Context, private val service: () -> CaptureService?) : LinearLayout(context) {
    private data class Row(val option: CameraOption, val label: TextView, val button: Button?, val slider: SeekBar?, var dragging: Boolean = false)
    private val rows = mutableListOf<Row>()
    private var generation = -1
    private val state = TextView(context).apply { textSize = 16f; setPadding(8, 12, 8, 12) }
    private val quick = LinearLayout(context).apply { orientation = VERTICAL }
    private val pro = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableMapOf<String, Runnable>()
    init {
        orientation = VERTICAL; addView(state)
        addView(quick)
        addView(Button(context).apply { text = "Pro camera controls"; setOnClickListener { pro.visibility = if (pro.visibility == VISIBLE) GONE else VISIBLE } })
        addView(pro)
    }
    fun update(status: CameraStatus) {
        state.text = "Focus: ${status.focus}\n${status.message}"
        if (status.generation != generation) {
            generation = status.generation
            for (task in scheduled.values) handler.removeCallbacks(task)
            scheduled.clear(); rows.clear(); quick.removeAllViews(); pro.removeAllViews()
            val options = status.options.associateBy { it.key }
            val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
            fun action(label: String, key: String, value: () -> Double) {
                actions.addView(Button(context).apply {
                    text = label; contentDescription = label; isEnabled = options[key]?.reason == null && options.containsKey(key)
                    setOnClickListener { service()?.control(key, value()) }
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            action("Focus Auto", "focusMode") { options["focusMode"]?.choices?.keys?.firstOrNull { it != 0 }?.toDouble() ?: 0.0 }
            action("Focus lock", "focusLock") { if (service()?.cameraStatus?.applied?.get("focusLock") == 1.0) 0.0 else 1.0 }
            action("Light", "torch") { if (service()?.cameraStatus?.applied?.get("torch") == 1.0) 0.0 else 1.0 }
            quick.addView(actions)
            quick.addView(Button(context).apply { text = "Focus centre"; isEnabled = options["focusPoint"]?.reason == null && options.containsKey("focusPoint"); setOnClickListener { service()?.control("focusPoint",1.0) } })
            options["zoom"]?.let { row(quick, it) }
            val groups = linkedMapOf(
                "Focus" to listOf("focusMode", "focusDistance", "focusLock"),
                "Exposure and light" to listOf("torch", "torchStrength", "exposureMode", "ev", "aeLock", "iso", "shutter", "antiFlicker"),
                "White balance" to listOf("wbMode", "wbLock", "wbRed", "wbBlue"),
                "Image" to listOf("brightness", "contrast", "saturation", "stabilization"),
                "Output and monitoring" to listOf("outputRotation", "outputMirror", "previewRotation", "previewMirror", "orientationLock", "grid", "zebras", "peaking", "histogram"))
            for ((name, keys) in groups) {
                val panel = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
                pro.addView(Button(context).apply { text = name; setOnClickListener {
                    val open = panel.visibility != VISIBLE
                    for (i in 0 until pro.childCount) if (pro.getChildAt(i) is LinearLayout) pro.getChildAt(i).visibility = GONE
                    panel.visibility = if (open) VISIBLE else GONE
                } })
                keys.mapNotNull { options[it] }.forEach { row(panel, it) }; pro.addView(panel)
            }
            pro.addView(Button(context).apply { text = "Reset camera settings"; setOnClickListener { service()?.resetSettings() } })
            pro.addView(Button(context).apply { text = "Save preset"; setOnClickListener {
                val input = EditText(context).apply { hint = "Preset name"; maxLines = 1 }
                AlertDialog.Builder(context).setTitle("Save camera preset").setView(input).setPositiveButton("Save") { _, _ ->
                    runCatching { service()?.savePreset(input.text.toString()) }.onFailure { state.text = it.message }
                }.setNegativeButton("Cancel", null).show()
            } })
            pro.addView(Button(context).apply { text = "Load or delete preset"; setOnClickListener {
                val names = CameraSettings(context).presets().keys.toTypedArray()
                if (names.isEmpty()) { state.text = "No saved presets"; return@setOnClickListener }
                AlertDialog.Builder(context).setTitle("Camera presets").setItems(names) { _, index ->
                    AlertDialog.Builder(context).setTitle(names[index]).setPositiveButton("Load") { _, _ -> service()?.applyPreset(names[index]) }
                        .setNeutralButton("Delete") { _, _ -> CameraSettings(context).deletePreset(names[index]) }.setNegativeButton("Cancel", null).show()
                }.show()
            } })
        }
        for (row in rows) {
            val o = row.option; val value = status.applied[o.key]
            val display = value?.let { o.choices[it.toInt()] ?: String.format(Locale.US, "%.3f %s", it, o.unit) } ?: "Awaiting camera"
            row.label.text = "${o.label}: $display${if (o.key in status.pending) " · Applying" else ""}${o.reason?.let { "\nUnavailable: $it" } ?: ""}"
            if (value != null && row.slider != null && !row.dragging && o.max > o.min && o.key !in status.pending)
                row.slider.progress = ((value - o.min) / (o.max - o.min) * 1000).toInt().coerceIn(0,1000)
        }
    }
    private fun row(parent: LinearLayout, option: CameraOption) {
        val label = TextView(context).apply { textSize = 16f; setPadding(8,16,8,0) }; parent.addView(label)
        if (option.reason != null) { rows.add(Row(option, label, null, null)); return }
        if (option.choices.isNotEmpty()) {
            val button = Button(context).apply { text = "Choose ${option.label}"; setOnClickListener {
                val keys = option.choices.keys.toList()
                AlertDialog.Builder(context).setTitle(option.label).setItems(keys.map { option.choices.getValue(it) }.toTypedArray()) { _, i ->
                    service()?.control(option.key, keys[i].toDouble())
                }.setNegativeButton("Cancel", null).show()
            } }
            parent.addView(button); rows.add(Row(option, label, button, null)); return
        }
        val slider = SeekBar(context).apply { max = 1000; contentDescription = option.label; minimumHeight = (48 * resources.displayMetrics.density).toInt() }
        val row = Row(option, label, null, slider); rows.add(row)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { row.dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) { row.dragging = false; submit(bar.progress) }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) submit(progress) }
            fun submit(progress: Int) {
                scheduled.remove(option.key)?.let { handler.removeCallbacks(it) }
                val value = option.min + progress / 1000.0 * (option.max - option.min)
                val stepped = if (option.step == 1.0) kotlin.math.round(value) else value
                val token = generation
                val task = Runnable { scheduled.remove(option.key); if (token == generation) service()?.control(option.key, stepped) }
                scheduled[option.key] = task; handler.postDelayed(task, 100)
            }
        })
        parent.addView(slider)
    }
    override fun onDetachedFromWindow() { for (task in scheduled.values) handler.removeCallbacks(task); scheduled.clear(); super.onDetachedFromWindow() }
}
