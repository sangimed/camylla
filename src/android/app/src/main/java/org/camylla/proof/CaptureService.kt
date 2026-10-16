package org.camylla.proof

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.File

class CaptureService : LifecycleService() {
    inner class LocalBinder : Binder() { val service: CaptureService get() = this@CaptureService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { CameraSettings(this) }
    private var engine: CameraEngine? = null
    @Volatile private var encoder: AvcEncoder? = null
    @Volatile private var networkSender: NetworkSender? = null
    private var entry: CameraEntry? = null
    private var mode = Mode.HD
    @Volatile private var generation = 0
    private var sessionStartMs = 0L
    private var previewSurface: Surface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var displayDegrees = 0
    private var thermalManager: PowerManager? = null
    private var captureWakeLock: PowerManager.WakeLock? = null
    private var cameraSuspended = false
    private val resumeCamera = Runnable {
        if (cameraSuspended && sessionStartMs != 0L && !phoneLocked()) {
            cameraSuspended = false
            bindCamera(settings.values(entry!!.id, mode.name))
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // SCREEN_ON can precede keyguard dismissal; USER_PRESENT retries after it.
            main.removeCallbacks(resumeCamera)
            main.post(resumeCamera)
        }
    }
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var snapshot = StatsSnapshot()
    @Volatile internal var cameraStatus = CameraStatus()
        private set
    private var lastStatusSent = 0L
    private var lastQualitySent = 0L
    private var advertisedSession: String? = null
    private var advertisedGeneration = 0
    private var lastRemoteId = 0L
    private var remoteSessionId = ""
    private var captureAuthorization = ""
    private var capturePeer = ""
    internal fun cameras(): List<CameraEntry> = CameraCatalog.list(getSystemService(CameraManager::class.java))
    fun stats(): StatsSnapshot = snapshot.copy(elapsedSeconds = if (sessionStartMs == 0L) 0 else (SystemClock.elapsedRealtime() - sessionStartMs) / 1000,
        network = networkSender?.stats() ?: NetworkProgress())
    fun retryConnection() { if (sessionStartMs != 0L) networkSender?.retryNow() }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Camera capture", NotificationManager.IMPORTANCE_LOW))
        thermalManager = getSystemService(PowerManager::class.java)
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        thermalListener = PowerManager.OnThermalStatusChangedListener { snapshot = snapshot.copy(thermalStatus = it) }.also { thermalManager?.addThermalStatusListener(it) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                if (sessionStartMs != 0L) return Service.START_NOT_STICKY
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail("Camera permission required"); stopSelf(); return Service.START_NOT_STICKY }
                try {
                    val identity = ProductIdentity(this)
                    require(identity.pendingRotation == null) { "Resume the pending key change first" }
                    val peerId = intent.getStringExtra(EXTRA_PEER).orEmpty()
                    val peer = identity.desktops().singleOrNull { it.id == peerId && !it.revoked } ?: error("Pair a desktop first")
                    val available = cameras()
                    val requestedId = intent.getStringExtra(EXTRA_CAMERA) ?: settings.preferredCamera
                    entry = available.find { it.id == requestedId } ?: available.find {
                        it.facing == if (intent.getIntExtra(EXTRA_LENS, CameraSelector.LENS_FACING_BACK) == CameraSelector.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
                    } ?: available.firstOrNull() ?: error("No camera exposes a supported 30 FPS mode")
                    mode = Mode.fromName(intent.getStringExtra(EXTRA_MODE) ?: settings.preferredMode)
                    require(mode in entry!!.modes) { "Selected mode unavailable on this lens" }
                    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                    // A foreground service does not keep custom encoder/network threads
                    // runnable after screen-off. The explicit session owns this CPU lease.
                    @Suppress("WakelockTimeout")
                    captureWakeLock = thermalManager!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "Camylla:camera-session").apply { setReferenceCounted(false); acquire() }
                    sessionStartMs = SystemClock.elapsedRealtime()
                    captureAuthorization = java.util.UUID.randomUUID().toString(); capturePeer = peer.id
                    snapshot = StatsSnapshot(state = "Starting", requestedMode = mode.label, thermalStatus = thermalManager?.currentThermalStatus ?: -1)
                    networkSender = NetworkSender(identity, peer, mode.label, facing(), { encoder?.requestSyncFrame() },
                        { id -> main.post { if (networkSender?.currentSession() == id) {
                            advertisedSession = null
                            if (remoteSessionId != id) { remoteSessionId = id; lastRemoteId = 0 }
                            publishNetwork(true)
                        } } },
                        { message, reply -> main.post { remote(message, reply) } },
                        { main.post { if (sessionStartMs != 0L) { endSession(); stopSelf() } } })
                    settings.preferredPeer = peer.id
                    bindCamera(settings.values(entry!!.id, mode.name))
                } catch (error: Exception) { endSession(); fail(error.message ?: "Capture setup failed"); stopSelf() }
            }
            ACTION_STOP -> { endSession(); stopSelf() }
        }
        return Service.START_NOT_STICKY
    }
    private fun facing() = if (entry?.facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "rear"
    fun switchCamera() { val list = cameras(); val index = list.indexOfFirst { it.id == entry?.id }; if (list.isNotEmpty()) selectCamera(list[(index + 1) % list.size].id) }
    internal fun selectCamera(id: String, done: (Double?, String?) -> Unit = { _, _ -> }) {
        if (sessionStartMs == 0L) { done(null, "Capture stopped"); return }
        val selected = cameras().find { it.id == id } ?: run { done(null, "Camera unavailable"); return }
        if (mode !in selected.modes) { done(null, "Selected video mode unavailable on this lens"); snapshot = snapshot.copy(error = "Choose a compatible mode before changing lens"); return }
        saveSettings(); entry = selected; bindCamera(settings.values(id, mode.name), done)
    }
    fun setMode(value: Mode) { changeMode(value) }
    private fun changeMode(value: Mode, done: (Double?, String?) -> Unit = { _, _ -> }) {
        if (sessionStartMs == 0L) { done(null, "Capture stopped"); return }
        if (value !in entry!!.modes) { done(null, "Mode unavailable on this lens"); return }
        saveSettings(); mode = value; bindCamera(settings.values(entry!!.id, mode.name), done)
    }
    fun setZoom(linear: Float) { val o = cameraStatus.options.find { it.key == "zoom" } ?: return; control("zoom", o.min + linear.coerceIn(0f, 1f) * (o.max - o.min)) }
    internal fun control(key: String, value: Double, done: (Double?, String?) -> Unit = { _, _ -> }) {
        engine?.command(key, value) { actual, error -> main.post {
            if (error != null) snapshot = snapshot.copy(error = error) else { snapshot = snapshot.copy(error = ""); saveSettings() }
            done(actual, error)
        } } ?: done(null, "Capture stopped")
    }
    internal fun tapFocus(x: Double, y: Double, width: Int, height: Int, done: (String?) -> Unit = {}) {
        engine?.tap(x, y, width, height) { _, error -> main.post { done(error) } } ?: done("Camera not ready")
    }
    internal fun clearFocusFeedback() { engine?.clearFocusFeedback() }
    internal fun attachPreview(surface: Surface?, width: Int, height: Int) { previewSurface = surface; previewWidth = width; previewHeight = height; engine?.attachPreview(surface, width, height) }
    internal fun displayRotation(degrees: Int) { displayDegrees = degrees; engine?.displayRotation(degrees) }
    internal fun resetSettings() { val current = entry ?: return; settings.reset(current.id, mode.name); bindCamera(emptyMap()) }
    internal fun applyPreset(name: String) {
        val raw = settings.presets()[name] ?: return
        try {
            val json = JSONObject(raw); val target = cameras().find { it.id == json.getString("camera") } ?: error("Preset camera unavailable")
            val targetMode = Mode.fromName(json.getString("mode")); require(targetMode in target.modes) { "Preset mode unavailable" }
            saveSettings(); entry = target; mode = targetMode; bindCamera(CameraSettings.decode(json.getJSONObject("values").toString()))
        } catch (error: Exception) { snapshot = snapshot.copy(error = "Preset rejected: ${error.message}") }
    }
    internal fun savePreset(name: String) { val current = entry ?: return; settings.savePreset(name, current.id, mode.name, cameraStatus.applied) }
    private fun saveSettings() { val current = entry ?: return; if (cameraStatus.camera == current.id && cameraStatus.mode == mode.name) settings.save(current.id, mode.name, cameraStatus.applied) }
    private fun bindCamera(saved: Map<String, Double>, done: (Double?, String?) -> Unit = { _, _ -> }) {
        if (sessionStartMs == 0L) return
        val current = entry ?: return; val token = ++generation
        networkSender?.reconfigure(mode.label, facing(), token)
        engine?.close(); engine = null; encoder?.close(); encoder = null
        cameraStatus = CameraStatus(camera = current.id, mode = mode.name, generation = token, message = "Changing camera")
        snapshot = snapshot.copy(state = "Binding", requestedMode = mode.label, camera = current.label, error = "")
        advertisedGeneration = 0
        try {
            val file = File(File(cacheDir, "diagnostics").apply { mkdirs() }, "capture-${System.currentTimeMillis()}.h264")
            file.parentFile?.listFiles { f -> f.extension == "h264" }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            val activeEncoder = AvcEncoder(mode.size.width, mode.size.height, file,
                onUpdate = { p -> if (token == generation) {
                    val active = encoder
                    if (active != null) {
                        val underPressure = networkSender?.stats()?.state == "Recovering" ||
                            snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                        val wanted = if (underPressure) active.selectedBitrate * 3 / 4 else active.selectedBitrate
                        active.setBitrate(wanted)
                        snapshot = snapshot.copy(bitrate = active.configuredBitrate, selectedBitrate = active.selectedBitrate)
                    }
                    snapshot = snapshot.copy(frames = p.frames, keyframes = p.keyframes, fps = p.fps, timestampUs = p.timestampUs, timestampGaps = p.timestampGaps, sinkBytes = p.sinkBytes, sinkLimitReached = p.sinkLimitReached)
                    publishNetwork()
                } },
                onError = { error -> main.post { if (token == generation) { endSession(); fail(error) } } },
                onCodecConfig = { bytes -> if (token == generation) networkSender?.codecConfig(bytes) },
                onAccessUnit = { bytes, ptsUs, key -> if (token == generation) networkSender?.offer(bytes, ptsUs, key) })
            encoder = activeEncoder
            engine = CameraEngine(this, current, mode, token, CameraControlState.Binding(capturePeer,captureAuthorization,token.toLong()),activeEncoder.surface, saved,
                { state -> main.post { if (token == generation && sessionStartMs != 0L) {
                    cameraStatus = state; snapshot = snapshot.copy(autofocus = state.focus, zoomRatio = (state.applied["zoom"] ?: 1.0).toFloat(), rotationDegrees = state.previewRotation); publishNetwork()
                } } },
                { error -> main.post { if (token == generation) {
                    done(null, error)
                    if (sessionStartMs != 0L && phoneLocked()) suspendCamera(error)
                    else { endSession(); fail(error) }
                } } },
                { main.post { if (token == generation && sessionStartMs != 0L) {
                    snapshot = snapshot.copy(state = "Capturing", resolution = "${mode.size.width}x${mode.size.height}", codec = activeEncoder.codecName, bitrate = activeEncoder.configuredBitrate, selectedBitrate = activeEncoder.selectedBitrate, outputFile = file.absolutePath)
                    settings.preferredCamera = current.id; settings.preferredMode = mode.name
                    done(cameras().indexOfFirst { it.id == current.id }.toDouble(), null); publishNetwork(true)
                } } })
            engine?.attachPreview(previewSurface, previewWidth, previewHeight); engine?.displayRotation(displayDegrees)
        } catch (error: Exception) { done(null, error.message); endSession(); fail("Camera configuration: ${error.message}") }
    }
    private fun publishNetwork(force: Boolean = false) {
        val sender = networkSender ?: return; val id = sender.currentSession() ?: return; val state = cameraStatus
        if (state.options.isEmpty() || sender.readyCameraGeneration() != state.generation) return
        if (advertisedSession != id || advertisedGeneration != state.generation) {
            advertisedSession = id; advertisedGeneration = state.generation
            val cameras = cameras()
            val cameraOption = CameraOption("camera", "Optical camera selection", 0.0, (cameras.size - 1).toDouble(), choices = cameras.mapIndexed { i, c -> i to c.label }.toMap())
            val modeOption = CameraOption("mode", "Video mode", 0.0, 1.0, choices = entry!!.modes.associate { it.ordinal to it.label })
            for (option in listOf(cameraOption, modeOption) + state.options) sender.cameraRecord(JSONObject().put("type", "camera_capability").put("generation", state.generation).put("option", option.json()))
        }
        if (force || SystemClock.elapsedRealtime() - lastStatusSent >= 250) {
            lastStatusSent = SystemClock.elapsedRealtime()
            val applied = state.applied + mapOf("camera" to cameras().indexOfFirst { it.id == state.camera }.toDouble(), "mode" to mode.ordinal.toDouble())
            sender.cameraRecord(JSONObject().put("type", "camera_state").put("generation", state.generation).put("applied", JSONObject(applied))
                .put("pending", org.json.JSONArray(state.pending.toList())).put("focus", state.focus).put("message", state.message))
        }
        if (force || SystemClock.elapsedRealtime() - lastQualitySent >= 1_000) {
            lastQualitySent = SystemClock.elapsedRealtime()
            val quality = QualityState.current(stats())
            sender.cameraRecord(JSONObject().put("type", "quality_state").put("generation", state.generation)
                .put("selected_mode", quality.selectedMode).put("effective_mode", quality.effectiveMode)
                .put("selected_bitrate", quality.selectedBitrate).put("effective_bitrate", quality.effectiveBitrate)
                .put("delivered_fps", quality.deliveredFps).put("network_pressure", quality.networkPressure)
                .put("thermal_pressure", quality.thermalPressure).put("reason", quality.reason))
        }
    }
    private fun remote(message: JSONObject, reply: (JSONObject) -> Unit) {
        val id = message.optLong("request_id", -1); val oldGeneration = message.optInt("generation", -1)
        val key = if (message.optString("type") == "camera_focus") "focusPoint" else message.optString("control")
        if (message.optString("type") == "camera_cancel") {
            if (message.optString("session_id") == networkSender?.currentSession() && oldGeneration == generation && id > 0) engine?.cancel(id)
            return
        }
        fun respond(value: Double?, error: String?) { reply(JSONObject().put("type", "camera_result").put("request_id", id).put("generation", oldGeneration)
            .put("control", key).put("applied", value ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)) }
        if (message.optString("session_id") != networkSender?.currentSession() || oldGeneration != generation || sessionStartMs == 0L) { respond(null, "Obsolete session or generation"); return }
        if (id <= lastRemoteId || id <= 0) { respond(null, "Duplicate or out-of-order request"); return }; lastRemoteId = id
        if (message.optString("type") == "camera_focus") {
            val x = runCatching { message.getDouble("x") }.getOrDefault(Double.NaN)
            val y = runCatching { message.getDouble("y") }.getOrDefault(Double.NaN)
            if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) { respond(null,"Invalid focus coordinates"); return }
            engine?.tap(x * mode.size.width, y * mode.size.height, mode.size.width, mode.size.height, id, true)
                { actual,error -> main.post { respond(actual,error) } } ?: respond(null,"Capture stopped")
            return
        }
        val value = runCatching { message.getDouble("value") }.getOrDefault(Double.NaN)
        if (key == "camera" || key == "mode") {
            if (!value.isFinite() || value != value.toInt().toDouble()) { respond(null, "Invalid choice"); return }
            if (key == "camera") {
                val selected = cameras().getOrNull(value.toInt()) ?: run { respond(null, "Camera unavailable"); return }
                selectCamera(selected.id) { actual, error -> respond(actual, error) }
            } else {
                val selected = Mode.entries.getOrNull(value.toInt()) ?: run { respond(null, "Mode unavailable"); return }
                changeMode(selected) { _, error -> respond(if (error == null) value else null, error) }
            }
        } else engine?.command(key, value, id) { actual, error -> main.post {
            if (error == null) saveSettings()
            respond(actual, error)
        } } ?: respond(null, "Capture stopped")
    }
    private fun fail(message: String) { android.util.Log.e("Camylla", message); snapshot = snapshot.copy(state = "Error", error = message) }
    private fun phoneLocked() = thermalManager?.isInteractive != true ||
        getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun suspendCamera(reason: String) {
        // Camera eviction is not an explicit Stop: keep trust, authorization and the
        // one network worker. Invalidate callbacks before releasing camera resources.
        saveSettings(); generation++; cameraSuspended = true
        engine?.close(); engine = null; encoder?.close(); encoder = null
        networkSender?.reconfigure(mode.label, facing(), generation)
        cameraStatus = CameraStatus(camera = entry!!.id, mode = mode.name, generation = generation,
            message = "Camera paused while phone locked; unlock to resume")
        snapshot = snapshot.copy(state = "Binding", error = reason)
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Camylla camera active").setContentText("Tap to adjust camera controls")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build()).build()
    }
    private fun endSession() {
        cameraSuspended = false; main.removeCallbacks(resumeCamera)
        generation++; saveSettings(); sessionStartMs = 0
        captureAuthorization = ""; capturePeer = ""
        engine?.close(); engine = null; encoder?.close(); encoder = null; networkSender?.close(); networkSender = null
        captureWakeLock?.let { if (it.isHeld) it.release() }; captureWakeLock = null
        cameraStatus = CameraStatus(message = "Capture stopped; light released")
        stopForeground(Service.STOP_FOREGROUND_REMOVE); snapshot = snapshot.copy(state = "Stopped")
    }
    override fun onDestroy() { endSession(); unregisterReceiver(screenReceiver); thermalListener?.let { thermalManager?.removeThermalStatusListener(it) }; super.onDestroy() }
    enum class Mode(val label: String, val size: Size) {
        HD("1280x720@30", Size(1280,720)), FHD("1920x1080@30", Size(1920,1080));
        companion object { fun fromName(name: String?): Mode = entries.find { it.name == name } ?: HD }
    }
    companion object {
        private const val CHANNEL = "camylla_capture"; private const val NOTIFICATION_ID = 13
        const val ACTION_START = "org.camylla.proof.START"; const val ACTION_STOP = "org.camylla.proof.STOP"
        const val EXTRA_MODE = "mode"; const val EXTRA_LENS = "lens"; const val EXTRA_PEER = "peer"; const val EXTRA_CAMERA = "camera"
    }
}
