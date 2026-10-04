package org.camylla.proof

import java.net.URI
import java.net.URLDecoder

data class PairInvitation(val host: String, val port: Int, val desktopId: String,
    val pairId: String, val capability: String, val expiresAt: Long) {
    companion object {
        fun parse(text: String, nowSeconds: Long = System.currentTimeMillis() / 1000): PairInvitation {
            require(text.toByteArray(Charsets.UTF_8).size in 1..512) { "Invitation too large" }
            val uri = URI(text)
            require(uri.scheme == "camylla-pair" && uri.rawAuthority == "v3" &&
                uri.rawPath.orEmpty().isEmpty() && uri.rawFragment == null) { "Invalid invitation" }
            val fields = linkedMapOf<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&')) {
                val pair = part.split('=', limit = 2)
                require(pair.size == 2) { "Invalid invitation field" }
                val key = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair[1], "UTF-8")
                require(fields.put(key, value) == null) { "Duplicate invitation field" }
            }
            require(fields.keys == setOf("host", "control_port", "desktop_id", "pair_id", "cap", "expires_at"))
            val host = fields.getValue("host")
            validateHost(host)
            val port = fields.getValue("control_port").toInt()
            require(port == 58413) { "Unsupported control port" }
            val expires = fields.getValue("expires_at").toLong()
            require(expires > nowSeconds && expires <= nowSeconds + 120) { "Invitation expired or invalid expiry" }
            return PairInvitation(host.trim('[', ']'), port, hex(fields.getValue("desktop_id"), 32),
                hex(fields.getValue("pair_id"), 16), hex(fields.getValue("cap"), 32), expires)
        }
        fun hex(value: String, bytes: Int): String {
            require(value.length == bytes * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid identity or authorization" }
            return value
        }
        fun validateHost(value: String) {
            require(value.length in 1..253 && value.all { it.code < 128 && !it.isWhitespace() }) { "Invalid LAN address" }
            val host = value.trim('[', ']')
            require(host.isNotEmpty() && host.all { it.isLetterOrDigit() || it in ".:-" } &&
                !host.startsWith('-') && !host.endsWith('-')) { "Invalid LAN address" }
        }
    }
}
