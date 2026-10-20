package org.camylla.proof

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as R
import android.os.Build
import android.util.Size

internal data class CameraEntry(val id: String, val label: String, val facing: Int,
    val modes: List<CaptureService.Mode>, val characteristics: C, val deviceId: String = id, val physicalId: String? = null) {
    fun options(mode: CaptureService.Mode): List<CameraOption> {
        fun unsupported(key: String, label: String, reason: String) = CameraOption(key, label, reason = reason)
        fun toggle(key: String, label: String, supported: Boolean) = if (supported) CameraOption(key, label,
            choices = mapOf(0 to "Off", 1 to "On")) else unsupported(key, label, "Not exposed by this lens")
        fun choices(key: String, label: String, values: IntArray?, names: Map<Int, String>): CameraOption {
            val available = names.filterKeys { values?.contains(it) == true }
            return if (available.isEmpty()) unsupported(key, label, "Not exposed by this lens") else CameraOption(
                key, label, available.keys.min().toDouble(), available.keys.max().toDouble(), choices = available)
        }
        val c = characteristics
        val af = c.get(C.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val distance = c.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val manualSensor = c.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val manualWb = c.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true &&
            c.availableCaptureRequestKeys?.contains(R.COLOR_CORRECTION_GAINS) == true &&
            c.availableCaptureResultKeys?.contains(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS) == true
        val ev = c.get(C.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(C.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val iso = c.get(C.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val zoom = (c.get(C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).toDouble()
        return listOf(
            CameraOption("zoom", "Digital zoom", 1.0, zoom, 0.01, "×"),
            choices("focusMode", if (distance == 0f) "Focus (fixed lens)" else "Focus mode", af,
                mapOf(R.CONTROL_AF_MODE_OFF to if (distance == 0f) "Fixed focus" else "Manual", R.CONTROL_AF_MODE_CONTINUOUS_VIDEO to "Auto video", R.CONTROL_AF_MODE_CONTINUOUS_PICTURE to "Auto photo")),
            if (distance > 0 && af.contains(R.CONTROL_AF_MODE_OFF)) CameraOption("focusDistance", "Manual focus", 0.0, distance.toDouble(), 0.01, "diopters")
                else unsupported("focusDistance", "Manual focus", "Fixed focus or manual focus not exposed"),
            toggle("focusLock", "Focus lock", distance > 0 && af.contains(R.CONTROL_AF_MODE_OFF)),
            if ((c.get(C.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 && af.contains(R.CONTROL_AF_MODE_AUTO))
                CameraOption("focusPoint", "Focus target (tap image)", 1.0, 1.0, choices = mapOf(1 to "Focus centre"))
                else unsupported("focusPoint", "Focus target", "AF metering regions or single autofocus not exposed"),
            toggle("torch", "Video light", c.get(C.FLASH_INFO_AVAILABLE) == true),
            if (Build.VERSION.SDK_INT >= 35 && (c.get(C.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1) > 1)
                CameraOption("torchStrength", "Light strength", 1.0, c.get(C.FLASH_TORCH_STRENGTH_MAX_LEVEL)!!.toDouble())
                else unsupported("torchStrength", "Light strength", "Variable torch strength not exposed on this Android/lens"),
            if (ev != null && evStep > 0 && ev.upper > ev.lower) CameraOption("ev", "Exposure compensation", ev.lower.toDouble(), ev.upper.toDouble(), 1.0, "steps (${evStep} EV)")
                else unsupported("ev", "Exposure compensation", "No compensation range"),
            toggle("aeLock", "Exposure lock", c.get(C.CONTROL_AE_LOCK_AVAILABLE) == true),
            choices("exposureMode", "Exposure mode", c.get(C.CONTROL_AE_AVAILABLE_MODES),
                if (manualSensor) mapOf(R.CONTROL_AE_MODE_ON to "Auto", R.CONTROL_AE_MODE_OFF to "Manual") else mapOf(R.CONTROL_AE_MODE_ON to "Auto")),
            if (manualSensor && iso != null) CameraOption("iso", "ISO", iso.lower.toDouble(), iso.upper.toDouble(), 1.0, "ISO")
                else unsupported("iso", "ISO", "Manual sensor controls not exposed"),
            if (manualSensor && exposure != null) CameraOption("shutter", "Exposure time", exposure.lower / 1_000_000.0,
                minOf(exposure.upper / 1_000_000.0, 1000.0 / mode.fps), 0.001, "ms (${mode.fps} FPS limit)")
                else unsupported("shutter", "Exposure time", "Manual sensor controls not exposed"),
            choices("wbMode", "White balance", c.get(C.CONTROL_AWB_AVAILABLE_MODES), mapOf(
                R.CONTROL_AWB_MODE_AUTO to "Auto", R.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent",
                R.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent", R.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
                R.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy", R.CONTROL_AWB_MODE_SHADE to "Shade") +
                if (manualWb) mapOf(R.CONTROL_AWB_MODE_OFF to "Manual gains") else emptyMap()),
            toggle("wbLock", "White balance lock", c.get(C.CONTROL_AWB_LOCK_AVAILABLE) == true),
            if (manualWb) CameraOption("wbRed", "Sensor WB red gain", 1.0, 4.0, 0.01, "gain") else unsupported("wbRed", "Sensor WB red gain", "Manual color gains/result not exposed"),
            if (manualWb) CameraOption("wbBlue", "Sensor WB blue gain", 1.0, 4.0, 0.01, "gain") else unsupported("wbBlue", "Sensor WB blue gain", "Manual color gains/result not exposed"),
            choices("stabilization", "Video stabilization", c.get(C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                mapOf(R.CONTROL_VIDEO_STABILIZATION_MODE_OFF to "Off", R.CONTROL_VIDEO_STABILIZATION_MODE_ON to "On")),
            choices("antiFlicker", "Anti-flicker", c.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES), mapOf(
                R.CONTROL_AE_ANTIBANDING_MODE_OFF to "Off", R.CONTROL_AE_ANTIBANDING_MODE_50HZ to "50 Hz",
                R.CONTROL_AE_ANTIBANDING_MODE_60HZ to "60 Hz", R.CONTROL_AE_ANTIBANDING_MODE_AUTO to "Auto")),
            CameraOption("brightness", "Software brightness", -0.5, 0.5, 0.01),
            CameraOption("contrast", "Software contrast", 0.0, 2.0, 0.01),
            CameraOption("saturation", "Software saturation", 0.0, 2.0, 0.01),
            CameraOption("outputRotation", "Output rotation (landscape canvas)", 0.0, 270.0, choices = mapOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°")),
            CameraOption("previewRotation", "Phone preview orientation", 0.0, 270.0, choices = mapOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°")),
            toggle("outputMirror", "Mirror webcam output", true), toggle("previewMirror", "Mirror phone preview", true),
            toggle("orientationLock", "Lock preview orientation", true), toggle("grid", "Preview grid", true),
            toggle("zebras", "Preview overexposure zebras", true), toggle("peaking", "Preview focus peaking", true),
            toggle("histogram", "Preview histogram", true),
        )
    }
}

internal object CameraCatalog {
    fun list(manager: CameraManager): List<CameraEntry> {
        val ids = manager.cameraIdList.toSet()
        val targets = ids.map { Triple(it, it, null as String?) }.toMutableList()
        for (logical in ids) runCatching {
            val c = manager.getCameraCharacteristics(logical)
            if (c.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true)
                for (physical in c.physicalCameraIds) if (physical !in ids) targets.add(Triple("$logical/$physical", logical, physical))
        }
        return targets.distinctBy { it.first }.mapNotNull { (id, device, physical) ->
        runCatching {
            val c = manager.getCameraCharacteristics(physical ?: device)
            val streams = c.get(C.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching null
            val sizes = streams.getOutputSizes(SurfaceTexture::class.java).toSet()
            val fps = c.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val fpsPairs = fps.map { it.lower to it.upper }
            val modes = CaptureService.Mode.entries.filter {
                CaptureModeCapability.supported(it.size in sizes, fpsPairs,
                    streams.getOutputMinFrameDuration(SurfaceTexture::class.java, it.size), it.fps) &&
                    AvcEncoder.supports(it.size.width, it.size.height, it.fps)
            }
            if (modes.isEmpty()) return@runCatching null
            val facing = c.get(C.LENS_FACING) ?: C.LENS_FACING_EXTERNAL
            val focal = c.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "${it}mm" }.orEmpty()
            val name = when (facing) { C.LENS_FACING_FRONT -> "Front"; C.LENS_FACING_BACK -> "Rear"; else -> "External" }
            CameraEntry(id, "$name · $focal · camera $id", facing, modes, c, device, physical)
        }.getOrNull()
        }
    }
}
