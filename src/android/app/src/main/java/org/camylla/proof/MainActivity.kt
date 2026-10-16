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
    private lateinit var retryConnection: Button
    private lateinit var resumeKeyChange: Button
    private var trustedDesktops: List<TrustedDesktop> = emptyList()
    private val identity get() = ProductIdentity(this)
    private var trustError = ""
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
        connectionStatus = TextView(this).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); MobileUi.text(this, 15f, true) }
        connections.addView(TextView(this).apply { text = "Connections"; MobileUi.text(this, 24f) })
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
        connections.addView(connectionManagement)
        layout.addView(TextView(this).apply { text = "Settings & security"; MobileUi.text(this, 24f) })
        layout.addView(TextView(this).apply { text = "Manage security keys and inspect connection diagnostics."; MobileUi.text(this, 15f, true) })
        layout.addView(settings)
        stats = TextView(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); MobileUi.text(this, 14f, true) }
        layout.addView(TextView(this).apply { text = "Diagnostics"; MobileUi.text(this, 20f) })
        layout.addView(stats)
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
        titleRow.addView(TextView(this@MainActivity).apply { text = "CAMYLLA"; letterSpacing = .12f; MobileUi.text(this, 20f) }, LinearLayout.LayoutParams(0, -2, 1f))
        val proPreference = getSharedPreferences("mobile-ui", Context.MODE_PRIVATE)
        var proMode = proPreference.getBoolean("pro_mode", false)
        val proToggle = Button(this).apply { MobileUi.button(this) }
        titleRow.addView(proToggle)
        header.addView(titleRow)
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cameraNav = Button(this).apply { text = "Camera"; MobileUi.button(this) }
        val connectionsNav = Button(this).apply { text = "Connections"; MobileUi.button(this) }
        val settingsNav = Button(this).apply { text = "Settings"; MobileUi.button(this) }
        listOf(cameraNav, connectionsNav, settingsNav).forEach {
            navigation.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        header.addView(navigation)
        root.addView(header)
        root.addView(connectionStatus)
        val body = LinearLayout(this).apply {
            orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
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
        controls.addView(selections)
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
        tab("Pro controls", cameraPanel.advancedView)
        controls.addView(drawerTabs)
        controls.addView(drawer, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.22f).toInt()))
        if (body.orientation == LinearLayout.HORIZONTAL) {
            body.addView(previewFrame, LinearLayout.LayoutParams(0, -1, 1f))
            val panelWidth = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.4f).toInt())
            body.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(panelWidth, -1))
        } else {
            body.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
            body.addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        val cameraPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cameraPage.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "Start camera"; MobileUi.button(this, prominent = true); setOnClickListener { startWithPermission() } }
        stopButton = Button(this).apply { text = "Stop camera"; MobileUi.button(this, destructive = true); setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) } }
        retryConnection = Button(this).apply { text = "Retry now"; MobileUi.button(this); setOnClickListener { service?.retryConnection() } }
        actions.addView(startButton, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        actions.addView(retryConnection, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        actions.addView(stopButton, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }); cameraPage.addView(actions)
        val pages = FrameLayout(this)
        val connectionsPage = ScrollView(this).apply { addView(connections); visibility = View.GONE }
        val settingsPage = ScrollView(this).apply { addView(layout); visibility = View.GONE }
        pages.addView(cameraPage)
        pages.addView(connectionsPage)
        pages.addView(settingsPage)
        fun selectPage(page: Int) {
            cameraPage.visibility = if (page == 0) View.VISIBLE else View.GONE
            connectionsPage.visibility = if (page == 1) View.VISIBLE else View.GONE
            settingsPage.visibility = if (page == 2) View.VISIBLE else View.GONE
            listOf(cameraNav, connectionsNav, settingsNav).forEachIndexed { index, view ->
                MobileUi.button(view, selected = index == page)
            }
        }
        fun updatePresentation() {
            proToggle.text = if (proMode) "Pro" else "Simple"
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
        cameraNav.setOnClickListener { selectPage(0) }
        connectionsNav.setOnClickListener { selectPage(1) }
        settingsNav.setOnClickListener { selectPage(2) }
        updatePresentation(); selectPage(0)
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
            previewPlaceholder.text = if (value.state == "Stopped") "Start camera to preview" else value.state
        }
        val canonical = service?.cameraStatus ?: CameraStatus()
        val active = value.state in listOf("Starting", "Binding", "Capturing")
        if (active && canonical.mode.isNotEmpty()) selectedMode = CaptureService.Mode.fromName(canonical.mode)
        focusBadge.visibility = if (value.state == "Capturing") View.VISIBLE else View.GONE
        focusBadge.text = "Focus: ${canonical.focus} · ${String.format(Locale.US, "%.2f", value.zoomRatio)}×"
        val currentCamera = cameraList.find { it.id == canonical.camera.ifEmpty { CameraSettings(this).preferredCamera } } ?: cameraList.firstOrNull()
        if (::lensChoice.isInitialized) {
            lensChoice.text = "Camera\n${currentCamera?.let { "${it.label.substringBefore(" · ")} · ${it.id}" } ?: "Unavailable"}"
            lensChoice.contentDescription = "Optical camera: ${currentCamera?.label ?: "Unavailable"}"
            lensChoice.isEnabled = cameraList.isNotEmpty() && value.state !in listOf("Starting", "Binding")
            modeChoice.text = "Video\n${if (selectedMode == CaptureService.Mode.HD) "720p" else "1080p"} · 30 FPS${if (value.state == "Binding") " · Applying" else ""}"
            modeChoice.contentDescription = "Video mode: ${if (active) value.requestedMode else selectedMode.label}"
            modeChoice.isEnabled = currentCamera != null && value.state !in listOf("Starting", "Binding")
            startButton.isEnabled = !active && trustedDesktops.isNotEmpty()
            stopButton.isEnabled = active
            startButton.visibility = if (active) View.GONE else View.VISIBLE
            stopButton.visibility = if (active) View.VISIBLE else View.GONE
            connectionPrompt.visibility = if (trustedDesktops.isEmpty() && !active) View.VISIBLE else View.GONE
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
