package org.camylla.proof

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Per-session byte cap. The encoder keeps draining when full so capture behavior remains measurable. */
class DiagnosticSink(val file: File, private val maxBytes: Long = 64L * 1024 * 1024) : Closeable {
    private val output = FileOutputStream(file)
    var bytes = 0L
        private set
    var limitReached = false
        private set

    fun write(buffer: ByteBuffer) {
        val length = buffer.remaining()
        if (limitReached) return
        if (length.toLong() > maxBytes - bytes) {
            limitReached = true
            return
        }
        val chunk = ByteArray(minOf(length, 64 * 1024))
        while (buffer.hasRemaining()) {
            val count = minOf(buffer.remaining(), chunk.size)
            buffer.get(chunk, 0, count)
            output.write(chunk, 0, count)
        }
        bytes += length
    }

    /** Accepts Annex B directly, or converts 4-byte length-prefixed NAL units without frame copies. */
    fun writeAvc(buffer: ByteBuffer) {
        val source = buffer.slice()
        if (source.remaining() >= 4 && source.get(0) == 0.toByte() && source.get(1) == 0.toByte() &&
            (source.get(2) == 1.toByte() || (source.get(2) == 0.toByte() && source.get(3) == 1.toByte()))) {
            write(source)
            return
        }
        val scan = source.duplicate()
        var convertedBytes = 0L
        while (scan.hasRemaining()) {
            if (scan.remaining() < 4) throw IOException("Invalid AVC NAL length header")
            val length = scan.int
            if (length <= 0 || length > scan.remaining()) throw IOException("Invalid AVC NAL length $length")
            convertedBytes += 4L + length
            scan.position(scan.position() + length)
        }
        if (limitReached) return
        if (convertedBytes > maxBytes - bytes) {
            limitReached = true
            return
        }
        while (source.hasRemaining()) {
            val length = source.int
            output.write(byteArrayOf(0, 0, 0, 1))
            val nal = source.slice()
            nal.limit(length)
            val chunk = ByteArray(minOf(length, 64 * 1024))
            while (nal.hasRemaining()) {
                val count = minOf(nal.remaining(), chunk.size)
                nal.get(chunk, 0, count)
                output.write(chunk, 0, count)
            }
            source.position(source.position() + length)
        }
        bytes += convertedBytes
    }

    override fun close() = output.close()
}
