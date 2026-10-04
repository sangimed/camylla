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
        // Exercise both authenticated channels without opening a camera or producing frames.
        identity.socket(peer.host, 58413, peer.id).use { control ->
            val nonce = ProductIdentity.randomHex(16)
            M4Wire.writeControl(control.outputStream, JSONObject().put("type", "start").put("version", 3)
                .put("phone_id", identity.phoneId).put("client_nonce", nonce).put("codec", "h264")
                .put("modes", JSONArray().put("1280x720@30")).put("mode", "1280x720@30").put("lens", "rear").toString())
            val started = JSONObject(M4Wire.readControl(control.inputStream))
            M4Wire.record(started, "started", "session_id", "client_nonce", "server_nonce", "video_ticket", "codec", "mode", "fps")
            assertTrue(started.getString("client_nonce") == nonce)
            identity.socket(peer.host, 58414, peer.id).use { video ->
                M4Wire.writeControl(video.outputStream, JSONObject().put("type", "bind").put("version", 3)
                    .put("session_id", started.getString("session_id")).put("phone_id", identity.phoneId)
                    .put("client_nonce", nonce).put("server_nonce", started.getString("server_nonce"))
                    .put("video_ticket", started.getString("video_ticket")).toString())
                val ready = JSONObject(M4Wire.readControl(video.inputStream))
                M4Wire.record(ready, "ready", "session_id")
                assertTrue(ready.getString("session_id") == started.getString("session_id"))
                M4Wire.writeControl(control.outputStream, JSONObject().put("type", "stop").put("version", 3)
                    .put("session_id", started.getString("session_id")).toString())
                var reply = JSONObject(M4Wire.readControl(control.inputStream))
                if (reply.getString("type") == "sync") reply = JSONObject(M4Wire.readControl(control.inputStream))
                M4Wire.record(reply, "stopped", "session_id")
            }
        }
    }
}
