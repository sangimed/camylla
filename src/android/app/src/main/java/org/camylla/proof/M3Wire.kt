package org.camylla.proof

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Version 2 extends the portable CMYV header with message type, session ID and send time. */
object M3Wire {
    const val VERSION = 2
    const val HEADER_BYTES = 64
    const val MAX_VIDEO_BYTES = 4 * 1024 * 1024
    const val MAX_CONTROL_BYTES = 4096
    const val KEY = 1
    const val CONFIG = 2
    const val NORMAL = 4

    fun hex16(value: String): ByteArray {
        require(value.length == 32) { "Session ID must have 32 hex characters" }
        return ByteArray(16) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun video(sequence: Long, generation: Int, ptsUs: Long, sendUs: Long,
              session: ByteArray, flag: Int, payload: ByteArray): ByteArray {
        require(session.size == 16 && flag in listOf(KEY, CONFIG, NORMAL))
        require(payload.isNotEmpty() && payload.size <= MAX_VIDEO_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('C'.code.toByte(), 'M'.code.toByte(), 'Y'.code.toByte(), 'V'.code.toByte()))
            put(VERSION.toByte()); put(0.toByte())
            putShort(HEADER_BYTES.toShort())
            putInt(payload.size)
            putLong(sequence)
            putInt(generation)
            putLong(ptsUs)
            putShort(flag.toShort())
            putShort(1.toShort()) // access-unit message type
            put(session)
            putLong(sendUs)
            putInt(0)
            put(payload)
        }.array()
    }

    fun writeControl(output: OutputStream, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.isNotEmpty() && payload.size <= MAX_CONTROL_BYTES)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
        output.write(payload)
        output.flush()
    }

    fun readControl(input: InputStream): String {
        val header = ByteArray(4)
        readFully(input, header)
        val size = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(size in 1..MAX_CONTROL_BYTES) { "Invalid control length" }
        val payload = ByteArray(size)
        readFully(input, payload)
        return payload.toString(Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val amount = input.read(bytes, offset, bytes.size - offset)
            if (amount < 0) throw EOFException("Truncated control message")
            offset += amount
        }
    }
}
