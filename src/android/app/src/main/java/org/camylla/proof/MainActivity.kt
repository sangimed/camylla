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
    private lateinit var modeChoice: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var cameraList: List<CameraEntry> = emptyList()
    private var selectedMode = CaptureService.Mode.HD
    private lateinit var stats: TextView
    private lateinit var desktops: Spinner
    private lateinit var trustControls: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var consoleDetails: TextView
    private lateinit var retryConnection: Button
    private lateinit var resumeKeyChange: Button
    private var trustedDesktops: List<TrustedDesktop> = emptyList()
    private val identity get() = ProductIdentity(this)
    private var trustError = ""
    private var selectedPage = 0
    private var selectedSettingsCategory = -1
    private var workspaceMode: CameraWorkspaceLayout.Mode? = null
    private var notice = ""
    private var noticeUntil = 0L
    private lateinit var previewView: SurfaceView
    private lateinit var cameraPanel: CameraControlsPanel
    private lateinit var histogramView: View
    private lateinit var lensChoice: Button
    private lateinit var previewPlaceholder: TextView
    private lateinit var connectionPrompt: LinearLayout
    private lateinit var focusBadge: TextView
    private lateinit var focusTargetView: FocusTargetView
    private lateinit var peerButton: Button
    private lateinit var compactStatus: TextView
    private var returnToCamera: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            showStats()
            handler.postDelayed(this, 1000)
        }
    }
    private val refreshFocus = object : Runnable {
        override fun run() {
            val target = if (service?.stats()?.state == "Capturing") service?.cameraStatus?.focusTarget else null
            focusTargetView.update(target)
            handler.postDelayed(this, 50)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as CaptureService.LocalBinder).service
            cameraList = runCatching { service?.cameras().orEmpty() }.getOrDefault(emptyList())
            if (service?.stats()?.state !in listOf("Capturing", "Starting", "Binding")) {
                val preferred = cameraList.find { it.id == CameraSettings(this@MainActivity).preferredCamera } ?: cameraList.firstOrNull()
                if (preferred != null && selectedMode !in preferred.modes) {
                    selectedMode = preferred.modes.first()
                    CameraSettings(this@MainActivity).preferredMode = selectedMode.name
                }
            }
            attachPreview()
            updateKeepScreenOn()
            showStats()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; focusTargetView.update(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val upgrade = getSharedPreferences("production-setup", Context.MODE_PRIVATE)
        if (!upgrade.getBoolean("upgrade_explained", false)) {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (info.lastUpdateTime != info.firstInstallTime && runCatching { identity.desktops().none { !it.revoked } }.getOrDefault(false))
                android.widget.Toast.makeText(this, "Connection setup has changed. Pair your PC with a code or QR.", android.widget.Toast.LENGTH_LONG).show()
            upgrade.edit().putBoolean("upgrade_explained", true).apply()
        }
        fun dp(value: Int) = MobileUi.dp(this, value)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val connections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        fun button(parent: LinearLayout, label: String, action: () -> Unit): Button {
            val value = Button(this).apply {
                text = label; isAllCaps = false; textSize = 14f
                MobileUi.button(this)
                setOnClickListener { action() }
            }
            val params = if (parent.orientation == LinearLayout.HORIZONTAL)
                LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
            params.setMargins(dp(4), dp(4), dp(4), dp(4))
            parent.addView(value, params); return value
        }
        connectionStatus = TextView(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); MobileUi.text(this, 14f, true) }
        connections.addView(TextView(this).apply { text = "Connections"; MobileUi.text(this, 24f) })
        val connectionsBack = Button(this).apply { text = "‹  Camera"; contentDescription = "Back to Camera"; MobileUi.button(this) }
        connections.addView(connectionsBack)
        connections.addView(TextView(this).apply { text = "Pair a PC to start your camera stream."; MobileUi.text(this, 15f, true) })
        button(connections, "Pair a PC") { openPairing() }
        trustControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        desktops = Spinner(this)
        trustControls.addView(desktops)
        connections.addView(trustControls)
        val connectionManagement = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selections = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lensChoice = button(selections, "Camera") {
            val list = cameraList
            if (list.isEmpty()) { showNotice("Camera list unavailable"); return@button }
            val currentMode = service?.cameraStatus?.mode?.takeIf { it.isNotEmpty() }?.let { CaptureService.Mode.fromName(it) } ?: selectedMode
            val active = service?.stats()?.state in listOf("Capturing", "Starting", "Binding")
            val token = service?.cameraStatus?.generation
            android.app.AlertDialog.Builder(this).setTitle("Optical camera · digital zoom is separate")
                .setItems(list.map { it.label + if (active && currentMode !in it.modes) " · unavailable in ${currentMode.label}" else "" }.toTypedArray()) { _, i ->
                if (service?.cameraStatus?.generation != token) {
                    showNotice("Camera changed. Choose the camera again."); return@setItems
                }
                if (active) {
                    if (currentMode in list[i].modes) service?.selectCamera(list[i].id)
                    else showNotice("Choose a compatible video mode before switching camera.")
                } else {
                    CameraSettings(this).preferredCamera = list[i].id
                    if (selectedMode !in list[i].modes) selectedMode = list[i].modes.first()
                    CameraSettings(this).preferredMode = selectedMode.name
                }
                showStats()
            }.show()
        }
        modeChoice = button(selections, "Video mode") {
            val status = service?.cameraStatus ?: CameraStatus()
            val current = cameraList.find { it.id == status.camera.ifEmpty { CameraSettings(this).preferredCamera } } ?: cameraList.firstOrNull()
            val supported = current?.modes.orEmpty()
            if (supported.isEmpty()) { showNotice("Video modes unavailable: no supported camera."); return@button }
            android.app.AlertDialog.Builder(this).setTitle("Video mode · supported by this camera")
                .setItems(supported.map { it.label }.toTypedArray()) { _, i ->
                    if (service?.cameraStatus?.generation != status.generation) {
                        showNotice("Camera changed. Choose the video mode again."); return@setItems
                    }
                    if (service?.stats()?.state == "Capturing") service?.setMode(supported[i])
                    else { selectedMode = supported[i]; CameraSettings(this).preferredMode = selectedMode.name }
                    showStats()
                }.show()
        }
        for (i in 0 until selections.childCount) selections.getChildAt(i).layoutParams =
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        cameraPanel = CameraControlsPanel(this) { service }
        button(connectionManagement, "Forget this PC") {
            val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition)
            if (peer != null) android.app.AlertDialog.Builder(this).setTitle("Forget ${peer.name}?")
                .setMessage("Capture will stop. Pair again with a code or QR.")
                .setPositiveButton("Forget") { _, _ ->
                    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    runCatching { identity.revoke(peer.id); refreshDesktops() }.onFailure { showNotice("Could not save this change.") }
                }.setNegativeButton("Cancel", null).show()
        }
        button(settings, "Change phone security key") { openRotation("phone") }
        button(settings, "Confirm PC key change") { openRotation("desktop") }
        resumeKeyChange = button(layout, "Resume key change") {
            if (identity.pendingRotation != null) android.app.AlertDialog.Builder(this)
                .setTitle("Pending security key change")
                .setMessage("Resume to check whether the PC committed the change. If recovery is impossible, repair forgets all PCs and requires a fresh code or QR. Capture stays stopped.")
                .setPositiveButton("Resume") { _, _ -> openRotation("resume") }
                .setNeutralButton("Repair pairing") { _, _ ->
                    android.app.AlertDialog.Builder(this).setTitle("Forget PCs and pair again?")
                        .setMessage("All saved PC approvals will be revoked locally. The proposed phone key is retained if it may already be committed; the previous key will not be restored.")
                        .setPositiveButton("Repair") { _, _ ->
                            runCatching { identity.repairPendingRotation(); refreshDesktops() }
                                .onFailure { showNotice("Repair could not be saved. Capture remains blocked.") }
                        }.setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Cancel", null).show()
            else showNotice("No key change is pending.")
        }
        resumeKeyChange.visibility = android.view.View.GONE
        layout.removeView(resumeKeyChange)
        connections.addView(connectionManagement)
        layout.addView(TextView(this).apply { text = "Settings"; MobileUi.text(this, 24f) })
        val settingsBack = Button(this).apply { text = "‹  Camera"; contentDescription = "Back to Camera"; MobileUi.button(this) }
        layout.addView(settingsBack)
        val categoryIndex = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val categoryDetail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        layout.addView(categoryIndex)
        layout.addView(categoryDetail)
        stats = TextView(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); MobileUi.text(this, 14f, true) }
        val diagnosticsSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagnosticsSettings.addView(stats)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobileUi.BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        val titleRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this@MainActivity).apply { text = "CAMYLLA"; letterSpacing = .12f; MobileUi.text(this, 18f) }, LinearLayout.LayoutParams(0, -2, 1f))
        val proPreference = getSharedPreferences("mobile-ui", Context.MODE_PRIVATE)
        var proMode = proPreference.getBoolean("pro_mode", false)
        val proToggle = Button(this).apply { MobileUi.button(this) }
        titleRow.addView(proToggle)
        header.addView(titleRow)
        val connectionsNav = Button(this).apply { text = "PC"; contentDescription = "Connections"; MobileUi.button(this) }
        peerButton = connectionsNav
        val settingsNav = Button(this).apply { text = "⚙"; contentDescription = "Settings"; MobileUi.button(this) }
        proToggle.visibility = View.GONE
        header.orientation = LinearLayout.HORIZONTAL
        header.setPadding(dp(12), dp(2), dp(12), dp(2))
        titleRow.layoutParams = LinearLayout.LayoutParams(0, -2, .4f)
        compactStatus = TextView(this).apply {
            MobileUi.text(this, 12f, muted = true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Compact connection and capture status"
        }
        header.addView(compactStatus, LinearLayout.LayoutParams(0, -2, .6f))
        header.addView(settingsNav, LinearLayout.LayoutParams(dp(56), -2))
        header.addView(connectionsNav, LinearLayout.LayoutParams(dp(64), -2))
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusRail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        consoleDetails = TextView(this).apply { MobileUi.text(this, 13f, muted = true); contentDescription = "Camera and stream details" }
        statusRail.addView(TextView(this).apply { text = "CONNECTION / VIDEO"; MobileUi.text(this, 11f, muted = true) })
        statusRail.addView(consoleDetails)
        statusRail.addView(connectionStatus)
        statusRail.addView(Button(this).apply {
            text = "Pair a PC"; MobileUi.button(this); setOnClickListener { openPairing() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        previewView = SurfaceView(this).apply { contentDescription = "Camera preview. Tap to focus; pinch to zoom. Focus centre and zoom buttons are available below." }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(-1,-1))
        previewPlaceholder = TextView(this).apply { text = "Start camera to preview"; setTextColor(android.graphics.Color.WHITE); textSize = 20f; gravity = android.view.Gravity.CENTER }
        previewFrame.addView(previewPlaceholder, FrameLayout.LayoutParams(-1,-1))
        connectionPrompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = MobileUi.surface(MobileUi.SURFACE, 20f)
            addView(TextView(this@MainActivity).apply { text = "Connect your PC"; gravity = android.view.Gravity.CENTER; MobileUi.text(this, 20f) })
            addView(TextView(this@MainActivity).apply {
                text = "Pair with the code shown in Camylla for Windows, or scan its QR."; gravity = android.view.Gravity.CENTER
                setPadding(0, dp(8), 0, dp(12)); MobileUi.text(this, 15f, true)
            })
            addView(Button(this@MainActivity).apply {
                text = "Connect a PC"; MobileUi.button(this, prominent = true); setOnClickListener { openPairing() }
            })
        }
        previewFrame.addView(connectionPrompt, FrameLayout.LayoutParams(dp(360), -2, android.view.Gravity.CENTER))
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
        focusTargetView = FocusTargetView(this)
        previewFrame.addView(focusTargetView, FrameLayout.LayoutParams(-1,-1))
        focusBadge = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4)); textSize = 16f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewFrame.addView(focusBadge, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START))
        selections.setBackgroundColor(0xDD18181B.toInt())
        previewFrame.addView(selections, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                service?.tapFocus(e.x.toDouble(), e.y.toDouble(), previewView.width, previewView.height) { error ->
                    if (error != null && error !in setOf("Superseded", "Tap is outside the camera image")) showNotice(error)
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) { service?.control("focusLock", 1.0) }
        }).apply { setOnDoubleTapListener(null) }
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
        var multiplePointers = false
        previewView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) multiplePointers = false
            if (event.pointerCount > 1 && !multiplePointers) {
                multiplePointers = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
                service?.clearFocusFeedback(); focusTargetView.update(null)
            }
            pinch.onTouchEvent(event)
            if (!multiplePointers) gestures.onTouchEvent(event)
            true
        }
        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { attachPreview() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { attachPreview() }
            override fun surfaceDestroyed(holder: SurfaceHolder) { focusTargetView.update(null); service?.attachPreview(null, 1, 1) }
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(cameraPanel)
        val drawer = ScrollView(this).apply { visibility = View.GONE }
        cameraPanel.onQuickAdjustmentOpen = { drawer.removeAllViews(); drawer.visibility = View.GONE }
        val drawerTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(title: String, content: View) {
            val toggle = button(drawerTabs, title) {
                cameraPanel.closeQuickAdjustment()
                val close = drawer.visibility == View.VISIBLE && drawer.getChildAt(0) === content
                drawer.removeAllViews()
                drawer.visibility = if (close) View.GONE else View.VISIBLE
                if (!close) drawer.addView(content)
            }
            toggle.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        tab("Advanced controls", cameraPanel.advancedView)
        controls.addView(drawerTabs)
        controls.addView(drawer, LinearLayout.LayoutParams(-1, dp(180)))
        val controlsScroll = ScrollView(this).apply { isFillViewport = false; addView(controls) }
        val centerWorkspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        centerWorkspace.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        centerWorkspace.addView(controlsScroll, LinearLayout.LayoutParams(-1, dp(156)))
        body.addView(centerWorkspace)
        var placeCaptureActions: ((Boolean) -> Unit) = {}
        fun adaptWorkspace() {
            if (body.width <= 0 || body.height <= 0) return
            val density = resources.displayMetrics.density
            val mode = CameraWorkspaceLayout.choose(
                (body.width / density).toInt(), (body.height / density).toInt(), resources.configuration.fontScale
            )
            if (mode == workspaceMode) return
            workspaceMode = mode
            if (mode == CameraWorkspaceLayout.Mode.SPLIT) {
                titleRow.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                placeCaptureActions(true)
                body.orientation = LinearLayout.HORIZONTAL
                statusRail.visibility = View.VISIBLE
                compactStatus.visibility = View.GONE
                body.removeAllViews()
                body.addView(statusRail, LinearLayout.LayoutParams(dp(154), -1))
                body.addView(centerWorkspace, LinearLayout.LayoutParams(0, -1, 1f))
                centerWorkspace.orientation = LinearLayout.VERTICAL
                previewFrame.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
                controlsScroll.layoutParams = LinearLayout.LayoutParams(-1, dp(82))
                controls.orientation = LinearLayout.VERTICAL
                cameraPanel.orientation = LinearLayout.VERTICAL
                cameraPanel.setCompactQuickControls(true)
                connectionPrompt.visibility = View.GONE
                previewPlaceholder.text = "Preview unavailable · pair a PC to start"
            } else {
                titleRow.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                compactStatus.visibility = if (selectedPage == 0) View.VISIBLE else View.GONE
                placeCaptureActions(false)
                body.orientation = LinearLayout.VERTICAL
                statusRail.visibility = View.GONE
                body.removeAllViews()
                val availableWidthDp = (body.width / density).toInt()
                val workspaceWidth = if (availableWidthDp >= 600) dp(minOf(720, availableWidthDp)) else -1
                body.addView(centerWorkspace, LinearLayout.LayoutParams(workspaceWidth, -1).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
                previewFrame.layoutParams = LinearLayout.LayoutParams(-1, 0, if (mode == CameraWorkspaceLayout.Mode.COMPACT) .58f else .64f)
                controlsScroll.layoutParams = LinearLayout.LayoutParams(-1, 0, if (mode == CameraWorkspaceLayout.Mode.COMPACT) .42f else .36f)
                controlsScroll.isFillViewport = true
                cameraPanel.setCompactQuickControls(true)
                previewPlaceholder.text = "Start camera to preview"
                connectionPrompt.visibility = View.GONE
            }
            body.requestLayout()
        }
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> adaptWorkspace() }
        val cameraPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cameraPage.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "Start camera"; MobileUi.button(this, prominent = true); setOnClickListener { startWithPermission() } }
        stopButton = Button(this).apply { text = "Stop camera"; MobileUi.button(this, destructive = true); setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) } }
        retryConnection = Button(this).apply { text = "Retry now"; MobileUi.button(this); setOnClickListener { service?.retryConnection() } }
        actions.addView(startButton, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        actions.addView(retryConnection, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        actions.addView(stopButton, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }); cameraPage.addView(actions)
        placeCaptureActions = { inRail ->
            val captureActions = listOf(startButton, retryConnection, stopButton)
            if (inRail) {
                for (button in captureActions) {
                    (button.parent as? android.view.ViewGroup)?.removeView(button)
                    statusRail.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
                actions.visibility = View.GONE
            } else {
                for (button in captureActions) {
                    (button.parent as? android.view.ViewGroup)?.removeView(button)
                    actions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
                actions.visibility = View.VISIBLE
            }
        }
        val pages = FrameLayout(this)
        val connectionsPage = ScrollView(this).apply { addView(connections); visibility = View.GONE }
        val settingsPage = ScrollView(this).apply { addView(layout); visibility = View.GONE }
        var selectCameraPage: () -> Unit = {}
        var openCameraControls: () -> Unit = {}
        fun openPresets() {
            val names = CameraSettings(this).presets().keys.toTypedArray()
            if (names.isEmpty()) { showNotice("No saved presets"); return }
            android.app.AlertDialog.Builder(this).setTitle("Camera presets").setItems(names) { _, index ->
                android.app.AlertDialog.Builder(this).setTitle(names[index])
                    .setPositiveButton("Load") { _, _ -> service?.applyPreset(names[index]) }
                    .setNeutralButton("Delete") { _, _ -> CameraSettings(this).deletePreset(names[index]); showNotice("Preset deleted") }
                    .setNegativeButton("Cancel", null).show()
            }.show()
        }
        fun showSettingsCategory(index: Int) {
            selectedSettingsCategory = index
            categoryIndex.visibility = View.GONE
            categoryDetail.visibility = View.VISIBLE
            settingsBack.visibility = View.GONE
            categoryDetail.removeAllViews()
            val category = PreferenceRows.categories[index]
            val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            title.addView(Button(this).apply { text = "‹"; contentDescription = "Back to settings"; MobileUi.button(this); setOnClickListener {
                categoryDetail.visibility = View.GONE; categoryIndex.visibility = View.VISIBLE; settingsBack.visibility = View.VISIBLE; selectedSettingsCategory = -1
            } })
            title.addView(TextView(this).apply { text = category.title; MobileUi.text(this, 22f) })
            categoryDetail.addView(title)
            when (category.title) {
                "Video" -> categoryDetail.addView(PreferenceRows.row(this, "Video mode", "${selectedMode.label} · supported output resolution and frame rate", onClick = { selectCameraPage(); modeChoice.performClick() }))
                "Camera" -> {
                    categoryDetail.addView(PreferenceRows.row(this, "Optical camera", "Choose a camera supported by this device", onClick = { lensChoice.performClick() }))
                    categoryDetail.addView(PreferenceRows.row(this, "Advanced camera controls", "Focus, exposure, white balance, image and preview aids", onClick = openCameraControls))
                    categoryDetail.addView(PreferenceRows.row(this, "Camera presets", "Apply or delete a saved camera preset", onClick = ::openPresets))
                }
                "Security" -> {
                    categoryDetail.addView(TextView(this).apply { text = "Pairing and trusted PCs are managed in Connections."; MobileUi.text(this, 15f, muted = true) })
                    categoryDetail.addView(settings)
                    categoryDetail.addView(resumeKeyChange)
                }
                "Diagnostics" -> {
                    categoryDetail.addView(TextView(this).apply { text = "Live service, camera, encoder and transport diagnostics."; MobileUi.text(this, 15f, muted = true) })
                    categoryDetail.addView(diagnosticsSettings)
                }
            }
            for (index in categoryDetail.childCount - 1 downTo 2) {
                categoryDetail.addView(View(this).apply { setBackgroundColor(MobileUi.BORDER) }, index + 1,
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        PreferenceRows.categories.forEachIndexed { index, category ->
            categoryIndex.addView(PreferenceRows.row(this, category.title, categoryIcon = category.title, onClick = { showSettingsCategory(index) }))
            val divider = View(this).apply { setBackgroundColor(MobileUi.BORDER) }
            categoryIndex.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        }
        pages.addView(cameraPage)
        pages.addView(connectionsPage)
        pages.addView(settingsPage)
        fun selectPage(page: Int) {
            selectedPage = page
            cameraPage.visibility = if (page == 0) View.VISIBLE else View.GONE
            connectionsPage.visibility = if (page == 1) View.VISIBLE else View.GONE
            settingsPage.visibility = if (page == 2) View.VISIBLE else View.GONE
            compactStatus.visibility = if (page == 0 && workspaceMode != CameraWorkspaceLayout.Mode.SPLIT) View.VISIBLE else View.GONE
            settingsNav.visibility = if (page == 0) View.VISIBLE else View.GONE
            connectionsNav.visibility = if (page == 0) View.VISIBLE else View.GONE
            if (page == 1) connectionsNav.visibility = View.VISIBLE
            if (page == 2) settingsNav.visibility = View.VISIBLE
        }
        selectCameraPage = {
            selectedSettingsCategory = -1
            categoryDetail.visibility = View.GONE
            categoryIndex.visibility = View.VISIBLE
            settingsBack.visibility = View.VISIBLE
            selectPage(0)
        }
        returnToCamera = selectCameraPage
        fun updatePresentation() {
            proToggle.text = if (proMode) "Advanced" else "Simple"
            MobileUi.button(proToggle, selected = proMode)
            drawerTabs.visibility = if (proMode) View.VISIBLE else View.GONE
            if (!proMode) drawer.visibility = View.GONE
            cameraPanel.setSimpleMode(!proMode)
        }
        proToggle.setOnClickListener {
            proMode = !proMode
            proPreference.edit().putBoolean("pro_mode", proMode).apply()
            updatePresentation()
        }
        openCameraControls = {
            if (!proMode) proToggle.performClick()
            selectCameraPage()
            if (drawer.visibility != View.VISIBLE || drawer.getChildAt(0) !== cameraPanel.advancedView)
                drawerTabs.getChildAt(0)?.performClick()
        }
        connectionsNav.setOnClickListener { if (selectedPage == 1) selectCameraPage() else selectPage(1) }
        settingsNav.setOnClickListener { if (selectedPage == 2) selectCameraPage() else selectPage(2) }
        connectionsBack.setOnClickListener { selectCameraPage() }
        settingsBack.setOnClickListener { selectCameraPage() }
        updatePresentation(); selectPage(0)
        if (savedInstanceState != null) {
            selectPage(savedInstanceState.getInt("mobile.selectedPage", 0).coerceIn(0, 2))
            savedInstanceState.getInt("mobile.settingsCategory", -1).takeIf { it in PreferenceRows.categories.indices }?.let(::showSettingsCategory)
        }
        root.addView(pages, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        root.requestApplyInsets()
        selectedMode = CaptureService.Mode.fromName(CameraSettings(this).preferredMode)
        showStats()
    }

    private fun startWithPermission() {
        if (identity.pendingRotation != null) {
            showNotice("Resume the pending key change before starting capture."); return
        }
        if (trustedDesktops.getOrNull(desktops.selectedItemPosition) == null) {
            showNotice("Pair a desktop before starting capture."); return
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
            .putExtra(CaptureService.EXTRA_MODE, selectedMode.name)
            .putExtra(CaptureService.EXTRA_LENS, requestedLens)
            .putExtra(CaptureService.EXTRA_CAMERA, CameraSettings(this).preferredCamera)
            .putExtra(CaptureService.EXTRA_PEER, trustedDesktops.getOrNull(desktops.selectedItemPosition)?.id)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("mobile.selectedPage", selectedPage)
        outState.putInt("mobile.settingsCategory", selectedSettingsCategory)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Use dispatcher back navigation")
    override fun onBackPressed() {
        if (selectedPage != 0) returnToCamera?.invoke() else super.onBackPressed()
    }
    private fun openRotation(role: String) {
        if (service?.stats()?.state?.let { it != "Stopped" } == true) {
            showNotice("Stop capture before changing security keys."); return
        }
        val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition) ?: return
        startActivity(Intent(this, RotationActivity::class.java).putExtra("role", role).putExtra("peer", peer.id))
    }

    private fun openPairing() {
        if (service?.stats()?.state?.let { it != "Stopped" } == true) showNotice("Stop capture before pairing.")
        else startActivity(Intent(this, PairActivity::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWithPermission()
            else showNotice("Camera permission denied. Capture was not started.")
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        refreshDesktops()
        handler.post(refresh)
        handler.post(refreshFocus)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(refreshFocus)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        focusTargetView.update(null)
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
            val names = it.map { peer -> peer.name }.ifEmpty { listOf("No paired desktop") }
            desktops.setPopupBackgroundDrawable(MobileUi.surface(MobileUi.SURFACE, MobileUi.dp(this, 12).toFloat()))
            desktops.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).also {
                        MobileUi.text(it, 16f)
                        it.setPadding(MobileUi.dp(this@MainActivity, 12), MobileUi.dp(this@MainActivity, 8),
                            MobileUi.dp(this@MainActivity, 12), MobileUi.dp(this@MainActivity, 8))
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).also {
                        MobileUi.text(it, 16f)
                        it.setBackgroundColor(MobileUi.SURFACE)
                        val inset = MobileUi.dp(this@MainActivity, 12)
                        it.setPadding(inset, inset, inset, inset)
                    }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val index = it.indexOfFirst { peer -> peer.id == selected }
            if (index >= 0) desktops.setSelection(index)
            trustControls.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
        }.onFailure { trustError = "Protected trust unavailable. Re-pair or restore this app's keys." }
    }
    private fun showStats() {
        updateKeepScreenOn()
        val value = service?.stats() ?: StatsSnapshot()
        if (::cameraPanel.isInitialized) cameraPanel.update(service?.cameraStatus ?: CameraStatus())
        if (::histogramView.isInitialized) histogramView.invalidate()
        if (::previewPlaceholder.isInitialized) {
            previewPlaceholder.visibility = if (value.state == "Capturing") View.GONE else View.VISIBLE
            previewPlaceholder.text = if (value.state == "Stopped") {
                if (trustedDesktops.isEmpty()) "Pair a PC to start the camera stream" else "Start camera to preview"
            } else value.state
        }
        val canonical = service?.cameraStatus ?: CameraStatus()
        val active = value.state in listOf("Starting", "Binding", "Capturing", "Recovering")
        if (::peerButton.isInitialized) {
            val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition)?.name
            peerButton.text = peer?.take(9) ?: "PC"
            peerButton.contentDescription = "Connections · ${peer ?: "not paired"} · ${value.state}"
        }
        if (::consoleDetails.isInitialized) {
            consoleDetails.text = buildString {
                append("${value.state}\n")
                append("PC: ${trustedDesktops.getOrNull(desktops.selectedItemPosition)?.name ?: "Not paired"}\n")
                val mode = value.effectiveMode.ifEmpty { selectedMode.label }
                append("Video: $mode\n")
                if (value.resolution.isNotEmpty()) append("Output: ${value.resolution}\n")
                if (value.codec.isNotEmpty()) append("Encoder: ${value.codec}\n")
                if (value.fps > 0f) append("${String.format(Locale.US, "%.1f", value.fps)} FPS\n")
                append("Network: ${value.network.state}")
                if (value.error.isNotEmpty()) append("\n${value.error}")
            }
        }
        if (active && canonical.mode.isNotEmpty()) selectedMode = CaptureService.Mode.entries.find { it.label == value.requestedMode }
            ?: CaptureService.Mode.fromName(canonical.mode)
        focusBadge.visibility = if (value.state == "Capturing") View.VISIBLE else View.GONE
        focusBadge.text = "Focus: ${canonical.focus} · ${String.format(Locale.US, "%.2f", value.zoomRatio)}×"
        val currentCamera = cameraList.find { it.id == canonical.camera.ifEmpty { CameraSettings(this).preferredCamera } } ?: cameraList.firstOrNull()
        if (::lensChoice.isInitialized) {
            lensChoice.text = "Camera\n${currentCamera?.let { "${it.label.substringBefore(" · ")} · ${it.id}" } ?: "Unavailable"}"
            lensChoice.contentDescription = "Optical camera: ${currentCamera?.label ?: "Unavailable"}"
            lensChoice.isEnabled = cameraList.isNotEmpty() && value.state !in listOf("Starting", "Binding")
            modeChoice.text = "Video\n${if (active) value.effectiveMode.ifEmpty { selectedMode.label } else selectedMode.label}${if (value.state == "Binding") " · Applying" else ""}"
            modeChoice.contentDescription = "Video mode: ${if (active) value.requestedMode else selectedMode.label}"
            modeChoice.isEnabled = currentCamera != null && value.state !in listOf("Starting", "Binding")
            startButton.isEnabled = !active && trustedDesktops.isNotEmpty()
            startButton.alpha = if (startButton.isEnabled) 1f else 0.48f
            startButton.contentDescription = if (startButton.isEnabled) "Start camera" else "Start camera unavailable. Pair a PC first."
            stopButton.isEnabled = active
            startButton.visibility = if (active) View.GONE else View.VISIBLE
            stopButton.visibility = if (active) View.VISIBLE else View.GONE
            connectionPrompt.visibility = View.GONE
            if (workspaceMode == CameraWorkspaceLayout.Mode.SPLIT && !active && trustedDesktops.isEmpty())
                previewPlaceholder.text = "Preview unavailable · pair a PC to start"
            else if (!active) previewPlaceholder.text = "Start camera to preview"
        }
        service?.displayRotation(when (windowManager.defaultDisplay.rotation) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 })
        desktops.isEnabled = !active
        retryConnection.visibility = if (value.state == "Capturing" && value.network.state == "Recovering") View.VISIBLE else View.GONE
        connectionStatus.text = when {
            trustError.isNotEmpty() -> trustError
            runCatching { identity.pendingRotation != null }.getOrDefault(false) -> "Security key change pending. Resume or repair pairing before capture."
            trustedDesktops.isEmpty() -> "Pair your PC with its one-time code or QR."
            else -> ConnectionStatus.message(value, trustedDesktops.getOrNull(desktops.selectedItemPosition)?.name ?: "your PC")
        }
        if (value.state == "Capturing" && value.error.isNotEmpty())
            connectionStatus.text = "${connectionStatus.text}\nControl rejected: ${value.error}"
        val quality = QualityState.current(value)
        if (value.state == "Capturing" && quality.reason != "Selected quality is active.")
            connectionStatus.text = "${connectionStatus.text}\n${quality.reason}"
        if (android.os.SystemClock.elapsedRealtime() < noticeUntil)
            connectionStatus.text = "${connectionStatus.text}\n$notice"
        if (::compactStatus.isInitialized) {
            val peer = trustedDesktops.getOrNull(desktops.selectedItemPosition)?.name
            compactStatus.text = "${peer ?: "Not paired"} · ${value.state}"
            compactStatus.contentDescription = "Compact connection and capture status. ${connectionStatus.text} Capture state: ${value.state}."
        }
        stats.text = """
            ${if (trustError.isNotEmpty()) trustError else ""}
            State: ${value.state}${if (value.error.isNotEmpty()) " — ${value.error}" else ""}
            Requested: ${value.requestedMode}
            Effective mode: ${value.effectiveMode}${if (value.fallbackReason.isNotEmpty()) " — ${value.fallbackReason}" else ""}
            Camera: ${value.camera}
            Encoded resolution: ${value.resolution}
            Encoder: ${value.codec} at ${value.bitrate} bit/s
            Encoded FPS (5 s): ${String.format(Locale.US, "%.1f", value.fps)}
            Frames: ${value.frames}  Keyframes: ${value.keyframes}
            Timestamp: ${value.timestampUs} μs  Gaps >50 ms: ${value.timestampGaps}  Duplicate PTS: ${value.duplicateTimestamps}
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

    private fun updateKeepScreenOn() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (service?.shouldKeepScreenOn() == true) window.addFlags(flag) else window.clearFlags(flag)
    }

    companion object {
        private const val CAMERA_REQUEST = 1
        private const val NOTIFICATION_REQUEST = 2
    }
    private fun showNotice(message: String) {
        notice = message
        noticeUntil = android.os.SystemClock.elapsedRealtime() + 5000
        connectionStatus.text = message
    }
    private fun attachPreview() {
        if (::previewView.isInitialized && previewView.holder.surface.isValid)
            service?.attachPreview(previewView.holder.surface, previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1))
    }
}
