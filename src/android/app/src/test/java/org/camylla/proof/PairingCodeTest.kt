package org.camylla.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCodeTest {
    @Test fun normalizesGroupedNumericCodes() {
        assertEquals("12345678", PairingCode.normalize("1234 5678"))
    }

    @Test fun rejectsShortAmbiguousAndMalformedCodes() {
        listOf("1234567", "123456789", "1234O678", "1234-678", "1234 567").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PairingCode.normalize(value) }
        }
    }
}
