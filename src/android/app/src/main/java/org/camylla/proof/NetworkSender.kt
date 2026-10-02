package org.camylla.proof

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class NetworkProgress(
    val state: String = "Disconnected", val queuedFrames: Long = 0, val sentFrames: Long = 0,
    val sentBytes: Long = 0, val depth: Int = 0, val highWater: Int = 0, val resets: Long = 0,
    val droppedFrames: Long = 0, val keyframes: Long = 0, val networkErrors: Long = 0,
    val reconnectAttempts: Long = 0, val lastSequence: Long = 0, val error: String = "",
)

/** One explicit phone-started session. Socket work is confined to worker/control threads. */
class NetworkSender(
    private val host: String, pinHex: String, private val secret: String,
    private val mode: String, private val lens: String,
    private val requestSyncFrame: () -> Unit,
) : AutoCloseable {
    private data class PendingUnit(val payload: ByteArray, val ptsUs: Long, val flag: Int,
                                   val queuedNs: Long, val generation: Int)
    private val lock = Object()
    private val queue = ArrayDeque<PendingUnit>()
    private val pin = parsePin(pinHex)
    private val tls = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(PinnedTrust(pin)), null)
    }.socketFactory
    private val worker = Thread(::run, "camylla-network-writer")
    @Volatile private var stopping = false
    @Volatile private var videoSocket: SSLSocket? = null
    @Volatile private var controlSocket: SSLSocket? = null
    @Volatile private var progress = NetworkProgress()
    private var config: ByteArray? = null
    private var generation = 0
    private var waitingForKey = true
    private var queueBytes = 0
    private var sequence = 0L

    init {
        require(host.isNotBlank() && secret.length == 64 && secret.all { it.digitToIntOrNull(16) != null })
        require(mode in listOf("1280x720@30", "1920x1080@30") && lens in listOf("rear", "front"))
        worker.start()
    }

    fun stats(): NetworkProgress = progress

    fun newEncoder() = synchronized(lock) {
        generation++
        config = null
        resetLocked()
    }

    fun codecConfig(bytes: ByteArray) = synchronized(lock) {
        if (bytes.isNotEmpty() && bytes.size <= M3Wire.MAX_VIDEO_BYTES) {
            val changed = config != null && !config!!.contentEquals(bytes)
            config = bytes.copyOf()
            if (changed) { generation++; resetLocked() }
        }
    }

    fun offer(bytes: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (stopping) return@synchronized
        if (bytes.isEmpty() || bytes.size > M3Wire.MAX_VIDEO_BYTES) { resetLocked(); return@synchronized }
        val now = SystemClock.elapsedRealtimeNanos()
        if (queue.isNotEmpty() && now - queue.first.queuedNs > MAX_AGE_NS ||
            queue.size + 2 > MAX_DEPTH || queueBytes + bytes.size + (if (waitingForKey) config?.size ?: 0 else 0) > MAX_QUEUE_BYTES) {
            resetLocked()
        }
        if (waitingForKey) {
            if (!key || config == null) {
                progress = progress.copy(droppedFrames = progress.droppedFrames + 1)
                return@synchronized
            }
            queue.addLast(PendingUnit(config!!.copyOf(), ptsUs, M3Wire.CONFIG, now, generation))
            queueBytes += config!!.size
            waitingForKey = false
        }
        queue.addLast(PendingUnit(bytes, ptsUs, if (key) M3Wire.KEY else M3Wire.NORMAL, now, generation))
        queueBytes += bytes.size
        progress = progress.copy(queuedFrames = progress.queuedFrames + 1,
            depth = queue.size, highWater = maxOf(progress.highWater, queue.size),
            keyframes = progress.keyframes + if (key) 1 else 0)
        lock.notifyAll()
    }

    private fun resetLocked(closeVideo: Boolean = true) {
        progress = progress.copy(resets = progress.resets + 1,
            droppedFrames = progress.droppedFrames + queue.count { it.flag != M3Wire.CONFIG }, depth = 0)
        queue.clear(); queueBytes = 0; waitingForKey = true
        if (closeVideo) runCatching { videoSocket?.close() }
        requestSyncFrame()
        lock.notifyAll()
    }

    private fun run() {
        var retries = 0
        while (!stopping && retries < MAX_RETRIES) {
            try {
                progress = progress.copy(state = if (retries == 0) "Connecting" else "Recovering",
                    reconnectAttempts = progress.reconnectAttempts + 1)
                val control = connect(CONTROL_PORT)
                controlSocket = control
                progress = progress.copy(state = "Authenticating")
                val hello = JSONObject().put("type", "hello").put("version", M3Wire.VERSION)
                    .put("codec", "h264").put("mode", mode).put("lens", lens)
                    .put("modes", JSONArray(listOf("1280x720@30", "1920x1080@30")))
                    .put("secret", secret)
                M3Wire.writeControl(control.outputStream, hello.toString())
                val welcome = JSONObject(M3Wire.readControl(control.inputStream))
                if (welcome.optString("type") != "welcome" || welcome.optInt("version") != M3Wire.VERSION ||
                    welcome.optString("mode") != mode || welcome.optString("codec") != "h264" ||
                    welcome.optInt("fps") != 30)
                    throw IllegalStateException("Capability negotiation rejected: $welcome")
                val id = M3Wire.hex16(welcome.getString("session"))
                progress = progress.copy(state = "Negotiating")
                val reader = Thread({ readControl(control) }, "camylla-control-reader").apply { isDaemon = true; start() }
                var videoFailures = 0
                while (!stopping && !control.isClosed) {
                    try {
                        progress = progress.copy(reconnectAttempts = progress.reconnectAttempts + 1)
                        val video = connect(VIDEO_PORT)
                        videoSocket = video
                        val bind = JSONObject().put("type", "bind").put("version", M3Wire.VERSION)
                            .put("session", welcome.getString("session")).put("secret", secret)
                        M3Wire.writeControl(video.outputStream, bind.toString())
                        val ready = JSONObject(M3Wire.readControl(video.inputStream))
                        if (ready.optString("type") != "ready" || ready.optString("session") != welcome.getString("session"))
                            throw IllegalStateException("Video channel binding rejected")
                        videoFailures = 0
                        synchronized(lock) { resetLocked(closeVideo = false) }
                        progress = progress.copy(state = "Streaming")
                        while (!stopping && !video.isClosed && !control.isClosed) {
                            val unit = synchronized(lock) {
                                while (queue.isEmpty() && !stopping && !video.isClosed && !control.isClosed) lock.wait(200)
                                if (queue.isEmpty()) null else queue.removeFirst().also {
                                    queueBytes -= it.payload.size
                                    progress = progress.copy(depth = queue.size)
                                }
                            } ?: continue
                            if (SystemClock.elapsedRealtimeNanos() - unit.queuedNs > MAX_AGE_NS) {
                                synchronized(lock) { resetLocked() }
                                continue
                            }
                            val next = ++sequence
                            val sendUs = SystemClock.elapsedRealtimeNanos() / 1000
                            val packet = M3Wire.video(next, unit.generation, unit.ptsUs, sendUs, id, unit.flag, unit.payload)
                            video.outputStream.write(packet)
                            progress = progress.copy(sentFrames = progress.sentFrames + if (unit.flag == M3Wire.CONFIG) 0 else 1,
                                sentBytes = progress.sentBytes + packet.size, lastSequence = next)
                        }
                    } catch (error: Exception) {
                        if (!stopping) reportError("Video: ${error.message}")
                        videoFailures++
                        if (videoFailures >= MAX_RETRIES) throw IllegalStateException("Video retry limit reached", error)
                    } finally {
                        runCatching { videoSocket?.close() }; videoSocket = null
                        synchronized(lock) { queue.clear(); queueBytes = 0; waitingForKey = true; progress = progress.copy(depth = 0) }
                        if (!stopping) progress = progress.copy(state = "Recovering")
                    }
                    if (!stopping && !control.isClosed) Thread.sleep(RETRY_DELAY_MS)
                }
            } catch (error: Exception) {
                if (!stopping) reportError("Control: ${error.message}")
            } finally {
                runCatching { videoSocket?.close() }; videoSocket = null
                runCatching { controlSocket?.close() }; controlSocket = null
                synchronized(lock) { queue.clear(); queueBytes = 0; waitingForKey = true; progress = progress.copy(depth = 0) }
            }
            retries++
            if (!stopping) Thread.sleep(RETRY_DELAY_MS)
        }
        progress = progress.copy(state = if (stopping) "Stopping" else "Error")
    }

    private fun readControl(control: SSLSocket) {
        try {
            while (!stopping && !control.isClosed) {
                val message = JSONObject(M3Wire.readControl(control.inputStream))
                when (message.optString("type")) {
                    "sync" -> synchronized(lock) { resetLocked(closeVideo = false) }
                    "pong" -> Unit
                    "error" -> throw IllegalStateException(message.optString("message", "Receiver error"))
                    else -> throw IllegalStateException("Unknown control message")
                }
            }
        } catch (error: Exception) {
            if (!stopping) reportError("Control read: ${error.message}")
            runCatching { control.close() }
            runCatching { videoSocket?.close() }
        }
    }

    private fun connect(port: Int): SSLSocket {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val socket = tls.createSocket(plain, host, port, true) as SSLSocket
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 0
            return socket
        } catch (error: Exception) { plain.close(); throw error }
    }

    private fun reportError(message: String) {
        Log.e("CamyllaProof", message)
        progress = progress.copy(networkErrors = progress.networkErrors + 1, error = message)
    }

    override fun close() {
        stopping = true
        synchronized(lock) { queue.clear(); queueBytes = 0; lock.notifyAll() }
        runCatching { videoSocket?.close() }
        runCatching { controlSocket?.close() }
        worker.join(2000)
    }

    private class PinnedTrust(private val pin: ByteArray) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Client certificates are not used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing receiver certificate")
            leaf.checkValidity()
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded), pin))
                throw CertificateException("Receiver certificate pin mismatch")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val CONTROL_PORT = 58413
        private const val VIDEO_PORT = 58414
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val RETRY_DELAY_MS = 250L
        private const val MAX_RETRIES = 20
        private const val MAX_DEPTH = 4
        private const val MAX_QUEUE_BYTES = 8 * 1024 * 1024
        private const val MAX_AGE_NS = 250_000_000L
        private fun parsePin(value: String): ByteArray {
            val clean = value.replace(":", "").trim()
            require(clean.length == 64) { "Certificate pin must be 64 hex characters" }
            return ByteArray(32) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
    }
}
