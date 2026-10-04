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

class ProductIdentity(private val context: Context, private val alias: String = IDENTITY_ALIAS) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(alias)) {
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
    }
    val certificate: X509Certificate get() = store.getCertificate(alias) as X509Certificate
    val phoneId: String get() = peerId(certificate)
    val privateKeyNonexportable: Boolean get() = store.getKey(alias, null).encoded == null
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
        val file = File(context.noBackupFilesDir, "trust-v3.dat")
        if (!file.exists()) return@synchronized emptyList()
        val bytes = AtomicFile(file).readFully()
        require(bytes.size in 29..131072) { "Invalid trust store" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            val data = JSONObject(plain.toString(Charsets.UTF_8))
            require(data.getString("phone_id") == phoneId) { "Phone identity changed; re-pair required" }
            val peers = data.getJSONArray("desktops")
            require(peers.length() <= 32)
            (0 until peers.length()).map { i -> peers.getJSONObject(i).let {
                TrustedDesktop(it.getString("id"), it.getString("name"), it.getString("host"),
                    it.getString("certificate"), it.getBoolean("revoked"), it.getLong("updated_at"))
            } }
        } finally { plain.fill(0) }
    }
    private fun writeStore(peers: List<TrustedDesktop>) {
        val array = JSONArray()
        peers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
            .put("certificate", it.certificate).put("revoked", it.revoked).put("updated_at", it.updatedAt)) }
        val plain = JSONObject().put("phone_id", phoneId).put("desktops", array).toString().toByteArray(Charsets.UTF_8)
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
