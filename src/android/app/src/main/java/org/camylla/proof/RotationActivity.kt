package org.camylla.proof

import android.app.Activity
import android.os.Bundle
import android.widget.*
import java.util.concurrent.Executors

class RotationActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private var proposal: PhoneRotation? = null
    private var submitted = false
    @Volatile private var destroyed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inset = MobileUi.dp(this, 20)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inset, inset, inset, inset); setBackgroundColor(MobileUi.BACKGROUND) }
        layout.addView(TextView(this).apply { text = "Change security key"; MobileUi.text(this, 26f) })
        status = TextView(this).apply { text = "Preparing…"; setPadding(0, inset, 0, inset); MobileUi.text(this, 16f, true) }
        layout.addView(status)
        confirm = Button(this).apply { text = "Confirm key change"; MobileUi.button(this, prominent = true); isEnabled = false; setOnClickListener { execute() } }
        layout.addView(confirm)
        layout.addView(Button(this).apply { text = "Back"; MobileUi.button(this); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(layout) })
        executor.execute {
            val result = runCatching {
                val identity = ProductIdentity(this)
                identity.pendingRotation ?: run {
                    val peer = identity.desktops().single { it.id == intent.getStringExtra("peer") && !it.revoked }
                    if (intent.getStringExtra("role") == "desktop") RotationClient(this).desktopOffer(peer)
                    else identity.newPhoneRotation(peer)
                }
            }
            if (destroyed) result.onSuccess { ProductIdentity(this).discardProposalKey(it) }
            runOnUiThread {
                if (!destroyed) result.onSuccess {
                    proposal = it
                    val resuming = ProductIdentity(this).pendingRotation != null
                    confirm.text = if (resuming) "Resume key change" else "Confirm key change"
                    confirm.isEnabled = true
                    status.text = "${if (it.role == "phone") "Phone" else "PC"} key\n\nPrevious fingerprint:\n${it.oldId.chunked(8).joinToString(" ")}\n\nNew fingerprint:\n${it.newId.chunked(8).joinToString(" ")}\n\nCapture stays stopped. Other paired ${if (it.role == "phone") "PCs" else "phones"} will need a fresh pairing."
                }.onFailure { status.text = "No key change available. For a PC change, open Change PC key on the desktop first." }
            }
        }
    }
    private fun execute() {
        val value = proposal ?: return
        submitted = true
        confirm.isEnabled = false
        executor.execute {
            val client = RotationClient(this) { text -> runOnUiThread { if (!destroyed) status.text = text } }
            val result = runCatching {
                if (ProductIdentity(this).pendingRotation != null) client.resume() else client.execute(value)
            }
            runOnUiThread {
                if (!destroyed) result.onSuccess {
                    Toast.makeText(this, "Security key changed. Capture stays stopped.", Toast.LENGTH_LONG).show(); finish()
                }.onFailure {
                    status.text = "Key change interrupted. Resume to verify its result; capture stays stopped."
                    confirm.text = "Resume key change"; confirm.isEnabled = ProductIdentity(this).pendingRotation != null
                }
            }
        }
    }
    override fun onDestroy() {
        destroyed = true
        if (!submitted) proposal?.let { runCatching { ProductIdentity(this).discardProposalKey(it) } }
        executor.shutdownNow(); super.onDestroy()
    }
}
