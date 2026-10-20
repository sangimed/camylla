package org.camylla.proof

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only capability inventory. It never opens a camera or starts capture. */
@RunWith(AndroidJUnit4::class)
class CameraCapabilityReportTest {
    @Test fun logCameraAndAvcEncoder1080pCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cameras.cameraIdList) runCatching {
            val characteristics = cameras.getCameraCharacteristics(id)
            val streams = characteristics.get(C.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching
            val has1080 = streams.getOutputSizes(SurfaceTexture::class.java).any { it.width == 1920 && it.height == 1080 }
            val duration = if (has1080) streams.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(1920, 1080)) else -1L
            val regular = characteristics.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().joinToString()
            val highSpeed = if (has1080) runCatching { streams.getHighSpeedVideoFpsRangesFor(Size(1920, 1080)).joinToString() }.getOrDefault("unavailable") else "no-1080p-output"
            Log.i(TAG, "camera=$id facing=${characteristics.get(C.LENS_FACING)} logicalPhysical=${characteristics.physicalCameraIds} output1080=$has1080 minDurationNs=$duration regularAeRanges=$regular highSpeedRanges1080=$highSpeed")
        }.onFailure { Log.w(TAG, "camera=$id capability query failed", it) }
        val avc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        for (codec in avc) {
            val type = codec.supportedTypes.first { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            val video = codec.getCapabilitiesForType(type).videoCapabilities
            Log.i(TAG, "avcEncoder=${codec.name} hardware=${codec.isHardwareAccelerated} 1080p30=${supports(video, 30)} 1080p60=${supports(video, 60)} rates=${video.supportedFrameRates}")
        }
    }

    private fun supports(video: android.media.MediaCodecInfo.VideoCapabilities, fps: Int) =
        runCatching { video.areSizeAndRateSupported(1920, 1080, fps.toDouble()) }.getOrDefault(false)

    companion object { private const val TAG = "CamyllaCapability" }
}
