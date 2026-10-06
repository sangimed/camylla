package org.camylla.proof

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : Activity() {
    private var service: CaptureService? = null
    private var bound = false
    private var requestedLens = CameraSelector.LENS_FACING_BACK
    private lateinit var modes: Spinner
    private lateinit var stats: TextView
    private lateinit var zoom: SeekBar
    private lateinit var desktops: Spinner
    private lateinit var captureControls: LinearLayout
    private lateinit var trustControls: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var resumeKeyChange: Button
    private var trustedDesktops: List<TrustedDesktop> = emptyList()
    private val identity get() = ProductIdentity(this)
    private var trustError = ""
    private lateinit var previewView: SurfaceView
    private lateinit var cameraPanel: CameraControlsPanel
    private lateinit var histogramView: View
    private lateinit var lensChoice: Button
    private lateinit var previewPlaceholder: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            showStats()
            handler.postDelayed(this, 1000)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as CaptureService.LocalBinder).service
            attachPreview()
            showStats()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val upgrade = getSharedPreferences("production-setup", Context.MODE_PRIVATE)
        if (!upgrade.getBoolean("upgrade_explained", false)) {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (info.lastUpdateTime != info.firstInstallTime && runCatching { identity.desktops().none { !it.revoked } }.getOrDefault(false))
                android.widget.Toast.makeText(this, "Connection setup has changed. Scan your PC's QR to pair again.", android.widget.Toast.LENGTH_LONG).show()
            upgrade.edit().putBoolean("upgrade_explained", true).apply()
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun button(parent: LinearLayout, label: String, action: () -> Unit): Button {
            val value = Button(this).apply { text = label; setOnClickListener { action() } }
            parent.addView(value); return value
        }
        layout.addView(TextView(this).apply { text = "Camylla"; textSize = 28f })
        connectionStatus = TextView(this).apply { textSize = 18f; setPadding(0, 24, 0, 24) }
        layout.addView(connectionStatus)
        button(layout, "Scan PC QR") {
            if (service?.stats()?.state?.let { it != "Stopped" } == true) connectionStatus.text = "Stop capture before pairing."
            else startActivity(Intent(this, PairActivity::class.java))
        }
        trustControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        desktops = Spinner(this)
        trustControls.addView(desktops)
        layout.addView(trustControls)
        captureControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modes = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                CaptureService.Mode.entries.map { it.label })
        }
        captureControls.addView(modes)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        button(captureControls, "Camera settings") {
            settings.visibility = if (settings.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        lensChoice = button(captureControls, "Choose optical camera") {
            val list = service?.cameras().orEmpty()
            if (list.isEmpty()) { connectionStatus.text = "Camera list unavailable"; return@button }
            android.app.AlertDialog.Builder(this).setTitle("Select optical camera").setItems(list.map { it.label }.toTypedArray()) { _, i ->
                CameraSettings(this).preferredCamera = list[i].id
                requestedLens = if (list[i].facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                if (service?.stats()?.state != "Stopped") service?.selectCamera(list[i].id)
            }.show()
        }
        button(settings, "Apply selected mode") { service?.setMode(CaptureService.Mode.entries[modes.selectedItemPosition]) }
        settings.addView(TextView(this).apply { text = "Zoom" })
        zoom = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) service?.setZoom(progress / 100f) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        settings.addView(zoom)
        zoom.visibility = View.GONE
        cameraPanel = CameraControlsPanel(this) { service }
        captureControls.addView(cameraPanel)
        button(settings, "Forget this PC") {
            val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition)
            if (peer != null) android.app.AlertDialog.Builder(this).setTitle("Forget ${peer.name}?")
                .setMessage("Capture will stop. Scan its QR to connect again.")
                .setPositiveButton("Forget") { _, _ ->
                    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    runCatching { identity.revoke(peer.id); refreshDesktops() }.onFailure { connectionStatus.text = "Could not save this change." }
                }.setNegativeButton("Cancel", null).show()
        }
        button(settings, "Change phone security key") { openRotation("phone") }
        button(settings, "Confirm PC key change") { openRotation("desktop") }
        resumeKeyChange = button(layout, "Resume key change") {
            if (identity.pendingRotation != null) android.app.AlertDialog.Builder(this)
                .setTitle("Pending security key change")
                .setMessage("Resume to check whether the PC committed the change. If recovery is impossible, repair forgets all PCs and requires scanning a fresh QR. Capture stays stopped.")
                .setPositiveButton("Resume") { _, _ -> openRotation("resume") }
                .setNeutralButton("Repair pairing") { _, _ ->
                    android.app.AlertDialog.Builder(this).setTitle("Forget PCs and pair again?")
                        .setMessage("All saved PC approvals will be revoked locally. The proposed phone key is retained if it may already be committed; the previous key will not be restored.")
                        .setPositiveButton("Repair") { _, _ ->
                            runCatching { identity.repairPendingRotation(); refreshDesktops() }
                                .onFailure { connectionStatus.text = "Repair could not be saved. Capture remains blocked." }
                        }.setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Cancel", null).show()
            else connectionStatus.text = "No key change is pending."
        }
        resumeKeyChange.visibility = android.view.View.GONE
        captureControls.addView(settings)
        layout.addView(captureControls)
        stats = TextView(this).apply { textSize = 14f; visibility = android.view.View.GONE }
        button(layout, "Diagnostics") {
            stats.visibility = if (stats.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        layout.addView(stats)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); minimumHeight = (180 * resources.displayMetrics.density).toInt() }
        previewView = SurfaceView(this).apply { contentDescription = "Camera preview. Tap to focus; pinch to zoom. Focus centre and zoom buttons are available below." }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(-1,-1))
        previewPlaceholder = TextView(this).apply { text = "Start camera to preview"; setTextColor(android.graphics.Color.WHITE); textSize = 20f; gravity = android.view.Gravity.CENTER }
        previewFrame.addView(previewPlaceholder, FrameLayout.LayoutParams(-1,-1))
        histogramView = object : View(this) {
            val paint = Paint().apply { color = android.graphics.Color.WHITE; strokeWidth = 2f }
            override fun onDraw(canvas: Canvas) {
                val state = service?.cameraStatus ?: return
                if (state.applied["histogram"] != 1.0) return
                val bins = state.histogram; val peak = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
                val w = minOf(width.toFloat(), 256f * resources.displayMetrics.density); val h = 64f * resources.displayMetrics.density
                paint.color = 0xAA000000.toInt(); canvas.drawRect(8f, 8f, w + 8, h + 8, paint); paint.color = android.graphics.Color.WHITE
                for (i in bins.indices) canvas.drawLine(8+i*w/64,8+h,8+i*w/64,8+h-bins[i].toFloat()/peak*h,paint)
            }
        }
        previewFrame.addView(histogramView, FrameLayout.LayoutParams(-1,-1))
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { service?.tapFocus(e.x.toDouble(), e.y.toDouble(), previewView.width, previewView.height); return true }
            override fun onLongPress(e: MotionEvent) { service?.control("focusLock", 1.0) }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var last = 0L
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (android.os.SystemClock.elapsedRealtime() - last < 100) return true
                last = android.os.SystemClock.elapsedRealtime()
                val state = service?.cameraStatus ?: return false
                val o = state.options.find { it.key == "zoom" } ?: return false
                service?.control("zoom", ((state.applied["zoom"] ?: 1.0) * detector.scaleFactor).coerceIn(o.min,o.max)); return true
            }
        })
        previewView.setOnTouchListener { _, event -> pinch.onTouchEvent(event); if (!pinch.isInProgress) gestures.onTouchEvent(event); true }
        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { attachPreview() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { attachPreview() }
            override fun surfaceDestroyed(holder: SurfaceHolder) { service?.attachPreview(null, 1, 1) }
        })
        val scroll = ScrollView(this).apply { addView(layout) }
        if (body.orientation == LinearLayout.HORIZONTAL) {
            body.addView(previewFrame, LinearLayout.LayoutParams(0,-1,2f)); body.addView(scroll, LinearLayout.LayoutParams(0,-1,1f))
        } else {
            body.addView(previewFrame, LinearLayout.LayoutParams(-1,0,3f)); body.addView(scroll, LinearLayout.LayoutParams(-1,0,2f))
        }
        root.addView(body, LinearLayout.LayoutParams(-1,0,1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "Start camera"; setOnClickListener { startWithPermission() } }
        val stop = Button(this).apply { text = "Stop camera"; setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) } }
        actions.addView(start, LinearLayout.LayoutParams(0,-2,1f)); actions.addView(stop, LinearLayout.LayoutParams(0,-2,1f)); root.addView(actions)
        setContentView(root)
        modes.setSelection(CaptureService.Mode.fromName(CameraSettings(this).preferredMode).ordinal)
        showStats()
    }

    private fun startWithPermission() {
        if (identity.pendingRotation != null) {
            connectionStatus.text = "Resume the pending key change before starting capture."; return
        }
        if (trustedDesktops.getOrNull(desktops.selectedItemPosition) == null) {
            stats.text = "Pair a desktop before starting capture."; return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
        val intent = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_MODE, CaptureService.Mode.entries[modes.selectedItemPosition].name)
            .putExtra(CaptureService.EXTRA_LENS, requestedLens)
            .putExtra(CaptureService.EXTRA_CAMERA, CameraSettings(this).preferredCamera)
            .putExtra(CaptureService.EXTRA_PEER, trustedDesktops.getOrNull(desktops.selectedItemPosition)?.id)
        ContextCompat.startForegroundService(this, intent)
    }
    private fun openRotation(role: String) {
        if (service?.stats()?.state?.let { it != "Stopped" } == true) {
            connectionStatus.text = "Stop capture before changing security keys."; return
        }
        val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition) ?: return
        startActivity(Intent(this, RotationActivity::class.java).putExtra("role", role).putExtra("peer", peer.id))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWithPermission()
            else stats.text = "Camera permission denied. Capture was not started."
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        refreshDesktops()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        service?.attachPreview(null, 1, 1)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun refreshDesktops() {
        val selected = trustedDesktops.getOrNull(desktops.selectedItemPosition)?.id ?: CameraSettings(this).preferredPeer
        runCatching { identity.desktops().filter { !it.revoked } }.onSuccess {
            trustedDesktops = it; trustError = ""
            resumeKeyChange.visibility = if (identity.pendingRotation != null) android.view.View.VISIBLE else android.view.View.GONE
            desktops.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                it.map { peer -> peer.name }.ifEmpty { listOf("No paired desktop") })
            val index = it.indexOfFirst { peer -> peer.id == selected }
            if (index >= 0) desktops.setSelection(index)
            captureControls.visibility = if (it.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            trustControls.visibility = captureControls.visibility
        }.onFailure { trustError = "Protected trust unavailable. Re-pair or restore this app's keys." }
    }
    private fun showStats() {
        val value = service?.stats() ?: StatsSnapshot()
        if (::cameraPanel.isInitialized) cameraPanel.update(service?.cameraStatus ?: CameraStatus())
        if (::histogramView.isInitialized) histogramView.invalidate()
        if (::previewPlaceholder.isInitialized) {
            previewPlaceholder.visibility = if (value.state == "Capturing") View.GONE else View.VISIBLE
            previewPlaceholder.text = if (value.state == "Stopped") "Start camera to preview" else value.state
        }
        if (::lensChoice.isInitialized) lensChoice.text = if (value.camera.isNotEmpty()) "Camera: ${value.camera}" else "Choose optical camera"
        service?.displayRotation(when (windowManager.defaultDisplay.rotation) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 })
        desktops.isEnabled = value.state == "Stopped"
        connectionStatus.text = when {
            trustError.isNotEmpty() -> trustError
            runCatching { identity.pendingRotation != null }.getOrDefault(false) -> "Security key change pending. Resume or repair pairing before capture."
            trustedDesktops.isEmpty() -> "Connect your PC by scanning its QR."
            value.state == "Stopped" -> "Connected to ${trustedDesktops.getOrNull(desktops.selectedItemPosition)?.name ?: "your PC"}. Ready to start."
            value.error.isNotEmpty() -> value.error
            else -> value.state
        }
        stats.text = """
            ${if (trustError.isNotEmpty()) trustError else ""}
            State: ${value.state}${if (value.error.isNotEmpty()) " — ${value.error}" else ""}
            Requested: ${value.requestedMode}
            Camera: ${value.camera}
            Encoded resolution: ${value.resolution}
            Encoder: ${value.codec} at ${value.bitrate} bit/s
            Encoded FPS (5 s): ${String.format(Locale.US, "%.1f", value.fps)}
            Frames: ${value.frames}  Keyframes: ${value.keyframes}
            Timestamp: ${value.timestampUs} μs  Gaps >50 ms: ${value.timestampGaps}
            Elapsed: ${value.elapsedSeconds} s
            AF: ${value.autofocus}  Zoom: ${String.format(Locale.US, "%.2f", value.zoomRatio)}x
            Transform rotation: ${value.rotationDegrees}°  Thermal status: ${value.thermalStatus}
            Diagnostic bytes: ${value.sinkBytes}${if (value.sinkLimitReached) " (64 MiB cap reached)" else ""}
            Output: ${value.outputFile}
            Network: ${value.network.state}${if (value.network.error.isNotEmpty()) " — ${value.network.error}" else ""}
            Queued: ${value.network.queuedFrames}  Sent: ${value.network.sentFrames}  Bytes: ${value.network.sentBytes}
            Queue depth/high-water: ${value.network.depth}/${value.network.highWater}
            Resets: ${value.network.resets}  Dropped: ${value.network.droppedFrames}  Network errors: ${value.network.networkErrors}
            Connection attempts: ${value.network.connectionAttempts}  Consecutive failures: ${value.network.consecutiveFailures}
            Successful recoveries: ${value.network.successfulRecoveries}  Last sequence: ${value.network.lastSequence}
        """.trimIndent()
    }

    companion object {
        private const val CAMERA_REQUEST = 1
        private const val NOTIFICATION_REQUEST = 2
    }
    private fun attachPreview() {
        if (::previewView.isInitialized && previewView.holder.surface.isValid)
            service?.attachPreview(previewView.holder.surface, previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1))
    }
}
