package org.camylla.proof

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

class DiagnosticSinkTest {
    @Test fun capsFileWithoutAccumulatingInput() {
        val file = Files.createTempFile("camylla-sink", ".h264").toFile()
        try {
            DiagnosticSink(file, 4).use { sink ->
                sink.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                sink.write(ByteBuffer.wrap(byteArrayOf(4, 5)))
                assertEquals(3L, sink.bytes)
                assertTrue(sink.limitReached)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun convertsLengthPrefixedAccessUnitToAnnexB() {
        val file = Files.createTempFile("camylla-avc", ".h264").toFile()
        try {
            DiagnosticSink(file, 32).use { sink ->
                sink.writeAvc(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 2, 0x67, 0x11, 0, 0, 0, 1, 0x68)))
                assertEquals(11L, sink.bytes)
            }
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x11, 0, 0, 0, 1, 0x68), file.readBytes())
        } finally { file.delete() }
    }
}
