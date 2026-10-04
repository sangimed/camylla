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
            showStats()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        button(captureControls, "Start capture") { startWithPermission() }
        button(captureControls, "Stop capture") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        button(captureControls, "Camera settings") {
            settings.visibility = if (settings.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        button(settings, "Switch front / rear") {
            service?.switchCamera()
            requestedLens = if (requestedLens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
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
        setContentView(ScrollView(this).apply { addView(layout) })
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
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun refreshDesktops() {
        val selected = trustedDesktops.getOrNull(desktops.selectedItemPosition)?.id
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
}
