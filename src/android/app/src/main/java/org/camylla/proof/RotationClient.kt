package org.camylla.proof

import android.content.Context
import org.json.JSONObject
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class RotationClient(private val context: Context, private val progress: (String) -> Unit = {}) {
    fun desktopOffer(peer: TrustedDesktop): PhoneRotation {
        val identity = ProductIdentity(context)
        require(identity.pendingRotation == null && identity.desktops().size < 32)
        identity.socket(peer.host, 58413, peer.id).use { control ->
            write(control, JSONObject().put("type", "rotate_request").put("version", 3))
            val offer = read(control)
            M4Wire.record(offer, "rotate_offer", "role", "old_id", "new_id", "new_cert", "rotation_id")
            require(offer.getString("role") == "desktop" && offer.getString("old_id") == peer.id)
            val id = PairInvitation.hex(offer.getString("new_id"), 32)
            val encoded = offer.getString("new_cert"); require(encoded.length <= 2048)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(
                android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP).inputStream()) as X509Certificate
            ProductIdentity.validateCertificate(cert, false)
            require(ProductIdentity.peerId(cert) == id && id != peer.id)
            return PhoneRotation("desktop", PairInvitation.hex(offer.getString("rotation_id"), 16), peer.id, id,
                encoded, "", peer, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
        }
    }
    /** Called only after the phone user has confirmed the displayed old/new fingerprints. */
    fun execute(proposal: PhoneRotation): TrustedDesktop {
        val value = if (proposal.role == "phone") proposal.copy(createdUtc = System.currentTimeMillis(),
            createdElapsed = android.os.SystemClock.elapsedRealtime()) else proposal
        val identity = ProductIdentity(context)
        identity.persistRotation(value)
        try {
            identity.socket(value.peer.host, 58413, value.peer.id).use { control ->
                if (value.role == "phone") {
                    write(control, JSONObject().put("type", "rotate_offer").put("version", 3).put("role", value.role)
                        .put("old_id", value.oldId).put("new_id", value.newId).put("new_cert", value.newCertificate)
                        .put("rotation_id", value.rotationId))
                    val pending = read(control); M4Wire.record(pending, "rotate_pending", "rotation_id")
                    require(pending.getString("rotation_id") == value.rotationId)
                } else {
                    write(control, JSONObject().put("type", "rotate_request").put("version", 3))
                    val offer = read(control)
                    M4Wire.record(offer, "rotate_offer", "role", "old_id", "new_id", "new_cert", "rotation_id")
                    require(offer.getString("rotation_id") == value.rotationId && offer.getString("new_id") == value.newId &&
                        offer.getString("old_id") == value.oldId && offer.getString("role") == value.role &&
                        offer.getString("new_cert") == value.newCertificate)
                }
                return complete(identity, control, value)
            }
        } catch (error: Exception) {
            if (ProductIdentity(context).pendingRotation == null) throw error
            return resume()
        }
    }
    fun resume(): TrustedDesktop {
        val identity = ProductIdentity(context)
        val value = identity.pendingRotation ?: error("No key change to resume")
        require(identity.desktops().any { it.id == value.peer.id && !it.revoked }) { "Desktop was locally revoked" }
        // Probe the proposed identity first. Never fall back after a verified durable commit.
        var observedCommitted = false
        try {
            identity.candidateIdentity(value).socket(value.peer.host, 58413,
                if (value.role == "desktop") value.newId else value.peer.id).use { probe ->
                val state = status(probe, value)
                require(state == "committed") { "New identity has no matching committed transaction" }
                observedCommitted = true
                identity.commitRotation(value)
                return updatedPeer(value)
            }
        } catch (error: Exception) {
            if (observedCommitted || ProductIdentity(context).pendingRotation == null) throw error
        }
        identity.socket(value.peer.host, 58413, value.peer.id).use { old ->
            return complete(identity, old, value)
        }
    }
    private fun complete(identity: ProductIdentity, control: SSLSocket, value: PhoneRotation): TrustedDesktop {
        while (true) {
            require(identity.desktops().any { it.id == value.peer.id && !it.revoked }) { "Desktop was locally revoked" }
            when (status(control, value)) {
                "expired" -> {
                    identity.discardUncommittedRotation(value)
                    error("Key change expired. The previous identity is unchanged.")
                }
                "committed" -> {
                    // This old connection may have outlived a desktop commit; prove the new endpoint before local selection.
                    error("The PC committed the key change. Resume to verify the new identity.")
                }
                "awaiting_approval" -> {
                    require(!value.expired) { "Key change interrupted. Resume to check its result." }
                    progress("Approve the phone key change on your PC.")
                    Thread.sleep(500)
                }
                "approved" -> {
                    require(!value.expired) { "Key change interrupted. Resume to check its result." }
                    prove(identity, value)
                    return commit(identity, control, value)
                }
                "ready" -> return commit(identity, control, value)
                else -> error("Invalid key change state")
            }
        }
    }
    private fun prove(identity: ProductIdentity, value: PhoneRotation) {
        progress("Verifying the new key…")
        val candidate = identity.candidateIdentity(value)
        candidate.socket(value.peer.host, 58414, if (value.role == "desktop") value.newId else value.peer.id).use { proof ->
            write(proof, JSONObject().put("type", "rotate_prove").put("version", 3).put("role", value.role)
                .put("old_id", value.oldId).put("new_id", value.newId).put("rotation_id", value.rotationId))
            val ready = read(proof); M4Wire.record(ready, "rotate_ready", "rotation_id")
            require(ready.getString("rotation_id") == value.rotationId)
        }
    }
    private fun commit(identity: ProductIdentity, control: SSLSocket, value: PhoneRotation): TrustedDesktop {
        write(control, JSONObject().put("type", "rotate_commit").put("version", 3).put("rotation_id", value.rotationId))
        val ok = read(control); M4Wire.record(ok, "rotate_ok", "rotation_id")
        require(ok.getString("rotation_id") == value.rotationId)
        identity.commitRotation(value)
        android.util.Log.i("CamyllaTrust", "Key rotation committed role=${value.role} old_id=${value.oldId} new_id=${value.newId}")
        return updatedPeer(value)
    }
    private fun updatedPeer(value: PhoneRotation) = if (value.role == "desktop")
        value.peer.copy(id = value.newId, certificate = value.newCertificate) else value.peer
    private fun status(control: SSLSocket, value: PhoneRotation): String {
        write(control, JSONObject().put("type", "rotation_status").put("version", 3).put("rotation_id", value.rotationId))
        val state = read(control); M4Wire.record(state, "rotation_state", "rotation_id", "state")
        require(state.getString("rotation_id") == value.rotationId)
        return state.getString("state")
    }
    private fun write(socket: SSLSocket, value: JSONObject) = M4Wire.writeControl(socket.outputStream, value.toString())
    private fun read(socket: SSLSocket) = JSONObject(M4Wire.readControl(socket.inputStream))
}
