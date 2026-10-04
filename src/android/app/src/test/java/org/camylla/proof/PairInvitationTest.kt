package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class PairInvitationTest {
    private val invitation = "camylla-pair://v3?host=192.168.1.236&control_port=58413&desktop_id=${"a".repeat(64)}&pair_id=${"b".repeat(32)}&cap=${"c".repeat(64)}&expires_at=1120"
    @Test fun validAuthorizationPreservesIdentityAndExpiry() {
        val value = PairInvitation.parse(invitation, 1000)
        assertEquals("192.168.1.236", value.host)
        assertEquals("a".repeat(64), value.desktopId)
        assertEquals(1120L, value.expiresAt)
    }
    @Test fun expiredExtendedAndDuplicateInvitationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation, 1120) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation, 999) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse("$invitation&%63ap=${"d".repeat(64)}", 1000) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation.replace("desktop_id=", "extra="), 1000) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation.replace("58413", "443"), 1000) }
    }
    @Test fun addressOrDiscoveryCannotConferAuthorization() {
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse("camylla-pair://v3?host=192.168.1.236", 1000) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation.replace("v3?", "v2?"), 1000) }
        assertThrows(IllegalArgumentException::class.java) { PairInvitation.parse(invitation.replace("c".repeat(64), "C".repeat(64)), 1000) }
    }
}
