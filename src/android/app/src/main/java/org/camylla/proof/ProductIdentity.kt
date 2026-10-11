package org.camylla.proof

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

data class TrustedDesktop(val id: String, val name: String, val host: String, val certificate: String,
    val revoked: Boolean = false, val updatedAt: Long = System.currentTimeMillis())

class ProductIdentity(private val context: Context, requestedAlias: String? = null) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val initialState = readProtectedState()
    private val alias = requestedAlias ?: initialState?.optString("active_alias", IDENTITY_ALIAS) ?: IDENTITY_ALIAS
    init {
        if (!store.containsAlias(alias)) {
            require(requestedAlias != null || initialState == null) { "Phone private key missing; re-pair required" }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt hashes the TLS transcript before asking Keystore for NONEwithECDSA.
                // SHA-256 remains authorized for the self-signed identity certificate.
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=Camylla phone"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).setBit(0))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 300_000))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 5L * 365 * 24 * 3600 * 1000))
                .build())
            generator.generateKeyPair()
        }
        if (requestedAlias == null) synchronized(STORE_LOCK) {
            val current = readProtectedState()
            if (current != null && current.optString("active_alias", IDENTITY_ALIAS) == alias) cleanupRetiredKeys(current)
        }
    }
    val pendingRotation: PhoneRotation? get() = readProtectedState()?.optJSONObject("pending_rotation")?.let(PhoneRotation::fromJson)
    fun newPhoneRotation(peer: TrustedDesktop): PhoneRotation {
        require(pendingRotation == null && desktops().size < 32) { "Resolve the existing key change first" }
        val newAlias = "camylla-v3-phone-${java.util.UUID.randomUUID()}"
        val next = ProductIdentity(context, newAlias)
        return PhoneRotation("phone", ProductIdentity.randomHex(16), phoneId, next.phoneId,
            android.util.Base64.encodeToString(next.certificate.encoded, android.util.Base64.NO_WRAP),
            newAlias, peer, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
    }
    fun persistRotation(value: PhoneRotation) = synchronized(STORE_LOCK) {
        val state = checkedState()
        require(state.optJSONObject("pending_rotation") == null || pendingRotation?.rotationId == value.rotationId) { "Key change already pending" }
        state.put("pending_rotation", value.toJson()); writeProtectedState(state)
    }
    fun candidateIdentity(value: PhoneRotation): ProductIdentity =
        if (value.role == "phone") ProductIdentity(context, value.newAlias) else this
    fun discardUncommittedRotation(value: PhoneRotation) = synchronized(STORE_LOCK) {
        val state = checkedState()
        require(pendingRotation?.rotationId == value.rotationId) { "Key change mismatch" }
        state.remove("pending_rotation"); writeProtectedState(state)
        discardProposalKey(value)
    }
    fun discardProposalKey(value: PhoneRotation) {
        if (value.role == "phone" && value.newAlias != alias && pendingRotation?.newAlias != value.newAlias)
            store.deleteEntry(value.newAlias)
    }
    fun repairPendingRotation() = synchronized(STORE_LOCK) {
        val state = checkedState()
        val value = pendingRotation ?: return@synchronized
        val peers = readStore().map { it.copy(revoked = true, updatedAt = System.currentTimeMillis()) }
        if (value.role == "phone") {
            val next = candidateIdentity(value)
            require(next.phoneId == value.newId && next.privateKeyNonexportable)
            state.put("active_alias", value.newAlias).put("phone_id", value.newId)
            val retired = state.optJSONArray("retired_aliases") ?: JSONArray()
            retired.put(alias); state.put("retired_aliases", retired)
        }
        state.put("desktops", peersJson(peers)).remove("pending_rotation")
        writeProtectedState(state)
        cleanupRetiredKeys(state)
    }
    fun commitRotation(value: PhoneRotation) = synchronized(STORE_LOCK) {
        val state = checkedState()
        require(pendingRotation?.rotationId == value.rotationId) { "Key change mismatch" }
        val oldPeers = readStore()
        require(oldPeers.any { it.id == value.peer.id && !it.revoked }) { "Desktop was locally revoked" }
        val now = System.currentTimeMillis()
        val peers: List<TrustedDesktop>
        if (value.role == "phone") {
            val next = candidateIdentity(value)
            require(next.phoneId == value.newId && next.privateKeyNonexportable)
            peers = oldPeers.map { if (it.id != value.peer.id) it.copy(revoked = true, updatedAt = now) else it.copy(updatedAt = now) }
            state.put("active_alias", value.newAlias).put("phone_id", value.newId)
            val retired = state.optJSONArray("retired_aliases") ?: JSONArray()
            retired.put(alias); state.put("retired_aliases", retired)
        } else {
            require(value.oldId == value.peer.id && oldPeers.size < 32)
            peers = oldPeers.map { if (it.id == value.oldId) it.copy(revoked = true, updatedAt = now) else it } +
                value.peer.copy(id = value.newId, certificate = value.newCertificate, revoked = false, updatedAt = now)
        }
        state.put("desktops", peersJson(peers)).remove("pending_rotation")
        writeProtectedState(state) // Atomically select identity and trust before retiring any key.
        cleanupRetiredKeys(state)
    }
    private fun cleanupRetiredKeys(state: JSONObject) {
        val retired = state.optJSONArray("retired_aliases") ?: return
        val remaining = JSONArray()
        for (index in 0 until retired.length()) {
            val key = retired.getString(index)
            if (key != state.optString("active_alias", IDENTITY_ALIAS))
                runCatching { store.deleteEntry(key) }.onFailure { remaining.put(key) }
        }
        if (remaining.length() != retired.length()) {
            state.put("retired_aliases", remaining)
            runCatching { writeProtectedState(state) } // Active identity was already committed atomically.
        }
    }
    val certificate: X509Certificate get() = store.getCertificate(alias) as X509Certificate
    val phoneId: String get() = peerId(certificate)
    val privateKeyNonexportable: Boolean get() = store.getKey(alias, null).encoded == null
    fun inspectDesktopIdentity(host: String, port: Int = 58413): String {
        PairInvitation.validateHost(host)
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not a client")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                validateCertificate(chain?.firstOrNull() ?: throw CertificateException("Missing desktop identity"), false)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), 1500)
            (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
                socket.apply {
                    enabledProtocols = arrayOf("TLSv1.3"); soTimeout = 1500; startHandshake()
                    require(session.protocol == "TLSv1.3") { "TLS 1.3 unavailable" }
                    val desktop = session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: throw CertificateException("Missing desktop identity")
                    val desktopId = peerId(desktop)
                    M4Wire.writeControl(outputStream, JSONObject().put("type", "pair_probe").put("version", 3).toString())
                    val reply = JSONObject(M4Wire.readControl(inputStream))
                    M4Wire.record(reply, "pair_identity", "desktop_id", "name")
                    require(reply.getString("desktop_id") == desktopId) { "Desktop identity mismatch" }
                    return desktopId
                }
            }
        }
    }
    fun socket(host: String, port: Int, desktopId: String, mutual: Boolean = true): SSLSocket {
        PairInvitation.validateHost(host)
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing desktop identity")
                validateCertificate(leaf, false)
                if (!MessageDigest.isEqual(peerId(leaf).toByteArray(Charsets.US_ASCII), desktopId.toByteArray(Charsets.US_ASCII)))
                    throw CertificateException("Desktop identity mismatch; scan a fresh invitation")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val manager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? {
                val selected = if (types?.any { it.startsWith("EC") } == true) alias else null
                android.util.Log.i("CamyllaTrust", "TLS client key selection types=${types?.joinToString()} selected=${selected != null}")
                return selected
            }
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? =
                chooseClientAlias(types, issuers, null)
            override fun getClientAliases(type: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
                if (type?.startsWith("EC") == true) arrayOf(alias) else null
            override fun getCertificateChain(requested: String?): Array<X509Certificate>? =
                if (requested == alias) arrayOf(certificate) else null
            override fun getPrivateKey(requested: String?): PrivateKey? = if (requested == alias) store.getKey(alias, null) as PrivateKey else null
            override fun getServerAliases(type: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
        }
        val ssl = SSLContext.getInstance("TLSv1.3").apply {
            init(if (mutual) arrayOf<KeyManager>(manager) else emptyArray(), arrayOf<TrustManager>(trust), SecureRandom())
        }
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), 1500)
            val socket = ssl.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.soTimeout = 1500
            socket.startHandshake()
            require(socket.session.protocol == "TLSv1.3") { "TLS 1.3 unavailable" }
            if (mutual) {
                val offered = socket.session.localCertificates?.firstOrNull() as? X509Certificate
                    ?: throw CertificateException("Android Keystore client identity not offered")
                require(peerId(offered) == phoneId && privateKeyNonexportable) { "Wrong client identity" }
                android.util.Log.i("CamyllaTrust", "TLS=${socket.session.protocol} port=$port client_id=$phoneId key_nonexportable=$privateKeyNonexportable provider=${ssl.provider.name}")
            }
            socket.soTimeout = 5000
            return socket
        } catch (error: Exception) { plain.close(); throw error }
    }
    fun desktops(): List<TrustedDesktop> = readStore()
    fun trust(peer: TrustedDesktop, replaceId: String? = null) = synchronized(STORE_LOCK) {
        require(pendingRotation == null) { "Resolve the pending key change before pairing" }
        PairInvitation.hex(peer.id, 32)
        val previous = readStore().filter { it.id != peer.id }.map {
            if (it.id == replaceId) it.copy(revoked = true, updatedAt = System.currentTimeMillis()) else it
        }
        require(previous.size < 32) { "Trust list full" }
        writeStore(previous + peer.copy(revoked = false))
    }
    fun revoke(id: String) = synchronized(STORE_LOCK) {
        writeStore(readStore().map { if (it.id == id) it.copy(revoked = true, updatedAt = System.currentTimeMillis()) else it })
    }
    private fun storageKey(): SecretKey {
        if (!store.containsAlias(STORAGE_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(STORAGE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        return store.getKey(STORAGE_ALIAS, null) as SecretKey
    }
    private fun readStore(): List<TrustedDesktop> = synchronized(STORE_LOCK) {
        val data = readProtectedState() ?: return@synchronized emptyList()
        require(data.getString("phone_id") == phoneId) { "Phone identity changed; re-pair required" }
        val peers = data.getJSONArray("desktops")
        require(peers.length() <= 32)
        (0 until peers.length()).map { i -> peers.getJSONObject(i).let {
            TrustedDesktop(it.getString("id"), it.getString("name"), it.getString("host"),
                it.getString("certificate"), it.getBoolean("revoked"), it.getLong("updated_at"))
        } }
    }
    private fun readProtectedState(): JSONObject? = synchronized(STORE_LOCK) {
        val file = File(context.noBackupFilesDir, "trust-v3.dat")
        if (!file.exists()) return@synchronized null
        val bytes = AtomicFile(file).readFully()
        require(bytes.size in 29..131072) { "Invalid trust store" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } finally { plain.fill(0) }
    }
    private fun writeStore(peers: List<TrustedDesktop>) {
        val state = checkedState().put("desktops", peersJson(peers))
        writeProtectedState(state)
    }
    private fun checkedState(): JSONObject {
        val state = readProtectedState() ?: JSONObject().put("phone_id", phoneId).put("active_alias", alias).put("desktops", JSONArray())
        require(state.getString("phone_id") == phoneId) { "Phone identity changed; re-pair required" }
        if (!state.has("active_alias")) state.put("active_alias", alias)
        return state
    }
    private fun peersJson(peers: List<TrustedDesktop>): JSONArray {
        val array = JSONArray()
        peers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
            .put("certificate", it.certificate).put("revoked", it.revoked).put("updated_at", it.updatedAt)) }
        return array
    }
    private fun writeProtectedState(state: JSONObject) {
        val plain = state.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 131_044) { "Trust store full" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        val bytes = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val file = AtomicFile(File(context.noBackupFilesDir, "trust-v3.dat"))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private val STORE_LOCK = Any()
        // Retain the unusable prototype key: never delete or mutate remembered keys on upgrade.
        private const val IDENTITY_ALIAS = "camylla-v3-phone-tls"
        private const val STORAGE_ALIAS = "camylla-v3-storage"
        fun peerId(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun validateCertificate(cert: X509Certificate, client: Boolean) {
            cert.checkValidity()
            val key = cert.publicKey as? ECPublicKey ?: throw CertificateException("P-256 identity required")
            val parameters = java.security.AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            if (key.params.order != parameters.order || key.params.generator != parameters.generator ||
                cert.sigAlgOID != "1.2.840.10045.4.3.2") throw CertificateException("Invalid identity algorithm")
            if (cert.keyUsage?.firstOrNull() == false) throw CertificateException("Invalid signing usage")
            val usage = cert.extendedKeyUsage
            if (usage != null && !usage.contains(if (client) "1.3.6.1.5.5.7.3.2" else "1.3.6.1.5.5.7.3.1"))
                throw CertificateException("Invalid TLS usage")
            if (cert.subjectX500Principal != cert.issuerX500Principal) throw CertificateException("Private identity must be self-signed")
            cert.verify(cert.publicKey)
        }
    }
}
