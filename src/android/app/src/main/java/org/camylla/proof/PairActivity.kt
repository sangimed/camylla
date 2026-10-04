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
    private lateinit var address: EditText
    private lateinit var preview: PreviewView
    private lateinit var begin: Button
    private lateinit var scan: Button
    private val identity by lazy { ProductIdentity(this) }
    @Volatile private var destroyed = false
    @Volatile private var scanned = false
    private val cameraPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (it) scanWithPermission() else status.text = "Camera access denied. Paste the PC invitation instead."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply { text = "Scan the PC QR"; textSize = 26f })
        layout.addView(TextView(this).apply { text = "Pairing is automatic. Capture stays stopped." })
        preview = PreviewView(this)
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        scan = Button(this).apply { text = "Scan again"; visibility = android.view.View.GONE; setOnClickListener { scanWithPermission() } }
        layout.addView(scan)
        invitation = EditText(this).apply {
            hint = "Or paste the invitation shown by your PC"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(512))
        }
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        manual.addView(invitation)
        address = EditText(this).apply { hint = "Optional LAN address override"; setSingleLine(true) }
        manual.addView(address)
        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)
        begin = Button(this).apply { text = "Pair using invitation"; setOnClickListener { beginPair() } }
        manual.addView(begin)
        layout.addView(manual)
        layout.addView(Button(this).apply {
            text = "Can't scan?"
            setOnClickListener {
                provider?.unbindAll()
                manual.visibility = android.view.View.VISIBLE
                preview.visibility = android.view.View.GONE
                visibility = android.view.View.GONE
            }
        })
        layout.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        setContentView(layout)
        scanWithPermission()
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
            } catch (_: Exception) { status.text = "Scanner unavailable. Paste the PC invitation instead." }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun beginPair() {
        if (identity.pendingRotation != null) {
            status.text = "Resume the pending security key change before pairing."; return
        }
        try {
            var invite = PairInvitation.parse(invitation.text.toString().trim())
            val override = address.text.toString().trim()
            if (override.isNotEmpty()) { PairInvitation.validateHost(override); invite = invite.copy(host = override.trim('[', ']')) }
            provider?.unbindAll()
            busy(true); status.text = "Pairing securely…"
            executor.execute {
                var phase = "invitation"
                val result = runCatching {
                    val client = PairClient(identity)
                    val pair = client.begin(invite, Build.MODEL)
                    if (pair.requiresApproval) runOnUiThread {
                        if (!destroyed) status.text = "Approve this phone's changed identity on your PC. Pairing finishes automatically."
                    }
                    phase = "certificate proof"
                    check(!destroyed) { "Pairing cancelled" }
                    client.commit(pair)
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
    private fun busy(value: Boolean) { begin.isEnabled = !value; scan.isEnabled = !value; invitation.isEnabled = !value; address.isEnabled = !value }
    override fun onDestroy() { destroyed = true; provider?.unbindAll(); executor.shutdownNow(); super.onDestroy() }
}
