package org.camylla.proof

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.Executor

class CaptureService : LifecycleService() {
    inner class LocalBinder : Binder() { val service: CaptureService get() = this@CaptureService }
    private val binder = LocalBinder()
    private val cameraExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    @Volatile private var encoder: AvcEncoder? = null
    private var networkSender: NetworkSender? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var mode = Mode.HD
    @Volatile private var generation = 0
    private var sessionStartMs = 0L
    private var fileNumber = 0
    private var rotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null
    private var thermalManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var snapshot = StatsSnapshot()

    fun stats(): StatsSnapshot = snapshot.copy(
        elapsedSeconds = if (sessionStartMs == 0L) 0 else (SystemClock.elapsedRealtime() - sessionStartMs) / 1000,
        network = networkSender?.stats() ?: NetworkProgress(),
    )

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera capture proof", NotificationManager.IMPORTANCE_LOW),
        )
        thermalManager = getSystemService(PowerManager::class.java)
        thermalListener = PowerManager.OnThermalStatusChangedListener { value ->
            snapshot = snapshot.copy(thermalStatus = value)
            Log.i(TAG, "Thermal status=$value")
        }.also { thermalManager?.addThermalStatusListener(it) }
        snapshot = snapshot.copy(thermalStatus = thermalManager?.currentThermalStatus ?: -1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                if (sessionStartMs != 0L) return Service.START_NOT_STICKY
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    fail("Camera permission is required")
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
                mode = Mode.fromName(intent.getStringExtra(EXTRA_MODE))
                lens = intent.getIntExtra(EXTRA_LENS, CameraSelector.LENS_FACING_BACK)
                try {
                    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                } catch (error: Exception) {
                    fail("Camera foreground service: ${error.message}")
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
                sessionStartMs = SystemClock.elapsedRealtime()
                snapshot = StatsSnapshot(state = "Starting", requestedMode = mode.label, thermalStatus = thermalManager?.currentThermalStatus ?: -1)
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
                val pin = intent.getStringExtra(EXTRA_PIN).orEmpty().trim()
                val secret = intent.getStringExtra(EXTRA_SECRET).orEmpty().trim()
                if (host.isNotEmpty() || pin.isNotEmpty() || secret.isNotEmpty()) {
                    try {
                        networkSender = NetworkSender(host, pin, secret, mode.label,
                            if (lens == CameraSelector.LENS_FACING_BACK) "rear" else "front") {
                            encoder?.requestSyncFrame()
                        }
                    } catch (error: Exception) {
                        fail("Network setup: ${error.message}")
                        endSession()
                        stopSelf()
                        return Service.START_NOT_STICKY
                    }
                }
                orientationListener = object : OrientationEventListener(this) {
                    override fun onOrientationChanged(degrees: Int) {
                        if (degrees == ORIENTATION_UNKNOWN) return
                        val next = when (degrees) {
                            in 45..134 -> Surface.ROTATION_270
                            in 135..224 -> Surface.ROTATION_180
                            in 225..314 -> Surface.ROTATION_90
                            else -> Surface.ROTATION_0
                        }
                        if (next != rotation) {
                            rotation = next
                            preview?.targetRotation = next
                        }
                    }
                }.also { it.enable() }
                ProcessCameraProvider.getInstance(this).let { future ->
                    future.addListener({
                        try {
                            provider = future.get()
                            if (sessionStartMs != 0L) bindCamera()
                        } catch (error: Exception) { fail("CameraX initialization: ${error.message}") }
                    }, cameraExecutor)
                }
            }
            ACTION_STOP -> {
                endSession()
                stopSelf()
            }
        }
        return Service.START_NOT_STICKY
    }

    fun switchCamera() {
        if (sessionStartMs == 0L) return
        if (networkSender != null) {
            snapshot = snapshot.copy(error = "Stop and restart to negotiate a different lens")
            return
        }
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    fun setMode(value: Mode) {
        if (sessionStartMs == 0L || mode == value) return
        if (networkSender != null) {
            snapshot = snapshot.copy(error = "Stop and restart to negotiate a different mode")
            return
        }
        mode = value
        bindCamera()
    }

    fun setZoom(linear: Float) {
        val active = camera ?: return
        active.cameraControl.setLinearZoom(linear.coerceIn(0f, 1f)).addListener({
            if (camera === active) snapshot = snapshot.copy(zoomRatio = active.cameraInfo.zoomState.value?.zoomRatio ?: 1f)
        }, cameraExecutor)
    }

    private fun bindCamera() {
        val currentProvider = provider ?: return
        val token = ++generation
        preview?.let { currentProvider.unbind(it) }
        preview = null
        camera = null
        encoder?.close()
        encoder = null
        networkSender?.newEncoder()
        snapshot = StatsSnapshot(state = "Binding", requestedMode = mode.label, thermalStatus = thermalManager?.currentThermalStatus ?: -1)
        try {
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            if (!currentProvider.hasCamera(selector)) throw IllegalStateException("Requested lens is unavailable")
            val cameraInfo = selector.filter(currentProvider.availableCameraInfos).first()
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val afModes = camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afMode = when {
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> null
            }
            val builder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(mode.size, ResolutionStrategy.FALLBACK_RULE_NONE)).build())
                .setTargetFrameRate(Range(30, 30))
                .setTargetRotation(rotation)
            if (afMode != null) {
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, afMode,
                )
            }
            val useCase = builder.build()
            useCase.setSurfaceProvider(cameraExecutor) { request ->
                if (token != generation || sessionStartMs == 0L) {
                    request.willNotProvideSurface()
                    return@setSurfaceProvider
                }
                try {
                    val size = request.resolution
                    if (size != mode.size) throw IllegalStateException("CameraX chose ${size.width}x${size.height} for ${mode.label}")
                    encoder?.close()
                    encoder = null
                    val file = nextFile()
                    val activeEncoder = AvcEncoder(size.width, size.height, file,
                        onUpdate = { progress ->
                            if (token == generation) snapshot = snapshot.copy(
                                frames = progress.frames, keyframes = progress.keyframes, fps = progress.fps,
                                timestampUs = progress.timestampUs, timestampGaps = progress.timestampGaps,
                                sinkBytes = progress.sinkBytes, sinkLimitReached = progress.sinkLimitReached,
                            )
                        },
                        onError = { message -> if (token == generation) fail(message) },
                        onCodecConfig = { bytes -> if (token == generation) networkSender?.codecConfig(bytes) },
                        onAccessUnit = { bytes, ptsUs, key ->
                            if (token == generation) networkSender?.offer(bytes, ptsUs, key)
                        },
                    )
                    encoder = activeEncoder
                    snapshot = snapshot.copy(
                        state = "Capturing", resolution = "${size.width}x${size.height}",
                        codec = activeEncoder.codecName, bitrate = activeEncoder.configuredBitrate,
                        outputFile = file.absolutePath,
                    )
                    request.setTransformationInfoListener(cameraExecutor) { info ->
                        if (token == generation) {
                            snapshot = snapshot.copy(rotationDegrees = info.rotationDegrees)
                            Log.i(TAG, "CameraX transform rotation=${info.rotationDegrees} crop=${info.cropRect}")
                        }
                    }
                    request.provideSurface(activeEncoder.surface, cameraExecutor) { result ->
                        Log.i(TAG, "Surface result=${result.resultCode}")
                    }
                } catch (error: Exception) {
                    request.willNotProvideSurface()
                    encoder?.close()
                    encoder = null
                    fail("Surface/encoder: ${error.message}")
                }
            }
            preview = useCase
            camera = currentProvider.bindToLifecycle(this, selector, useCase)
            snapshot = snapshot.copy(
                camera = "${if (lens == CameraSelector.LENS_FACING_BACK) "rear" else "front"} (${camera2Info.cameraId})",
                autofocus = when (afMode) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous video requested"
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous picture requested"
                    else -> "continuous AF unavailable"
                },
                zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f,
            )
            Log.i(TAG, "Bound ${snapshot.camera} mode=${mode.label} AF=${snapshot.autofocus}")
        } catch (error: Exception) {
            fail("CameraX binding: ${error.message}")
        }
    }

    private fun nextFile(): File {
        val directory = File(cacheDir, "diagnostics").apply { mkdirs() }
        directory.listFiles { file -> file.extension == "h264" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
        return File(directory, "capture-${System.currentTimeMillis()}-${++fileNumber}.h264")
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        snapshot = snapshot.copy(state = "Error", error = message)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camylla camera proof active")
            .setContentText("H.264 capture / M3 transport proof")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop,
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun endSession() {
        if (sessionStartMs == 0L) return
        generation++
        orientationListener?.disable()
        orientationListener = null
        preview?.let { provider?.unbind(it) }
        preview = null
        camera = null
        encoder?.close()
        encoder = null
        networkSender?.close()
        networkSender = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        sessionStartMs = 0L
        snapshot = snapshot.copy(state = "Stopped")
    }

    override fun onDestroy() {
        endSession()
        thermalListener?.let { thermalManager?.removeThermalStatusListener(it) }
        thermalListener = null
        super.onDestroy()
    }

    enum class Mode(val label: String, val size: Size) {
        HD("1280x720@30", Size(1280, 720)), FHD("1920x1080@30", Size(1920, 1080));
        companion object { fun fromName(name: String?): Mode = entries.firstOrNull { it.name == name } ?: HD }
    }

    companion object {
        private const val TAG = "CamyllaProof"
        private const val CHANNEL = "camylla_capture"
        private const val NOTIFICATION_ID = 13
        const val ACTION_START = "org.camylla.proof.START"
        const val ACTION_STOP = "org.camylla.proof.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LENS = "lens"
        const val EXTRA_HOST = "host"
        const val EXTRA_PIN = "pin"
        const val EXTRA_SECRET = "secret"
    }
}
