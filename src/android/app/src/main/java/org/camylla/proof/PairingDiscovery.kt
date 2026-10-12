package org.camylla.proof

import android.util.Base64
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

data class PairingDesktop(val host: String, val desktopId: String, val name: String)

/** LAN discovery is a routing hint only; the one-use code and PC approval remain required. */
object PairingDiscovery {
    private const val DISCOVERY_PORT = 41415
    private const val CONTROL_PORT = 58413

    fun findDesktops(code: String, timeoutMs: Int = 1800): List<PairingDesktop> {
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val request = "CAMYLLA_PAIR_DISCOVER_V3|$nonce".toByteArray(Charsets.US_ASCII)
        val found = linkedMapOf<String, PairingDesktop>()
        val checkedSalts = mutableSetOf<String>()
        val lan = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .mapNotNull { network -> network.interfaceAddresses.firstOrNull { it.address is Inet4Address && it.broadcast != null }?.let { network to it } }
                .sortedByDescending { it.first.name.startsWith("wlan", ignoreCase = true) }
                .firstOrNull()
        }.getOrNull()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(lan?.second?.address, 0))
            socket.soTimeout = timeoutMs
            val broadcast = lan?.second?.broadcast ?: InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(request, request.size, broadcast, DISCOVERY_PORT))
            val buffer = ByteArray(2048)
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { break }
                val fields = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).split('|')
                if (fields.size != 7 || fields[0] != "CAMYLLA_PAIR_V3" || fields[1] != nonce || fields[3].toIntOrNull() != CONTROL_PORT) continue
                val id = fields[2].lowercase()
                if (id.length != 64 || id.any { it !in '0'..'9' && it !in 'a'..'f' }) continue
                val name = runCatching {
                    java.text.Normalizer.normalize(String(Base64.decode(fields[4], Base64.DEFAULT), Charsets.UTF_8), java.text.Normalizer.Form.NFC)
                }.getOrNull()?.takeIf(::validDesktopName) ?: continue
                if (fields[5] in checkedSalts || checkedSalts.size >= 8) continue
                checkedSalts.add(fields[5])
                if (!verifyCodeProof(code, nonce, id, CONTROL_PORT, fields[4], fields[5], fields[6])) continue
                found[id] = PairingDesktop(packet.address.hostAddress ?: continue, id, name)
            }
        }
        return found.values.toList()
    }

    internal fun validDesktopName(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size in 1..64 && value.none(Char::isISOControl)

    internal fun verifyCodeProof(code: String, nonce: String, desktopId: String, port: Int,
        nameBase64: String, saltHex: String, proofHex: String): Boolean = runCatching {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(salt.size == 16)
        val spec = PBEKeySpec(code.toCharArray(), salt, 600_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val expected = mac.doFinal("$nonce|$desktopId|$port|$nameBase64".toByteArray(Charsets.US_ASCII))
        val actual = proofHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        actual.size == expected.size && MessageDigest.isEqual(actual, expected)
    }.getOrDefault(false)
}
