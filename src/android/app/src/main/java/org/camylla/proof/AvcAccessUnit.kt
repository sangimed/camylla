package org.camylla.proof

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts one MediaCodec buffer to Annex B without modifying its position. */
object AvcAccessUnit {
    fun annexB(input: ByteBuffer): ByteArray {
        val source = input.slice().order(ByteOrder.BIG_ENDIAN)
        if (source.remaining() >= 4 && source.get(0) == 0.toByte() && source.get(1) == 0.toByte() &&
            (source.get(2) == 1.toByte() || (source.get(2) == 0.toByte() && source.get(3) == 1.toByte()))) {
            return ByteArray(source.remaining()).also { source.get(it) }
        }
        val output = ByteArrayOutputStream(source.remaining() + 32)
        while (source.hasRemaining()) {
            require(source.remaining() >= 4) { "Invalid AVC NAL length header" }
            val length = source.int
            require(length > 0 && length <= source.remaining()) { "Invalid AVC NAL length" }
            require(output.size() + length + 4 <= M3Wire.MAX_VIDEO_BYTES) { "Access unit too large" }
            output.write(byteArrayOf(0, 0, 0, 1))
            val chunk = ByteArray(length)
            source.get(chunk)
            output.write(chunk)
        }
        return output.toByteArray()
    }
}
