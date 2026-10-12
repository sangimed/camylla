package org.camylla.proof

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDiscoveryTest {
    private val nonce = "00112233445566778899aabbccddeeff"
    private val desktopId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val name = "REVTS1RPUA=="
    private val salt = "000102030405060708090a0b0c0d0e0f"
    private val proof = "f8167e67b9b5d1b429dbee94c7ef4124e9cc838ade9ddde04c24922b456c7dc2"

    @Test fun validatesCrossPlatformCodeProof() {
        assertTrue(PairingDiscovery.verifyCodeProof("12345678", nonce, desktopId, 58413, name, salt, proof))
        assertFalse(PairingDiscovery.verifyCodeProof("12345679", nonce, desktopId, 58413, name, salt, proof))
        assertFalse(PairingDiscovery.verifyCodeProof("12345678", nonce.dropLast(1) + "0", desktopId, 58413, name, salt, proof))
    }

    @Test fun restrictsDiscoveredDesktopNamesToProtocolBounds() {
        assertTrue(PairingDiscovery.validDesktopName("Camylla PC"))
        assertFalse(PairingDiscovery.validDesktopName("\u00e9".repeat(33)))
        assertFalse(PairingDiscovery.validDesktopName("PC\u0000"))
    }
}
