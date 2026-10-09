package org.camylla.proof

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CaptureRequest as R
import android.hardware.camera2.CaptureResult as V
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Rational
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class CameraStatus(val camera: String = "", val mode: String = "", val generation: Int = 0,
    val options: List<CameraOption> = emptyList(), val applied: Map<String, Double> = emptyMap(),
    val pending: Set<String> = emptySet(), val focus: String = "Unavailable", val message: String = "",
    val histogram: IntArray = IntArray(64), val previewRotation: Int = 0,
    val focusTarget: FocusFeedback.Target? = null)

/** Camera2 owns sensor controls and capture-result confirmation. GL owns explicitly labelled
 * software adjustments and independent output transforms; overlays never enter the encoder.
 */
internal class CameraEngine(context: Context, val entry: CameraEntry, val mode: CaptureService.Mode,
    val generation: Int, private val binding: CameraControlState.Binding, encoderSurface: Surface, saved: Map<String, Double>,
    private val onStatus: (CameraStatus) -> Unit, private val onFatal: (String) -> Unit,
    private val onReady: () -> Unit) : AutoCloseable {
    private data class Pending(val id: Long, val revision: Long, val value: Double, val deadline: Long, val owner: Long?,
        val completion: (Double?, String?) -> Unit)
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camylla-camera-$generation").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var renderer: CameraRenderer? = null
    private val options = entry.options(mode).associateBy { it.key }.toMutableMap()
    private val commands = CameraControlState().apply { begin(binding, options.mapValues { (_,o) ->
        if (o.reason != null) CameraControlState.Capability(null,null,o.reason) else CameraControlState.Capability(o.min,o.max,null)
    }) }
    private val desired = defaults(entry).toMutableMap()
    private val applied = mutableMapOf<String, Double>()
    private val pending = mutableMapOf<String, Pending>()
    private var revision = 0L
    private var requestId = 0L
    private var lastResult: V? = null
    private var focusRegion: MeteringRectangle? = null
    private var tappedFocus = false
    private var focusDeadline = 0L
    private var focus = "Starting"
    private val focusFeedback = FocusFeedback()
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var message = ""
    private var ready = false
    private var failed = false
    @Volatile private var closed = false
    @Volatile var status = CameraStatus()
        private set
    private val software = setOf("brightness", "contrast", "saturation", "outputRotation", "outputMirror", "previewRotation", "previewMirror", "orientationLock", "grid", "zebras", "peaking", "histogram")
    init {
        for ((key, value) in saved) {
            if (key in CameraSettings.VOLATILE) continue
            val reason = options[key]?.validate(value) ?: if (options.containsKey(key)) null else "Unknown setting"
            if (reason == null) desired[key] = value else message += "$key reset: $reason. "
        }
        // Locks and torch are never restored, including reconfiguration within an active capture.
        desired["torch"] = 0.0; desired["torchStrength"] = 1.0
        desired["focusLock"] = 0.0; desired["aeLock"] = 0.0; desired["wbLock"] = 0.0
        handler.post {
            try {
                renderer = CameraRenderer(mode.size.width, mode.size.height, encoderSurface, ::rendered, ::fatal).also { r ->
                    r.values = desired.toMap()
                    r.previewRotation = desired.getValue("previewRotation").toInt()
                    r.inputTexture.setOnFrameAvailableListener({ r.frame() }, handler)
                }
                open()
                handler.postDelayed({ if (!closed && !ready) fatal("Camera did not produce a capture result within five seconds") }, 5_000)
                handler.postDelayed(::tick, 100)
            } catch (error: Exception) { fatal("Camera setup: ${error.message}") }
        }
    }
    @SuppressLint("MissingPermission") private fun open() {
        manager.openCamera(entry.deviceId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (closed) { camera.close(); return }
                device = camera
                val output = OutputConfiguration(renderer!!.input)
                entry.physicalId?.let { output.setPhysicalCameraId(it) }
                camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output),
                    java.util.concurrent.Executor { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (closed) { value.close(); return }
                        session = value
                        runCatching { repeatRequest() }.onFailure { fatal("Camera request setup: ${it.message}") }
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); fatal("Lens/mode camera session rejected") }
                }))
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); fatal("Camera disconnected") }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); fatal("Camera error $error") }
        }, handler)
    }
    fun attachPreview(surface: Surface?, width: Int, height: Int) { handler.post {
        if (surface !== previewSurface || width != previewWidth || height != previewHeight) focusFeedback.clear()
        previewSurface = surface; previewWidth = width; previewHeight = height
        if (!closed) runCatching { renderer?.attach(surface, width, height) }.onFailure { message = "Preview surface unavailable: ${it.message}"; publish() }
    } }
    fun clearFocusFeedback() { handler.post { focusFeedback.clear(); publish() } }
    fun displayRotation(degrees: Int) { handler.post {
        if (!closed && desired["orientationLock"] != 1.0) {
            val sensor = entry.characteristics.get(C.SENSOR_ORIENTATION) ?: 0
            val angle = (sensor - degrees * (if (entry.facing == C.LENS_FACING_FRONT) -1 else 1) + 360) % 360
            if (angle != renderer?.previewRotation) focusFeedback.clear()
            desired["previewRotation"] = angle.toDouble(); renderer?.previewRotation = angle
            publish()
        }
    } }
    fun command(key: String, value: Double, owner: Long? = null, completion: (Double?, String?) -> Unit = { _, _ -> }) { handler.post {
        if (closed || !ready) { completion(null, "Camera not ready"); return@post }
        val option = options[key]
        val invalid = option?.validate(value) ?: if (option == null) "Unknown control" else null
        if (invalid != null) { message = "$key: $invalid"; completion(null, invalid); publish(); return@post }
        if (key == "focusPoint") { tap(500.0,500.0,1000,1000,owner,false,completion); return@post }
        if (pending.size >= 32 && key !in pending) { completion(null, "Too many pending controls"); return@post }
        if (key in setOf("iso", "shutter") && desired["exposureMode"] != R.CONTROL_AE_MODE_OFF.toDouble()) {
            completion(null, "Select Manual exposure first"); message = "Select Manual exposure before ISO/shutter"; publish(); return@post
        }
        if (key in setOf("wbRed", "wbBlue") && desired["wbMode"] != R.CONTROL_AWB_MODE_OFF.toDouble()) {
            completion(null, "Select Manual gains first"); message = "Select Manual white balance first"; publish(); return@post
        }
        if (key == "ev" && desired["exposureMode"] == R.CONTROL_AE_MODE_OFF.toDouble()) {
            completion(null, "EV is unavailable in Manual exposure"); return@post
        }
        if (key == "aeLock" && value == 1.0 && desired["exposureMode"] == R.CONTROL_AE_MODE_OFF.toDouble() ||
            key == "wbLock" && value == 1.0 && desired["wbMode"] != R.CONTROL_AWB_MODE_AUTO.toDouble()) {
            completion(null, "Auto mode is required for this lock"); return@post
        }
        val before = desired.toMap()
        if (key == "focusLock" && value == 1.0) {
            val distance = lastResult?.get(V.LENS_FOCUS_DISTANCE)
            if (distance == null || lastResult?.get(V.LENS_STATE) == V.LENS_STATE_MOVING) {
                completion(null, "Focus is moving or its distance is unavailable"); message = "Wait for focus acquisition before locking"; publish(); return@post
            }
            if ("focusPoint" in pending) reject("focusPoint", "Focus locked")
            desired["focusDistance"] = distance.toDouble(); desired["focusMode"] = R.CONTROL_AF_MODE_OFF.toDouble()
            tappedFocus = false; focusRegion = null
        }
        if (key in setOf("focusMode", "focusDistance") && "focusPoint" in pending) reject("focusPoint", "Focus mode changed")
        if (key == "focusLock" && value == 0.0) { desired["focusMode"] = defaultAf(entry); tappedFocus = false; focusRegion = null }
        if (key == "focusDistance") { desired["focusMode"] = R.CONTROL_AF_MODE_OFF.toDouble(); desired["focusLock"] = 0.0; tappedFocus = false }
        if (key == "focusMode") { desired["focusLock"] = 0.0; tappedFocus = false; focusRegion = null }
        if (key == "exposureMode") {
            desired["aeLock"] = 0.0
            if (value == 0.0) {
                desired["iso"] = (lastResult?.get(V.SENSOR_SENSITIVITY)?.toDouble() ?: desired.getValue("iso")).coerceIn(options.getValue("iso").min, options.getValue("iso").max)
                desired["shutter"] = ((lastResult?.get(V.SENSOR_EXPOSURE_TIME) ?: 10_000_000L) / 1_000_000.0).coerceIn(options.getValue("shutter").min, options.getValue("shutter").max)
            }
        }
        if (key == "wbMode") desired["wbLock"] = 0.0
        pending.remove(key)?.completion?.invoke(null, "Superseded")
        desired[key] = value; revision++
        val p = Pending(++requestId, revision, value, SystemClock.elapsedRealtime() + 3_000, owner, completion)
        val admission = commands.submit(gateCommand(key,p),SystemClock.elapsedRealtime())
        val rejected = admission.find { it.command.id == p.id }
        if (rejected != null) { desired.clear(); desired.putAll(before); completion(null,rejected.error); return@post }
        pending[key] = p
        if (key in setOf("zoom", "previewRotation", "previewMirror", "focusMode", "focusDistance") || key == "focusLock" && value == 0.0)
            focusFeedback.clear()
        if (key == "previewRotation") { desired["orientationLock"] = 1.0; renderer?.previewRotation = value.toInt() }
        try {
            renderer?.values = desired.toMap()
            if (key !in software) repeatRequest()
            message = "Applying ${option!!.label}"; publish()
        } catch (error: Exception) { reject(key, "Camera rejected request: ${error.message}") }
    } }
    fun cancel(owner: Long) { handler.post {
        if (closed) return@post
        val keys = pending.filterValues { it.owner == owner }.keys.toList()
        for (key in keys) reject(key, "Cancelled")
        if (keys.isNotEmpty()) {
            if ("focusPoint" in keys) { tappedFocus = false; focusRegion = null; desired["focusMode"] = defaultAf(entry); focusFeedback.clear() }
            revision++; renderer?.values = desired.toMap()
            runCatching { repeatRequest() }; publish()
        }
    } }
    fun tap(x: Double, y: Double, width: Int, height: Int, owner: Long? = null, output: Boolean = false,
        completion: (Double?, String?) -> Unit = { _, _ -> }) { handler.post {
        if (closed || !ready) { completion(null, "Camera not ready"); return@post }
        if (width <= 0 || height <= 0) { completion(null, "Preview geometry unavailable"); return@post }
        val c = entry.characteristics
        val angle = if (output) desired.getValue("outputRotation").toInt() else renderer?.previewRotation ?: 0
        val aspect = if (angle % 180 == 0) mode.size.width.toDouble() / mode.size.height else mode.size.height.toDouble() / mode.size.width
        val rectWidth = minOf(width.toDouble(), height * aspect); val rectHeight = rectWidth / aspect
        val image = FocusCoordinates.Rect((width - rectWidth) / 2, (height - rectHeight) / 2, (width + rectWidth) / 2, (height + rectHeight) / 2)
        val active = c.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: run { completion(null,"Sensor geometry unavailable"); return@post }
        val crop = lastResult?.get(V.SCALER_CROP_REGION) ?: active
        // Preview stream has an additional aspect crop inside the sensor's digital-zoom crop.
        val streamAspect = mode.size.width.toDouble() / mode.size.height
        val croppedWidth = minOf(crop.width().toDouble(), crop.height() * streamAspect)
        val croppedHeight = croppedWidth / streamAspect
        val left = crop.exactCenterX() - croppedWidth / 2; val top = crop.exactCenterY() - croppedHeight / 2
        val normalized = FocusCoordinates.Rect((left - active.left) / active.width(), (top - active.top) / active.height(),
            (left + croppedWidth - active.left) / active.width(), (top + croppedHeight - active.top) / active.height())
        val point = FocusCoordinates.sensorPoint(x, y, image, normalized, angle, desired[if (output) "outputMirror" else "previewMirror"] == 1.0)
            ?: run { completion(null,"Tap is outside the camera image"); return@post }
        if ((c.get(C.CONTROL_MAX_REGIONS_AF) ?: 0) == 0 || c.get(C.CONTROL_AF_AVAILABLE_MODES)?.contains(R.CONTROL_AF_MODE_AUTO) != true) {
            focusFeedback.clear()
            message = "Tap focus unavailable on this lens"; focus = "Unavailable"; completion(null,message); publish(); return@post
        }
        val px = active.left + (point.x * active.width()).toInt(); val py = active.top + (point.y * active.height()).toInt()
        val radius = maxOf(8, crop.width() / 30)
        val target = Pending(++requestId,revision + 1,1.0,SystemClock.elapsedRealtime()+3_000,owner,completion)
        val admission = commands.submit(gateCommand("focusPoint",target),SystemClock.elapsedRealtime())
        val rejected = admission.find { it.command.id == target.id }
        if (rejected != null) { completion(null,rejected.error); return@post }
        focusRegion = MeteringRectangle(Rect((px - radius).coerceAtLeast(crop.left), (py - radius).coerceAtLeast(crop.top),
            (px + radius).coerceAtMost(crop.right), (py + radius).coerceAtMost(crop.bottom)), MeteringRectangle.METERING_WEIGHT_MAX)
        tappedFocus = true; desired["focusLock"] = 0.0; focus = "Acquiring"; focusDeadline = SystemClock.elapsedRealtime() + 5_000; revision++
        pending.remove("focusPoint")?.completion?.invoke(null,"Superseded")
        pending["focusPoint"] = target
        focusFeedback.clear()
        if (!output && width == previewWidth && height == previewHeight)
            focusFeedback.begin(revision, x, y, width, height, SystemClock.elapsedRealtime())
        try {
            // Reset the preceding acquisition, including when the new tap hits the same region.
            val cancel = request(); put(cancel, R.CONTROL_AF_TRIGGER, R.CONTROL_AF_TRIGGER_CANCEL)
            session?.capture(cancel.build(), callback, handler)
            val builder = request(); put(builder, R.CONTROL_AF_TRIGGER, R.CONTROL_AF_TRIGGER_START)
            session?.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: R, timestamp: Long, frameNumber: Long) {
                    if (!closed) focusFeedback.triggered(target.revision, frameNumber)
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: R, result: TotalCaptureResult) {
                    callback.onCaptureCompleted(session, request, result)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: R, failure: CaptureFailure) {
                    callback.onCaptureFailed(session, request, failure)
                }
            }, handler)
            repeatRequest(); publish()
        } catch (error: Exception) { message = "Tap focus failed: ${error.message}"; focus = "Failed"; reject("focusPoint",message); publish() }
    } }
    private fun request(): R.Builder {
        val builder = checkNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(renderer!!.input); builder.setTag(revision)
        put(builder, R.CONTROL_MODE, R.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= 31 && entry.characteristics.get(C.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)?.contains(R.SCALER_ROTATE_AND_CROP_NONE) == true)
            put(builder, R.SCALER_ROTATE_AND_CROP, R.SCALER_ROTATE_AND_CROP_NONE)
        val ranges = entry.characteristics.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val fps = ranges.filter { it.contains(30) }.minByOrNull { it.upper - it.lower } ?: error("30 FPS range unavailable")
        put(builder, R.CONTROL_AE_TARGET_FPS_RANGE, fps)
        val afMode = if (tappedFocus) R.CONTROL_AF_MODE_AUTO else desired.getValue("focusMode").toInt()
        put(builder, R.CONTROL_AF_MODE, afMode); put(builder, R.CONTROL_AF_TRIGGER, R.CONTROL_AF_TRIGGER_IDLE)
        if (afMode == R.CONTROL_AF_MODE_OFF && options["focusDistance"]?.reason == null) put(builder, R.LENS_FOCUS_DISTANCE, desired.getValue("focusDistance").toFloat())
        focusRegion?.let { put(builder, R.CONTROL_AF_REGIONS, arrayOf(it)) }
        val aeMode = desired.getValue("exposureMode").toInt()
        put(builder, R.CONTROL_AE_MODE, aeMode)
        if (options["aeLock"]?.reason == null) put(builder, R.CONTROL_AE_LOCK, desired["aeLock"] == 1.0)
        if (aeMode == R.CONTROL_AE_MODE_OFF) {
            put(builder, R.SENSOR_SENSITIVITY, desired.getValue("iso").toInt())
            put(builder, R.SENSOR_EXPOSURE_TIME, (desired.getValue("shutter") * 1_000_000).toLong())
            put(builder, R.SENSOR_FRAME_DURATION, 33_333_333L)
        } else if (options["ev"]?.reason == null) put(builder, R.CONTROL_AE_EXPOSURE_COMPENSATION, desired.getValue("ev").toInt())
        put(builder, R.CONTROL_AWB_MODE, desired.getValue("wbMode").toInt())
        if (options["wbLock"]?.reason == null) put(builder, R.CONTROL_AWB_LOCK, desired["wbLock"] == 1.0)
        if (desired["wbMode"] == R.CONTROL_AWB_MODE_OFF.toDouble()) {
            put(builder, R.COLOR_CORRECTION_MODE, R.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            put(builder, R.COLOR_CORRECTION_GAINS, RggbChannelVector(desired.getValue("wbRed").toFloat(), 1f, 1f, desired.getValue("wbBlue").toFloat()))
            put(builder, R.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(arrayOf(Rational(1,1), Rational(0,1), Rational(0,1),
                Rational(0,1), Rational(1,1), Rational(0,1), Rational(0,1), Rational(0,1), Rational(1,1))))
        }
        put(builder, R.FLASH_MODE, if (desired["torch"] == 1.0) R.FLASH_MODE_TORCH else R.FLASH_MODE_OFF)
        if (Build.VERSION.SDK_INT >= 35 && options["torchStrength"]?.reason == null) put(builder, R.FLASH_STRENGTH_LEVEL, desired.getValue("torchStrength").toInt())
        if (options["stabilization"]?.reason == null) put(builder, R.CONTROL_VIDEO_STABILIZATION_MODE, desired.getValue("stabilization").toInt())
        if (options["antiFlicker"]?.reason == null) put(builder, R.CONTROL_AE_ANTIBANDING_MODE, desired.getValue("antiFlicker").toInt())
        val active = entry.characteristics.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val zoom = desired.getValue("zoom")
        val cw = (active.width() / zoom).toInt().coerceAtLeast(2); val ch = (active.height() / zoom).toInt().coerceAtLeast(2)
        put(builder, R.SCALER_CROP_REGION, Rect(active.left + (active.width()-cw)/2, active.top + (active.height()-ch)/2,
            active.left + (active.width()+cw)/2, active.top + (active.height()+ch)/2))
        return builder
    }
    private fun repeatRequest() { session?.setRepeatingRequest(request().build(), callback, handler) }
    private fun <T> put(builder: R.Builder, key: R.Key<T>, value: T) {
        val physical = entry.physicalId
        val keys = if (physical != null) manager.getCameraCharacteristics(entry.deviceId).availablePhysicalCameraRequestKeys else null
        if (physical != null && keys?.contains(key) == true) builder.setPhysicalCameraKey(key, value, physical) else builder.set(key, value)
    }
    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: R, result: TotalCaptureResult) {
            if (closed || failed) return
            val sensorResult = if (entry.physicalId == null) result else result.physicalCameraResults[entry.physicalId]
                ?: run { fatal("Physical camera did not expose capture-result metadata"); return }
            lastResult = sensorResult
            val tag = request.tag as? Long ?: return
            if (!ready) { ready = true; onReady() }
            val actual = readback(sensorResult)
            applied.putAll(actual)
            for ((key, p) in pending.toMap()) {
                if (key in software || tag < p.revision) continue
                if (SystemClock.elapsedRealtime() >= p.deadline) { reject(key, "Camera command timed out"); continue }
                val value = actual[key] ?: continue
                // Confirm returned state, rather than trusting the requested capture metadata.
                val tolerance = when (key) { "zoom" -> 0.02; "shutter" -> 0.1; "focusDistance", "wbRed", "wbBlue" -> 0.05; else -> 0.001 }
                if (kotlin.math.abs(value - p.value) <= tolerance) {
                    val confirmed = commands.complete(gateCommand(key,p),value,null,SystemClock.elapsedRealtime())
                    pending.remove(key); message = confirmed?.error ?: "${options[key]?.label} applied"; publish()
                    if (key == "focusLock" && confirmed?.applied == 1.0) focusFeedback.locked(SystemClock.elapsedRealtime())
                    p.completion(confirmed?.applied, confirmed?.error ?: if (confirmed == null) "Obsolete command" else null)
                }
            }
            focus = if (actual["focusLock"] == 1.0) "Locked" else if (actual["focusMode"] == 0.0 && !tappedFocus) {
                if ((entry.characteristics.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) == 0f) "Fixed focus" else "Manual"
            } else when (sensorResult.get(V.CONTROL_AF_STATE)) {
                V.CONTROL_AF_STATE_FOCUSED_LOCKED, V.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "Focused"
                V.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, V.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "Not focused"
                V.CONTROL_AF_STATE_ACTIVE_SCAN, V.CONTROL_AF_STATE_PASSIVE_SCAN -> "Acquiring"
                else -> if (tappedFocus) "Acquiring" else "Auto"
            }
            val acquisition = when (sensorResult.get(V.CONTROL_AF_STATE)) {
                V.CONTROL_AF_STATE_FOCUSED_LOCKED -> true
                V.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> false
                else -> null
            }
            focusFeedback.result(tag, result.frameNumber, actual["focusPoint"] == 1.0, acquisition, SystemClock.elapsedRealtime())
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: R, failure: CaptureFailure) {
            if (closed || failed) return
            val tag = request.tag as? Long ?: return
            message = "Camera capture rejected (reason ${failure.reason})"
            for ((key, p) in pending.toMap()) if (key !in software && tag >= p.revision) reject(key, message)
            if (tag >= (focusFeedback.visible(SystemClock.elapsedRealtime())?.revision ?: Long.MAX_VALUE)) focusFeedback.fail(SystemClock.elapsedRealtime())
            publish()
        }
    }
    private fun readback(result: V): Map<String, Double> {
        val values = mutableMapOf<String, Double>()
        if (tappedFocus && focusRegion != null && result.get(V.CONTROL_AF_MODE) == R.CONTROL_AF_MODE_AUTO &&
            result.get(V.CONTROL_AF_REGIONS)?.any { it.rect == focusRegion!!.rect } == true) values["focusPoint"] = 1.0
        fun number(name: String, value: Number?) { if (value != null) values[name] = value.toDouble() }
        fun flag(name: String, value: Boolean?) { if (value != null) values[name] = if (value) 1.0 else 0.0 }
        number("focusMode", result.get(V.CONTROL_AF_MODE)); number("focusDistance", result.get(V.LENS_FOCUS_DISTANCE))
        if (result.get(V.CONTROL_AF_MODE) == R.CONTROL_AF_MODE_OFF && result.get(V.LENS_FOCUS_DISTANCE) != null &&
            result.get(V.LENS_STATE) == V.LENS_STATE_STATIONARY &&
            kotlin.math.abs(result.get(V.LENS_FOCUS_DISTANCE)!!.toDouble() - desired.getValue("focusDistance")) <= 0.05)
            values["focusLock"] = desired.getValue("focusLock")
        else values["focusLock"] = 0.0
        number("exposureMode", result.get(V.CONTROL_AE_MODE)); number("iso", result.get(V.SENSOR_SENSITIVITY))
        result.get(V.SENSOR_EXPOSURE_TIME)?.let { values["shutter"] = it / 1_000_000.0 }
        number("ev", result.get(V.CONTROL_AE_EXPOSURE_COMPENSATION)); flag("aeLock", result.get(V.CONTROL_AE_LOCK))
        number("wbMode", result.get(V.CONTROL_AWB_MODE)); flag("wbLock", result.get(V.CONTROL_AWB_LOCK))
        result.get(V.COLOR_CORRECTION_GAINS)?.let { values["wbRed"] = it.red.toDouble(); values["wbBlue"] = it.blue.toDouble() }
        result.get(V.FLASH_MODE)?.let {
            if (it == R.FLASH_MODE_OFF) values["torch"] = 0.0
            else if (it == R.FLASH_MODE_TORCH && result.get(V.FLASH_STATE) == V.FLASH_STATE_FIRED) values["torch"] = 1.0
        }
        if (Build.VERSION.SDK_INT >= 35) number("torchStrength", result.get(V.FLASH_STRENGTH_LEVEL))
        number("stabilization", result.get(V.CONTROL_VIDEO_STABILIZATION_MODE)); number("antiFlicker", result.get(V.CONTROL_AE_ANTIBANDING_MODE))
        val active = entry.characteristics.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        result.get(V.SCALER_CROP_REGION)?.let { if (active != null) values["zoom"] = active.width().toDouble() / it.width() }
        return values
    }
    private fun rendered() {
        if (closed) return
        for (key in software) applied[key] = desired.getValue(key)
        for ((key, p) in pending.toMap()) if (key in software) {
            val confirmed = commands.complete(gateCommand(key,p),p.value,null,SystemClock.elapsedRealtime())
            pending.remove(key); message = confirmed?.error ?: "${options[key]?.label} applied"; publish()
            p.completion(confirmed?.applied,confirmed?.error ?: if (confirmed == null) "Obsolete command" else null)
        }
    }
    private fun reject(key: String, reason: String) {
        pending.remove(key)?.let { p -> commands.cancel(gateCommand(key,p),reason); p.completion(null,reason) }
        desired[key] = applied[key] ?: defaults(entry).getValue(key)
        if (key == "focusPoint") { tappedFocus = false; focusRegion = null; desired["focusMode"] = defaultAf(entry); focusFeedback.fail(SystemClock.elapsedRealtime()) }
        renderer?.values = desired.toMap()
        if (key == "previewRotation") renderer?.previewRotation = desired.getValue(key).toInt()
        if (!closed && ready) runCatching { repeatRequest() }
        message = reason
    }
    private fun gateCommand(key: String, p: Pending) = CameraControlState.Command(p.id,binding,key,p.value)
    private fun tick() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        for ((key, p) in pending.toMap()) if (now >= p.deadline) reject(key, "${options[key]?.label}: camera did not confirm application")
        if (tappedFocus && now >= focusDeadline) {
            focusFeedback.fail(now)
            if (focus == "Acquiring") focus = "Focus timed out"
        }
        publish(); handler.postDelayed(::tick, 100)
    }
    private fun publish() {
        if (closed) return
        status = CameraStatus(entry.id, mode.name, generation, options.values.toList(), applied.toMap(), pending.keys.toSet(), focus, message.take(1000),
            renderer?.histogram?.get() ?: IntArray(64), renderer?.previewRotation ?: 0, focusFeedback.visible(SystemClock.elapsedRealtime()))
        onStatus(status)
    }
    private fun fatal(reason: String) { if (!closed && !failed) { failed = true; onFatal(reason) } }
    override fun close() {
        if (closed) return
        closed = true
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                for (p in pending.values.toList()) p.completion(null, "Capture stopped or camera changed")
                pending.clear()
                commands.stop()
                runCatching { session?.stopRepeating() }; runCatching { session?.abortCaptures() }
                session?.close(); session = null; device?.close(); device = null
                renderer?.close(); renderer = null
            } finally { done.countDown(); thread.quitSafely() }
        }
        if (android.os.Looper.myLooper() == thread.looper) cleanup.run() else { handler.post(cleanup); done.await(2, TimeUnit.SECONDS) }
    }
    companion object {
        private fun defaultAf(entry: CameraEntry): Double = if (entry.characteristics.get(C.CONTROL_AF_AVAILABLE_MODES)?.contains(R.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true)
            R.CONTROL_AF_MODE_CONTINUOUS_VIDEO.toDouble() else if (entry.characteristics.get(C.CONTROL_AF_AVAILABLE_MODES)?.contains(R.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true)
                R.CONTROL_AF_MODE_CONTINUOUS_PICTURE.toDouble() else R.CONTROL_AF_MODE_OFF.toDouble()
        fun defaults(entry: CameraEntry) = mapOf("zoom" to 1.0, "focusMode" to defaultAf(entry), "focusDistance" to 0.0, "focusLock" to 0.0,
            "focusPoint" to 1.0, "torch" to 0.0, "torchStrength" to 1.0, "ev" to 0.0, "aeLock" to 0.0, "exposureMode" to 1.0,
            "iso" to (entry.characteristics.get(C.SENSOR_INFO_SENSITIVITY_RANGE)?.lower?.toDouble() ?: 100.0), "shutter" to 10.0,
            "wbMode" to 1.0, "wbLock" to 0.0, "wbRed" to 1.0, "wbBlue" to 1.0,
            "stabilization" to 0.0, "antiFlicker" to (entry.characteristics.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.firstOrNull()?.toDouble() ?: 0.0),
            "brightness" to 0.0, "contrast" to 1.0, "saturation" to 1.0, "outputRotation" to 0.0, "outputMirror" to 0.0,
            "previewRotation" to (entry.characteristics.get(C.SENSOR_ORIENTATION)?.toDouble() ?: 0.0),
            "previewMirror" to if (entry.facing == C.LENS_FACING_FRONT) 1.0 else 0.0, "orientationLock" to 0.0, "grid" to 0.0, "zebras" to 0.0, "peaking" to 0.0, "histogram" to 0.0)
    }
}
