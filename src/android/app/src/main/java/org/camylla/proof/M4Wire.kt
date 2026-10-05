package org.camylla.proof

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object M4Wire {
    const val VERSION = 3
    const val MAX_VIDEO_BYTES = M3Wire.MAX_VIDEO_BYTES
    const val CONFIG = M3Wire.CONFIG
    const val KEY = M3Wire.KEY
    const val NORMAL = M3Wire.NORMAL
    fun hex16(value: String): ByteArray = M3Wire.hex16(PairInvitation.hex(value, 16))
    fun video(sequence: Long, generation: Int, ptsUs: Long, sendUs: Long,
              session: ByteArray, flag: Int, payload: ByteArray) = M3Wire.video(sequence, generation, ptsUs, sendUs, session, flag, payload, VERSION)
    fun writeControl(output: OutputStream, json: String) { StrictJson.validate(json); M3Wire.writeControl(output, json) }
    fun readControl(input: InputStream): String {
        val sizeBytes = ByteArray(4); readFully(input, sizeBytes)
        val size = ByteBuffer.wrap(sizeBytes).int
        require(size in 1..4096) { "Invalid control size" }
        val bytes = ByteArray(size); readFully(input, bytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        StrictJson.validate(text); return text
    }
    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) { val n = input.read(bytes, offset, bytes.size - offset); if (n < 0) throw java.io.EOFException("Control closed"); offset += n }
    }
    fun record(value: JSONObject, type: String, vararg fields: String) {
        require(value.getString("type") == type && value.getInt("version") == VERSION) { "Unsupported protocol response" }
        require(value.keys().asSequence().toSet() == fields.toSet() + setOf("type", "version")) { "Invalid response fields" }
    }
}
