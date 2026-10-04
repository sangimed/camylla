package org.camylla.proof

import org.json.JSONObject

data class PhoneRotation(val role: String, val rotationId: String, val oldId: String, val newId: String,
    val newCertificate: String, val newAlias: String, val peer: TrustedDesktop, val createdUtc: Long, val createdElapsed: Long) {
    val expired: Boolean get() = System.currentTimeMillis() - createdUtc >= 60_000 ||
        android.os.SystemClock.elapsedRealtime() - createdElapsed !in 0..59_999
    fun toJson() = JSONObject().put("role", role).put("rotation_id", rotationId).put("old_id", oldId).put("new_id", newId)
        .put("new_cert", newCertificate).put("new_alias", newAlias).put("created_utc", createdUtc).put("created_elapsed", createdElapsed)
        .put("peer", JSONObject().put("id", peer.id).put("name", peer.name).put("host", peer.host).put("certificate", peer.certificate))
    companion object {
        fun fromJson(value: JSONObject): PhoneRotation {
            val role = value.getString("role"); require(role in listOf("phone", "desktop"))
            val peer = value.getJSONObject("peer")
            return PhoneRotation(role, PairInvitation.hex(value.getString("rotation_id"), 16),
                PairInvitation.hex(value.getString("old_id"), 32), PairInvitation.hex(value.getString("new_id"), 32),
                value.getString("new_cert"), value.getString("new_alias"),
                TrustedDesktop(PairInvitation.hex(peer.getString("id"), 32), peer.getString("name"), peer.getString("host"), peer.getString("certificate")),
                value.getLong("created_utc"), value.getLong("created_elapsed"))
        }
    }
}
