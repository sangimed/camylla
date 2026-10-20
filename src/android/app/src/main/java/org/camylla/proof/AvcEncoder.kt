package org.camylla.proof

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream

data class EncodedProgress(
    val frames: Long, val keyframes: Long, val fps: Double, val timestampUs: Long,
    val timestampGaps: Long, val duplicateTimestamps: Long, val sinkBytes: Long, val sinkLimitReached: Boolean,
)

/** Surface input and output draining are kept separate from CameraX session ownership. */
class AvcEncoder(
    width: Int,
    height: Int,
    private val fps: Int,
    output: File,
    private val onUpdate: (EncodedProgress) -> Unit,
    private val onError: (String) -> Unit,
    private val onCodecConfig: (ByteArray) -> Unit = {},
    private val onAccessUnit: (ByteArray, Long, Boolean) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val selectedCodecName = encoderName(width, height, fps)
        ?: throw IllegalArgumentException("No AVC encoder supports ${width}x${height}@${fps}")
    private val codec = MediaCodec.createByCodecName(selectedCodecName)
    private val counter = FrameCounter()
    private val sink = DiagnosticSink(output)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val targetBitrate = if (width * height > 1280 * 720) 6_000_000 else 3_000_000
    @Volatile private var bitrate = targetBitrate
    val codecName: String = codec.name
    val configuredBitrate: Int get() = bitrate
    val selectedBitrate: Int get() = targetBitrate
    val surface: Surface
    @Volatile private var stopping = false
    private val drainThread: Thread

    fun requestSyncFrame() {
        if (!stopping) runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }.onFailure { Log.w(TAG, "Sync frame request failed", it) }
    }

    /** Changes only encoder bitrate; mode/generation remain stable. */
    fun setBitrate(next: Int): Boolean {
        if (stopping || next == bitrate || next !in 500_000..targetBitrate) return next == bitrate
        return runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next) })
            bitrate = next
        }.onFailure { Log.w(TAG, "Bitrate update failed", it) }.isSuccess
    }

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        } catch (error: Exception) {
            sink.close()
            codec.release()
            throw error
        }
        drainThread = Thread(::drain, "camylla-avc-drain").apply { start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var deadline = Long.MAX_VALUE
        var configWritten = false
        val partial = ByteArrayOutputStream()
        var partialKey = false
        try {
            while (true) {
                if (stopping && deadline == Long.MAX_VALUE) deadline = System.nanoTime() + 3_000_000_000L
                if (System.nanoTime() > deadline) {
                    Log.w(TAG, "Encoder EOS timeout")
                    break
                }
                when (val index = codec.dequeueOutputBuffer(info, 100_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        Log.i(TAG, "Encoder format: $format")
                        var hasConfig = false
                        val config = ByteArrayOutputStream()
                        for (key in listOf("csd-0", "csd-1")) {
                            format.getByteBuffer(key)?.let {
                                sink.writeAvc(it.duplicate())
                                config.write(AvcAccessUnit.annexB(it.duplicate()))
                                hasConfig = true
                            }
                        }
                        if (hasConfig) onCodecConfig(config.toByteArray())
                        configWritten = configWritten || hasConfig
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            val data = codec.getOutputBuffer(index)
                            if (data != null && info.size > 0) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    if (!configWritten) {
                                        sink.writeAvc(data.slice())
                                        onCodecConfig(AvcAccessUnit.annexB(data.slice()))
                                        configWritten = true
                                    }
                                } else {
                                    sink.writeAvc(data.slice())
                                    val bytes = AvcAccessUnit.annexB(data.slice())
                                    if (partial.size() + bytes.size > M3Wire.MAX_VIDEO_BYTES) throw IllegalArgumentException("Partial AVC access unit exceeds limit")
                                    partial.write(bytes)
                                    partialKey = partialKey || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0) continue
                                    val unique = counter.add(info.presentationTimeUs, partialKey)
                                    if (unique) onAccessUnit(partial.toByteArray(), info.presentationTimeUs, partialKey)
                                    partial.reset()
                                    partialKey = false
                                    if (unique && counter.frames % 30L == 0L) {
                                        Log.i(TAG, "frames=${counter.frames} fps=${"%.1f".format(counter.fps())} ptsUs=${counter.lastTimestampUs} gaps=${counter.timestampGaps} sinkBytes=${sink.bytes}")
                                    }
                                    if (unique && counter.frames % 10L == 0L) {
                                        val progress = EncodedProgress(
                                            counter.frames, counter.keyframes, counter.fps(),
                                            counter.lastTimestampUs, counter.timestampGaps, counter.duplicateTimestamps,
                                            sink.bytes, sink.limitReached,
                                        )
                                        callbackHandler.post { onUpdate(progress) }
                                    }
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Encoder drain failed", error)
            callbackHandler.post { onError("Encoder: ${error.message}") }
        } finally {
            sink.close()
        }
    }

    override fun close() {
        if (!stopping) {
            stopping = true
            try { codec.signalEndOfInputStream() } catch (error: Exception) { Log.w(TAG, "EOS signal failed", error) }
        }
        drainThread.join(4_000)
        if (drainThread.isAlive) Log.e(TAG, "Drain did not finish before encoder release")
        runCatching { codec.stop() }.onFailure { Log.e(TAG, "Encoder stop failed", it) }
        runCatching { codec.release() }.onFailure { Log.e(TAG, "Encoder release failed", it) }
        surface.release()
    }

    companion object {
        private const val TAG = "CamyllaProof"
        fun supports(width: Int, height: Int, fps: Int): Boolean = encoderName(width, height, fps) != null
        private fun encoderName(width: Int, height: Int, fps: Int): String? = runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (width * height > 1280 * 720) 6_000_000 else 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.findEncoderForFormat(format)?.takeIf { name ->
                val info = list.codecInfos.first { it.name == name }
                val type = info.supportedTypes.first { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
                info.getCapabilitiesForType(type).videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
            }
        }.getOrNull()
    }
}
