package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class M3WireTest {
    @Test fun accessUnitCarriesSessionSequencePtsAndKind() {
        val session = M3Wire.hex16("00112233445566778899AABBCCDDEEFF")
        val bytes = M3Wire.video(7, 3, 123456, 123999, session, M3Wire.KEY, byteArrayOf(0, 0, 1, 0x65))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals("CMYV", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(2, header.get(4).toInt())
        assertEquals(64, header.getShort(6).toInt())
        assertEquals(4, header.getInt(8))
        assertEquals(7L, header.getLong(12))
        assertEquals(3, header.getInt(20))
        assertEquals(123456L, header.getLong(24))
        assertEquals(M3Wire.KEY, header.getShort(32).toInt())
        assertEquals(1, header.getShort(34).toInt())
        assertArrayEquals(session, bytes.copyOfRange(36, 52))
        assertEquals(123999L, header.getLong(52))
    }

    @Test fun controlUsesExactReadsAndRejectsMalformedLengths() {
        val output = ByteArrayOutputStream()
        M3Wire.writeControl(output, "{\"type\":\"hello\"}")
        val slow = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals("{\"type\":\"hello\"}", M3Wire.readControl(slow))
        val oversized = ByteBuffer.allocate(4).putInt(M3Wire.MAX_CONTROL_BYTES + 1).array()
        assertThrows(IllegalArgumentException::class.java) { M3Wire.readControl(ByteArrayInputStream(oversized)) }
        assertThrows(EOFException::class.java) { M3Wire.readControl(ByteArrayInputStream(output.toByteArray().dropLast(1).toByteArray())) }
    }

    @Test fun videoLimitsAreEnforced() {
        val id = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            M3Wire.video(1, 1, 1, 1, id, M3Wire.NORMAL, ByteArray(M3Wire.MAX_VIDEO_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            M3Wire.video(1, 1, 1, 1, id, 8, byteArrayOf(1))
        }
    }
}
