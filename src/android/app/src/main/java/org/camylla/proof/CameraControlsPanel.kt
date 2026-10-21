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
    private var descriptors: List<CameraOption> = emptyList()
    private val quickButtons = mutableMapOf<String, Button>()
    private var zoomAdjustment: View? = null
    var onQuickAdjustmentOpen: (() -> Unit)? = null
    fun closeQuickAdjustment() { zoomAdjustment?.visibility = GONE }
    private val state = TextView(context).apply {
        MobileUi.text(this, 16f, muted = true)
        val inset = MobileUi.dp(context, 8)
        setPadding(inset, MobileUi.dp(context, 12), inset, MobileUi.dp(context, 12))
    }
    private val quick = LinearLayout(context).apply { orientation = VERTICAL }
    private val pro = LinearLayout(context).apply { orientation = VERTICAL }
    val advancedView: View get() = pro
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableMapOf<String, Runnable>()
    private var simpleMode = true
    private var compactQuickControls = false
    private var latestStatus = CameraStatus()
    init {
        orientation = VERTICAL; addView(state)
        addView(quick)
    }
    private fun styleButton(button: Button) {
        MobileUi.button(button)
        button.textSize = 14f
    }
    private fun spaced(width: Int = LayoutParams.MATCH_PARENT, weight: Float = 0f): LayoutParams =
        LayoutParams(width, LayoutParams.WRAP_CONTENT, weight).apply {
            val gap = (6 * resources.displayMetrics.density).toInt()
            setMargins(gap, gap, gap, gap)
        }
    /** Simple mode leaves focus to the direct preview gesture and exposes only zoom and torch. */
    fun setSimpleMode(value: Boolean) {
        if (simpleMode == value) return
        simpleMode = value
        generation = -1
        update(latestStatus)
    }
    /** Keep frequent actions in a single compact rail on the camera workspace. */
    fun setCompactQuickControls(value: Boolean) {
        compactQuickControls = value
        quick.orientation = if (value) HORIZONTAL else VERTICAL
        for (index in 0 until quick.childCount) {
            val child = quick.getChildAt(index)
            val params = child.layoutParams as LayoutParams
            params.width = if (value) 0 else LayoutParams.MATCH_PARENT
            params.weight = if (value) 1f else 0f
            child.layoutParams = params
        }
    }
    fun update(status: CameraStatus) {
        latestStatus = status
        state.text = if (status.options.isEmpty()) "Start to apply camera controls" else "Focus: ${status.focus}" +
            if (status.message.isNotEmpty() && !status.message.endsWith("applied")) "\n${status.message}" else ""
        state.visibility = if (status.options.isNotEmpty() && status.message.isNotEmpty() && !status.message.endsWith("applied")) VISIBLE else GONE
        // Binding publishes a generation before its descriptors arrive. Runtime validation
        // can also change availability within that generation.
        if (status.generation != generation || status.options != descriptors) {
            generation = status.generation
            descriptors = status.options.toList()
            for (task in scheduled.values) handler.removeCallbacks(task)
            scheduled.clear(); rows.clear(); quickButtons.clear(); quick.removeAllViews(); pro.removeAllViews()
            val options = status.options.associateBy { it.key }
            val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
            fun action(label: String, key: String, value: () -> Double) {
                actions.addView(Button(context).apply {
                    text = label; styleButton(this); contentDescription = label; isEnabled = options[key]?.reason == null && options.containsKey(key)
                    setOnClickListener { service()?.control(key, value()) }
                    quickButtons[key] = this
                }, spaced(0, 1f))
            }
            if (!simpleMode) {
                action("Focus Auto", "focusMode") { CameraQuickControls.autoMode(options["focusMode"]?.choices.orEmpty())?.toDouble() ?: 0.0 }
                action("Focus lock", "focusLock") { if (service()?.cameraStatus?.applied?.get("focusLock") == 1.0) 0.0 else 1.0 }
            }
            action("Torch", "torch") { if (service()?.cameraStatus?.applied?.get("torch") == 1.0) 0.0 else 1.0 }
            quick.addView(actions, spaced())
            val focusCentre = Button(context).apply { text = "Focus centre"; styleButton(this); setOnClickListener { service()?.control("focusPoint",1.0) } }
            quickButtons["focusPoint"] = focusCentre
            // Keep the gesture alternative beside the zoom, rather than adding another full row.
            val zoomRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            val zoomControls = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
            zoomAdjustment = zoomControls
            options["zoom"]?.let { row(zoomControls, it) }
            val zoomButton = Button(context).apply {
                styleButton(this)
                setOnClickListener {
                    val open = zoomControls.visibility != VISIBLE
                    if (open) onQuickAdjustmentOpen?.invoke()
                    zoomControls.visibility = if (open) VISIBLE else GONE
                }
            }
            quickButtons["zoom"] = zoomButton
            zoomRow.addView(zoomButton, spaced(0, if (simpleMode) 1f else .5f))
            if (!simpleMode) zoomRow.addView(focusCentre, spaced(0, .5f))
            quick.addView(zoomRow, spaced())
            quick.addView(zoomControls, spaced())
            val groups = linkedMapOf(
                "Focus" to listOf("focusMode", "focusDistance", "focusLock"),
                "Exposure and light" to listOf("torch", "torchStrength", "exposureMode", "ev", "aeLock", "iso", "shutter", "antiFlicker"),
                "White balance" to listOf("wbMode", "wbLock", "wbRed", "wbBlue"),
                "Image" to listOf("brightness", "contrast", "saturation", "stabilization"),
                "Output and monitoring" to listOf("outputRotation", "outputMirror", "previewRotation", "previewMirror", "orientationLock", "grid", "zebras", "peaking", "histogram"))
            for ((name, keys) in groups) {
                val panel = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
                pro.addView(Button(context).apply { text = name; styleButton(this); setOnClickListener {
                    val open = panel.visibility != VISIBLE
                    for (i in 0 until pro.childCount) if (pro.getChildAt(i) is LinearLayout) pro.getChildAt(i).visibility = GONE
                    panel.visibility = if (open) VISIBLE else GONE
                } }, spaced())
                keys.mapNotNull { options[it] }.forEach { row(panel, it) }; pro.addView(panel, spaced())
            }
            pro.addView(Button(context).apply { text = "Reset camera settings"; styleButton(this); setOnClickListener { service()?.resetSettings() } }, spaced())
            pro.addView(Button(context).apply { text = "Save preset"; styleButton(this); setOnClickListener {
                val input = EditText(context).apply { hint = "Preset name"; maxLines = 1; MobileUi.input(this) }
                AlertDialog.Builder(context).setTitle("Save camera preset").setView(input).setPositiveButton("Save") { _, _ ->
                    runCatching { service()?.savePreset(input.text.toString()) }.onFailure {
                        Toast.makeText(context, it.message ?: "Could not save preset", Toast.LENGTH_LONG).show()
                    }
                }.setNegativeButton("Cancel", null).show()
            } }, spaced())
            pro.addView(Button(context).apply { text = "Load or delete preset"; styleButton(this); setOnClickListener {
                val names = CameraSettings(context).presets().keys.toTypedArray()
                if (names.isEmpty()) { Toast.makeText(context, "No saved presets", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                AlertDialog.Builder(context).setTitle("Camera presets").setItems(names) { _, index ->
                    AlertDialog.Builder(context).setTitle(names[index]).setPositiveButton("Load") { _, _ -> service()?.applyPreset(names[index]) }
                        .setNeutralButton("Delete") { _, _ -> CameraSettings(context).deletePreset(names[index]) }.setNegativeButton("Cancel", null).show()
                }.show()
            } }, spaced())
        }
        setCompactQuickControls(compactQuickControls)
        val availability = mutableListOf<String>()
        for ((key, button) in quickButtons) {
            val presentation = CameraQuickControls.present(key, status)
            val selected = key in setOf("torch", "focusLock") && status.applied[key] == 1.0
            if (button.isSelected != selected) {
                MobileUi.button(button, selected = selected)
                button.textSize = 14f
            }
            button.text = presentation.label
            button.isEnabled = presentation.enabled
            button.contentDescription = presentation.label + (presentation.reason?.let { ". $it" } ?: "")
            presentation.reason?.let { availability.add(it) }
        }
        if (availability.isNotEmpty()) {
            state.text = "${state.text}\n${availability.distinct().joinToString(" · ")}"
            state.visibility = VISIBLE
        }
        for (row in rows) {
            val o = row.option; val value = status.applied[o.key]
            val display = if (o.reason != null) "Unavailable" else value?.let {
                if (o.key == "focusMode" && it == 1.0) "Tap autofocus"
                else o.choices[it.toInt()] ?: String.format(Locale.US, "%.3f %s", it, o.unit)
            } ?: "Awaiting camera"
            row.label.text = "${o.label}: $display${if (o.key in status.pending) " · Applying" else ""}${o.reason?.let { "\nUnavailable: $it" } ?: ""}"
            row.button?.isEnabled = o.key !in status.pending && o.reason == null
            if (value != null && row.slider != null && !row.dragging && o.max > o.min && o.key !in status.pending)
                row.slider.progress = ((value - o.min) / (o.max - o.min) * 1000).toInt().coerceIn(0,1000)
        }
    }
    private fun row(parent: LinearLayout, option: CameraOption) {
        val label = TextView(context).apply {
            MobileUi.text(this, 16f)
            val horizontalInset = MobileUi.dp(context, 8)
            setPadding(horizontalInset, MobileUi.dp(context, 16), horizontalInset, 0)
        }
        parent.addView(label)
        if (option.reason != null) { rows.add(Row(option, label, null, null)); return }
        if (option.choices.isNotEmpty()) {
            val button = Button(context).apply { text = "Choose ${option.label}"; styleButton(this); setOnClickListener {
                val keys = option.choices.keys.toList()
                val token = generation
                AlertDialog.Builder(context).setTitle(option.label).setItems(keys.map { option.choices.getValue(it) }.toTypedArray()) { _, i ->
                    if (service()?.cameraStatus?.generation == token)
                        service()?.control(option.key, keys[i].toDouble())
                    else Toast.makeText(context, "Camera changed. Choose the setting again.", Toast.LENGTH_LONG).show()
                }.setNegativeButton("Cancel", null).show()
            } }
            parent.addView(button, spaced()); rows.add(Row(option, label, button, null)); return
        }
        val slider = SeekBar(context).apply { max = 1000; contentDescription = option.label; MobileUi.seekBar(this) }
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
                val task = Runnable {
                    scheduled.remove(option.key)
                    if (token == generation && service()?.cameraStatus?.generation == token)
                        service()?.control(option.key, stepped)
                }
                scheduled[option.key] = task; handler.postDelayed(task, 100)
            }
        })
        parent.addView(slider)
    }
    override fun onDetachedFromWindow() { for (task in scheduled.values) handler.removeCallbacks(task); scheduled.clear(); super.onDetachedFromWindow() }
}
