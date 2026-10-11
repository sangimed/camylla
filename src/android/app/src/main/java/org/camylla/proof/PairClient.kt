package org.camylla.proof

import android.util.Base64
import org.json.JSONObject
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.Normalizer

data class PendingPair(val invitation: PairInvitation, val challengeId: String, val desktopCertificate: String,
    val desktopName: String, val startedAt: Long, val requiresApproval: Boolean, val previousPhoneId: String?)

class PairClient(private val identity: ProductIdentity) {
    fun beginCode(host: String, desktopId: String, code: String, phoneName: String): PendingPair {
        val normalizedCode = PairingCode.normalize(code)
        val name = Normalizer.normalize(phoneName, Normalizer.Form.NFC)
        require(name.toByteArray(Charsets.UTF_8).size in 1..64 && name.none { it.isISOControl() }) { "Invalid phone name" }
        val reply = identity.socket(host, 58413, desktopId, mutual = false).use { socket ->
            val request = JSONObject().put("type", "pair_code_init").put("version", 3)
                .put("code", normalizedCode).put("phone_id", identity.phoneId)
                .put("client_cert", Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP)).put("name", name)
            M4Wire.writeControl(socket.outputStream, request.toString())
            JSONObject(M4Wire.readControl(socket.inputStream))
        }
        M4Wire.record(reply, "pair_challenge", "pair_id", "challenge_id", "server_cert", "name", "requires_approval", "previous_phone_id")
        val pairId = PairInvitation.hex(reply.getString("pair_id"), 16)
        val challenge = PairInvitation.hex(reply.getString("challenge_id"), 16)
        val text = reply.getString("server_cert")
        require(text.length <= 2048)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(text, Base64.NO_WRAP).inputStream()) as X509Certificate
        ProductIdentity.validateCertificate(cert, false)
        require(ProductIdentity.peerId(cert) == desktopId)
        val desktopName = reply.getString("name")
        require(desktopName.toByteArray(Charsets.UTF_8).size in 1..64 && desktopName.none { it.isISOControl() })
        require(reply.get("requires_approval") is Boolean && reply.getBoolean("requires_approval"))
        val previous = if (reply.isNull("previous_phone_id")) null else PairInvitation.hex(reply.getString("previous_phone_id"), 32)
        val invitation = PairInvitation(host, 58413, desktopId, pairId, "", System.currentTimeMillis() / 1000 + 120)
        return PendingPair(invitation, challenge, text, desktopName, android.os.SystemClock.elapsedRealtime(), true, previous)
    }
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
            M4Wire.record(reply, "pair_challenge", "pair_id", "challenge_id", "server_cert", "name", "requires_approval", "previous_phone_id")
            require(reply.getString("pair_id") == invitation.pairId)
            val challenge = PairInvitation.hex(reply.getString("challenge_id"), 16)
            val text = reply.getString("server_cert")
            require(text.length <= 2048)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(text, Base64.NO_WRAP).inputStream()) as X509Certificate
            ProductIdentity.validateCertificate(cert, false)
            require(ProductIdentity.peerId(cert) == invitation.desktopId)
            val desktopName = reply.getString("name")
            require(desktopName.toByteArray(Charsets.UTF_8).size in 1..64 && desktopName.none { it.isISOControl() })
            require(reply.get("requires_approval") is Boolean)
            val approval = reply.getBoolean("requires_approval")
            val previous = if (reply.isNull("previous_phone_id")) null else PairInvitation.hex(reply.getString("previous_phone_id"), 32)
            require(approval == (previous != null))
            return PendingPair(invitation, challenge, text, desktopName, android.os.SystemClock.elapsedRealtime(), approval, previous)
        }
    }
    fun commit(pending: PendingPair, replacesId: String? = null): TrustedDesktop {
        require(android.os.SystemClock.elapsedRealtime() - pending.startedAt < 30_000) { "Challenge expired; scan a fresh invitation" }
        val invite = pending.invitation
        if (pending.requiresApproval) while (true) {
            require(android.os.SystemClock.elapsedRealtime() - pending.startedAt < 30_000) { "Pairing approval expired" }
            val approved = identity.socket(invite.host, invite.port, invite.desktopId).use { status ->
                M4Wire.writeControl(status.outputStream, JSONObject().put("type", "pair_status").put("version", 3)
                    .put("pair_id", invite.pairId).put("challenge_id", pending.challengeId).toString())
                val reply = JSONObject(M4Wire.readControl(status.inputStream))
                M4Wire.record(reply, "pair_state", "challenge_id", "state")
                require(reply.getString("challenge_id") == pending.challengeId)
                val state = reply.getString("state"); require(state in listOf("approved", "awaiting_approval"))
                state == "approved"
            }
            if (approved) break
            Thread.sleep(250)
        }
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
