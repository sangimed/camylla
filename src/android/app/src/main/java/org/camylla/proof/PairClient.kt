package org.camylla.proof

import android.util.Base64
import org.json.JSONObject
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.Normalizer

data class PendingPair(val invitation: PairInvitation, val challengeId: String, val desktopCertificate: String,
    val desktopName: String, val startedAt: Long)

class PairClient(private val identity: ProductIdentity) {
    fun begin(invitation: PairInvitation, phoneName: String): PendingPair {
        val name = Normalizer.normalize(phoneName, Normalizer.Form.NFC)
        require(name.toByteArray(Charsets.UTF_8).size in 1..64 && name.none { it.isISOControl() }) { "Invalid phone name" }
        require(invitation.expiresAt > System.currentTimeMillis() / 1000) { "Invitation expired" }
        identity.socket(invitation.host, invitation.port, invitation.desktopId, mutual = false).use { socket ->
            val request = JSONObject().put("type", "pair_init").put("version", 3)
                .put("pair_id", invitation.pairId).put("cap", invitation.capability)
                .put("phone_id", identity.phoneId).put("client_cert", Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP))
                .put("name", name)
            M4Wire.writeControl(socket.outputStream, request.toString())
            val reply = JSONObject(M4Wire.readControl(socket.inputStream))
            M4Wire.record(reply, "pair_challenge", "pair_id", "challenge_id", "server_cert", "name")
            require(reply.getString("pair_id") == invitation.pairId)
            val challenge = PairInvitation.hex(reply.getString("challenge_id"), 16)
            val text = reply.getString("server_cert")
            require(text.length <= 2048)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(text, Base64.NO_WRAP).inputStream()) as X509Certificate
            ProductIdentity.validateCertificate(cert, false)
            require(ProductIdentity.peerId(cert) == invitation.desktopId)
            val desktopName = reply.getString("name")
            require(desktopName.toByteArray(Charsets.UTF_8).size in 1..64 && desktopName.none { it.isISOControl() })
            return PendingPair(invitation, challenge, text, desktopName, android.os.SystemClock.elapsedRealtime())
        }
    }
    fun commit(pending: PendingPair, replacesId: String? = null): TrustedDesktop {
        require(android.os.SystemClock.elapsedRealtime() - pending.startedAt < 30_000) { "Challenge expired; scan a fresh invitation" }
        val invite = pending.invitation
        identity.socket(invite.host, invite.port, invite.desktopId).use { socket ->
            M4Wire.writeControl(socket.outputStream, JSONObject().put("type", "pair_commit").put("version", 3)
                .put("pair_id", invite.pairId).put("challenge_id", pending.challengeId).toString())
            val reply = JSONObject(M4Wire.readControl(socket.inputStream))
            M4Wire.record(reply, "pair_ok", "desktop_id", "phone_id")
            require(reply.getString("desktop_id") == invite.desktopId && reply.getString("phone_id") == identity.phoneId)
            val peer = TrustedDesktop(invite.desktopId, pending.desktopName, invite.host, pending.desktopCertificate)
            identity.trust(peer, replacesId)
            return peer
        }
    }
}
