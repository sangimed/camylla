package org.camylla.proof

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

class PairActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private lateinit var status: TextView
    private lateinit var invitation: EditText
    private lateinit var qrAddress: EditText
    private lateinit var preview: PreviewView
    private lateinit var begin: Button
    private lateinit var pairByCode: Button
    private lateinit var scan: Button
    private lateinit var codeForm: LinearLayout
    private lateinit var pairCode: EditText
    private lateinit var codeSwitch: Button
    private val identity by lazy { ProductIdentity(this) }
    @Volatile private var destroyed = false
    @Volatile private var scanned = false
    private val cameraPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (it) scanWithPermission() else {
            codeForm.visibility = android.view.View.VISIBLE; preview.visibility = android.view.View.GONE
            codeSwitch.visibility = android.view.View.GONE; scan.visibility = android.view.View.VISIBLE
            status.text = "Camera access denied. Enter the PC pairing code instead."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inset = MobileUi.dp(this, 20)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inset, inset, inset, inset); setBackgroundColor(MobileUi.BACKGROUND) }
        layout.addView(TextView(this).apply { text = "Connect a PC"; MobileUi.text(this, 26f) })
        layout.addView(TextView(this).apply { text = "Enter the one-time code shown by Camylla on your PC. Pairing never starts capture."; setPadding(0, 0, 0, inset); MobileUi.text(this, 16f, true) })
        codeForm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairCode = EditText(this).apply {
            hint = "8-digit code"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            MobileUi.input(this)
        }
        codeForm.addView(pairCode)
        pairByCode = Button(this).apply { text = "Connect"; MobileUi.button(this, prominent = true); setOnClickListener { beginCodePair() } }
        codeForm.addView(pairByCode)
        layout.addView(codeForm)
        preview = PreviewView(this)
        preview.visibility = android.view.View.GONE
        layout.addView(preview, LinearLayout.LayoutParams(-1, MobileUi.dp(this, 320)))
        scan = Button(this).apply { text = "Use QR instead"; MobileUi.button(this); setOnClickListener {
            codeForm.visibility = android.view.View.GONE; preview.visibility = android.view.View.VISIBLE
            codeSwitch.visibility = android.view.View.VISIBLE
            text = "Scan QR"; scanWithPermission()
        } }
        layout.addView(scan)
        invitation = EditText(this).apply {
            hint = "Or paste the invitation shown by your PC"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(512))
            MobileUi.input(this)
        }
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        manual.addView(invitation)
        qrAddress = EditText(this).apply { hint = "Optional LAN address override"; setSingleLine(true); MobileUi.input(this) }
        manual.addView(qrAddress)
        status = TextView(this).apply { setPadding(0, inset / 2, 0, inset / 2); MobileUi.text(this, 16f, true) }
        layout.addView(status)
        begin = Button(this).apply { text = "Pair using invitation"; MobileUi.button(this, prominent = true); setOnClickListener { beginPair() } }
        manual.addView(begin)
        layout.addView(manual)
        codeSwitch = Button(this).apply {
            text = "Enter code instead"; MobileUi.button(this); visibility = android.view.View.GONE
            setOnClickListener {
                provider?.unbindAll()
                codeForm.visibility = android.view.View.VISIBLE
                preview.visibility = android.view.View.GONE
                scan.visibility = android.view.View.VISIBLE
                codeSwitch.visibility = android.view.View.GONE
            }
        }
        layout.addView(codeSwitch)
        layout.addView(Button(this).apply { text = "Back"; MobileUi.button(this); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(layout) })
    }
    private fun scanWithPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        scanned = false
        scan.visibility = android.view.View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (destroyed) return@addListener
                provider = future.get()
                val view = Preview.Builder().build().apply { setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (!scanned && !destroyed) {
                            val width = image.width; val height = image.height
                            if (width * height > 1920 * 1080) return@setAnalyzer
                            val plane = image.planes[0]
                            val source = plane.buffer.duplicate()
                            val pixels = ByteArray(width * height)
                            for (y in 0 until height) for (x in 0 until width)
                                pixels[y * width + x] = source.get(y * plane.rowStride + x * plane.pixelStride)
                            val luminance = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                            val text = reader.decode(BinaryBitmap(HybridBinarizer(luminance))).text
                            PairInvitation.parse(text)
                            scanned = true
                            runOnUiThread {
                                if (!destroyed) {
                                    provider?.unbindAll(); invitation.setText(text)
                                    beginPair()
                                }
                            }
                        }
                    } catch (_: NotFoundException) { }
                    catch (_: Exception) { }
                    finally { image.close(); reader.reset() }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
                status.text = "Point at the PC pairing QR."
            } catch (_: Exception) {
                codeForm.visibility = android.view.View.VISIBLE; preview.visibility = android.view.View.GONE
                codeSwitch.visibility = android.view.View.GONE; scan.visibility = android.view.View.VISIBLE
                status.text = "Scanner unavailable. Enter the PC pairing code instead."
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun beginPair() {
        if (identity.pendingRotation != null) {
            status.text = "Resume the pending security key change before pairing."; return
        }
        try {
            var invite = PairInvitation.parse(invitation.text.toString().trim())
            val override = qrAddress.text.toString().trim()
            if (override.isNotEmpty()) { PairInvitation.validateHost(override); invite = invite.copy(host = override.trim('[', ']')) }
            provider?.unbindAll()
            busy(true); status.text = "Pairing securely…"
            executor.execute {
                var phase = "invitation"
                val result = runCatching {
                    val client = PairClient(identity)
                    val pair = client.begin(invite, Build.MODEL)
                    if (pair.requiresApproval) runOnUiThread {
                        if (!destroyed) status.text = "Approve this pairing request on your PC. Pairing finishes automatically."
                    }
                    phase = "certificate proof"
                    check(!destroyed) { "Pairing cancelled" }
                    val known = identity.desktops()
                    val previous = if (known.any { it.id == invite.desktopId }) null else known.filter { it.id != invite.desktopId && it.name == pair.desktopName }
                        .sortedWith(compareBy<TrustedDesktop> { it.revoked }.thenByDescending { it.updatedAt }).firstOrNull()
                    if (previous != null) confirmDesktopReplacement(previous, pair)
                    client.commit(pair, previous?.id)
                }
                result.onFailure { error ->
                    android.util.Log.w("CamyllaTrust", "Pairing failed phase=$phase exception=${error.javaClass.simpleName}")
                }
                runOnUiThread {
                    if (!destroyed) {
                        busy(false)
                        result.onSuccess {
                            Toast.makeText(this, "Paired with ${it.name}", Toast.LENGTH_SHORT).show()
                            finish()
                        }.onFailure {
                            status.text = "Connection failed. Refresh the PC QR, then scan again."
                            scan.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            }
        } catch (_: Exception) { status.text = "Invalid or expired invitation. Scan a fresh PC QR." }
    }
    private fun beginCodePair() {
        if (identity.pendingRotation != null) { status.text = "Resume the pending security key change before pairing."; return }
        val code = pairCode.text.toString()
        try {
            PairingCode.normalize(code)
        } catch (_: Exception) { status.text = "Enter the 8-digit code shown on your PC."; return }
        busy(true); status.text = "Finding your PC on the local network…"
        executor.execute {
            val result = runCatching {
                val desktops = PairingDiscovery.findDesktops(PairingCode.normalize(code))
                require(desktops.isNotEmpty()) { "No PC is ready. Open Pair a phone on Camylla for PC, then try again." }
                val desktop = if (desktops.size == 1) desktops.single() else chooseDesktop(desktops)
                status.post { if (!destroyed) status.text = "Connecting to ${desktop.name}… Approve the request on that PC." }
                val client = PairClient(identity)
                val pair = client.beginCode(desktop.host, desktop.desktopId, PairingCode.normalize(code), Build.MODEL)
                val known = identity.desktops()
                val previous = if (known.any { it.id == desktop.desktopId }) null else known.filter { it.id != desktop.desktopId && it.name == pair.desktopName }
                    .sortedWith(compareBy<TrustedDesktop> { it.revoked }.thenByDescending { it.updatedAt }).firstOrNull()
                if (previous != null) confirmDesktopReplacement(previous, pair)
                client.commit(pair, previous?.id)
            }
            result.onFailure { error ->
                android.util.Log.w("CamyllaTrust", "Code pairing failed exception=${error.javaClass.simpleName}")
            }
            runOnUiThread {
                if (!destroyed) {
                    busy(false)
                    result.onSuccess {
                        Toast.makeText(this, "Paired with ${it.name}", Toast.LENGTH_SHORT).show()
                        finish()
                    }.onFailure { error -> status.text = error.message ?: "Pairing failed. Create a fresh code on the PC." }
                }
            }
        }
    }
    private fun chooseDesktop(desktops: List<PairingDesktop>): PairingDesktop {
        val decision = java.util.concurrent.CountDownLatch(1)
        val selected = java.util.concurrent.atomic.AtomicReference<PairingDesktop?>()
        val labels = desktops.map { it.name }.toTypedArray()
        runOnUiThread {
            if (destroyed) { decision.countDown(); return@runOnUiThread }
            android.app.AlertDialog.Builder(this).setTitle("Choose a PC")
                .setItems(labels) { _, which -> selected.set(desktops[which]); decision.countDown() }
                .setOnCancelListener { decision.countDown() }.show()
        }
        check(decision.await(30, java.util.concurrent.TimeUnit.SECONDS) && !destroyed) { "PC selection cancelled" }
        return selected.get() ?: error("Choose a PC to continue")
    }
    private fun confirmDesktopReplacement(previous: TrustedDesktop, pair: PendingPair) {
        val decision = java.util.concurrent.CountDownLatch(1)
        val approved = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog>()
        runOnUiThread {
            if (destroyed) { decision.countDown(); return@runOnUiThread }
            dialog.set(android.app.AlertDialog.Builder(this)
                .setTitle("Replace this PC's identity?")
                .setMessage("PC: ${pair.desktopName}\n\nPrevious fingerprint:\n${previous.id}\n\nNew fingerprint:\n${pair.invitation.desktopId}\n\nOnly approve a change you requested. The previous PC identity will be revoked locally.")
                .setPositiveButton("Approve replacement") { _, _ -> approved.set(true); decision.countDown() }
                .setNegativeButton("Cancel") { _, _ -> decision.countDown() }
                .setOnCancelListener { decision.countDown() }.show())
        }
        try {
            val remaining = (30_000 - (android.os.SystemClock.elapsedRealtime() - pair.startedAt)).coerceAtLeast(0)
            check(decision.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) && approved.get() && !destroyed) {
                "PC replacement cancelled or expired"
            }
        } finally { runOnUiThread { dialog.get()?.dismiss() } }
    }
    private fun busy(value: Boolean) { begin.isEnabled = !value; pairByCode.isEnabled = !value; scan.isEnabled = !value; codeSwitch.isEnabled = !value; invitation.isEnabled = !value; pairCode.isEnabled = !value }
    override fun onDestroy() { destroyed = true; provider?.unbindAll(); executor.shutdownNow(); super.onDestroy() }
}
