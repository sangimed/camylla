package org.camylla.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in hardware test: only consumes an explicitly prepared local invitation. Never starts capture. */
@RunWith(AndroidJUnit4::class)
class PairingHardwareTest {
    @Test fun scannerHasNoVisibleManualOrApprovalSteps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context, PairActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val visible = mutableListOf<android.view.View>()
                fun visit(view: android.view.View) {
                    if (view.visibility != android.view.View.VISIBLE) return
                    visible.add(view)
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                // Check app content even when the physical device's keyguard covers its window.
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                for (index in 0 until content.childCount) visit(content.getChildAt(index))
                val labels = visible.filterIsInstance<android.widget.TextView>().map { it.text.toString() }
                assertTrue("Scanner title missing: $labels", labels.contains("Scan the PC QR"))
                assertTrue(visible.none { it is android.widget.EditText })
                assertTrue(labels.none { it.contains("Approve") || it.contains("Finish") || it.contains("Review") || it == "Pair using invitation" })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun pairWithNonexportableKeystoreIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "pairing-invitation-test")
        val invitation = PairInvitation.parse(file.readText().trim())
        file.delete()
        val identity = ProductIdentity(context)
        assertTrue(identity.privateKeyNonexportable)
        val client = PairClient(identity)
        val peer = client.commit(client.begin(invitation, android.os.Build.MODEL))
        assertTrue(identity.desktops().any { it.id == peer.id && !it.revoked })
        exerciseSession(identity, peer)
    }

    @Test fun rememberedTrustConnectsWithoutInvitation() {
        val identity = ProductIdentity(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = identity.desktops().single { !it.revoked }
        assertTrue(identity.privateKeyNonexportable)
        exerciseSession(identity, peer)
    }

    @Test fun revokedPhoneCannotReconnect() {
        val identity = ProductIdentity(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = identity.desktops().single { !it.revoked }
        for (port in listOf(58413, 58414)) {
            var rejected = false
            try {
                identity.socket(peer.host, port, peer.id).use { socket ->
                    M4Wire.writeControl(socket.outputStream, "{\"type\":\"ping\",\"version\":3}")
                    M4Wire.readControl(socket.inputStream)
                }
            } catch (_: java.io.IOException) { rejected = true }
            assertTrue("Revoked identity accepted on port $port", rejected)
        }
    }

    @Test fun missingClientCertificateIsRejected() {
        val identity = ProductIdentity(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = identity.desktops().single { !it.revoked }
        for (port in listOf(58413, 58414)) assertConnectionRejected {
            identity.socket(peer.host, port, peer.id, mutual = false)
        }
    }

    @Test fun wrongServerPinIsRejected() {
        val identity = ProductIdentity(InstrumentationRegistry.getInstrumentation().targetContext)
        val peer = identity.desktops().single { !it.revoked }
        val wrongPin = (if (peer.id[0] == '0') "1" else "0") + peer.id.substring(1)
        for (port in listOf(58413, 58414)) assertConnectionRejected {
            identity.socket(peer.host, port, wrongPin)
        }
    }

    @Test fun expiredClientCertificateIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val peer = ProductIdentity(context).desktops().single { !it.revoked }
        val alias = "camylla-expired-test-${java.util.UUID.randomUUID()}"
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            java.security.KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256, android.security.keystore.KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Camylla expired test"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setCertificateNotBefore(java.util.Date(System.currentTimeMillis() - 172_800_000))
                    .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() - 86_400_000)).build())
                generateKeyPair()
            }
            val expired = ProductIdentity(context, alias)
            assertTrue(expired.certificate.notAfter.before(java.util.Date()))
            assertTrue(expired.privateKeyNonexportable)
            for (port in listOf(58413, 58414)) assertConnectionRejected {
                expired.socket(peer.host, port, peer.id)
            }
        } finally { store.deleteEntry(alias) }
    }

    private fun assertConnectionRejected(connect: () -> javax.net.ssl.SSLSocket) {
        var rejected = false
        try {
            connect().use { socket ->
                M4Wire.writeControl(socket.outputStream, "{\"type\":\"ping\",\"version\":3}")
                M4Wire.readControl(socket.inputStream)
            }
        } catch (_: java.io.IOException) { rejected = true }
        assertTrue("Unexpectedly admitted invalid peer", rejected)
    }

    @Test fun activeSessionEndsOnRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = ProductIdentity(context)
        val peer = identity.desktops().single { !it.revoked }
        val ready = File(context.filesDir, "revocation-session-ready-test")
        ready.delete()
        try {
            exerciseSession(identity, peer) { control, video, sessionId ->
                control.soTimeout = 20_000
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val heartbeat = Thread({
                    var sequence = 0L
                    while (!done.get()) {
                        try {
                            M4Wire.writeControl(control.outputStream, "{\"type\":\"ping\",\"version\":3}")
                            // Keep video bound with configuration records, without camera capture or decoded frames.
                            val nowUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000
                            video.outputStream.write(M4Wire.video(++sequence, 1, nowUs, nowUs,
                                M4Wire.hex16(sessionId), M4Wire.CONFIG, byteArrayOf(0, 0, 0, 1, 0x67)))
                            Thread.sleep(500)
                        } catch (_: Exception) { break }
                    }
                }, "revocation-test-heartbeat").apply { isDaemon = true; start() }
                ready.writeText("ready")
                var closed = false
                try {
                    while (true) {
                        val record = JSONObject(M4Wire.readControl(control.inputStream))
                        assertTrue(record.getString("type") in listOf("sync", "pong", "peer_revoked"))
                    }
                } catch (_: java.net.SocketTimeoutException) { }
                catch (_: java.io.IOException) { closed = true }
                finally { done.set(true); heartbeat.interrupt(); heartbeat.join(1000) }
                assertTrue("Active control connection did not close", closed)
                video.soTimeout = 2000
                var videoClosed = false
                try { videoClosed = video.inputStream.read() == -1 }
                catch (_: java.net.SocketTimeoutException) { }
                catch (_: java.io.IOException) { videoClosed = true }
                assertTrue("Active video connection did not close", videoClosed)
            }
        } finally { ready.delete() }
    }

    private fun exerciseSession(identity: ProductIdentity, peer: TrustedDesktop,
        onBound: ((javax.net.ssl.SSLSocket, javax.net.ssl.SSLSocket, String) -> Unit)? = null) {
        // Exercise both authenticated channels without opening a camera or producing frames.
        identity.socket(peer.host, 58413, peer.id).use { control ->
            val nonce = ProductIdentity.randomHex(16)
            M4Wire.writeControl(control.outputStream, JSONObject().put("type", "start").put("version", 3)
                .put("phone_id", identity.phoneId).put("client_nonce", nonce).put("codec", "h264")
                .put("modes", JSONArray().put("1280x720@30")).put("mode", "1280x720@30").put("lens", "rear").toString())
            val started = JSONObject(M4Wire.readControl(control.inputStream))
            M4Wire.record(started, "started", "session_id", "client_nonce", "server_nonce", "video_ticket", "codec", "mode", "fps")
            assertTrue(started.getString("client_nonce") == nonce)
            identity.socket(peer.host, 58414, peer.id).use videoScope@ { video ->
                M4Wire.writeControl(video.outputStream, JSONObject().put("type", "bind").put("version", 3)
                    .put("session_id", started.getString("session_id")).put("phone_id", identity.phoneId)
                    .put("client_nonce", nonce).put("server_nonce", started.getString("server_nonce"))
                    .put("video_ticket", started.getString("video_ticket")).toString())
                val ready = JSONObject(M4Wire.readControl(video.inputStream))
                M4Wire.record(ready, "ready", "session_id")
                assertTrue(ready.getString("session_id") == started.getString("session_id"))
                if (onBound != null) { onBound(control, video, started.getString("session_id")); return@videoScope }
                M4Wire.writeControl(control.outputStream, JSONObject().put("type", "stop").put("version", 3)
                    .put("session_id", started.getString("session_id")).toString())
                var reply = JSONObject(M4Wire.readControl(control.inputStream))
                if (reply.getString("type") == "sync") reply = JSONObject(M4Wire.readControl(control.inputStream))
                M4Wire.record(reply, "stopped", "session_id")
            }
        }
    }
}
