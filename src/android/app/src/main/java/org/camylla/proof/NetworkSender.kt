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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class NetworkProgress(
    val state: String = "Disconnected", val queuedFrames: Long = 0, val sentFrames: Long = 0,
    val sentBytes: Long = 0, val depth: Int = 0, val highWater: Int = 0, val resets: Long = 0,
    val droppedFrames: Long = 0, val keyframes: Long = 0, val networkErrors: Long = 0,
    val connectionAttempts: Long = 0, val consecutiveFailures: Int = 0,
    val successfulRecoveries: Long = 0, val lastSequence: Long = 0, val error: String = "",
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
    private val retryWaiter = RetryWaiter()
    @Volatile private var activeHeartbeat: ControlHeartbeat? = null
    private val queue = RecoveryQueue<PendingUnit>({ it.payload.size }, { it.flag == M3Wire.CONFIG })
    private val pin = parsePin(pinHex)
    private val tls = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(PinnedTrust(pin)), null)
    }.socketFactory
    private val worker = Thread(::run, "camylla-network-writer")
    @Volatile private var stopping = false
    @Volatile private var videoSocket: SSLSocket? = null
    @Volatile private var controlSocket: SSLSocket? = null
    @Volatile private var connectingSocket: Socket? = null
    @Volatile private var progress = NetworkProgress()
    private var config: ByteArray? = null
    private var generation = 0
    private val syncGate = SyncGate()
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
        if (!queue.isEmpty && now - queue.first.queuedNs > MAX_AGE_NS ||
            queue.size + 2 > MAX_DEPTH || queue.bytes + bytes.size + (if (syncGate.waitingForKey) config?.size ?: 0 else 0) > MAX_QUEUE_BYTES) {
            resetLocked()
        }
        if (syncGate.waitingForKey) {
            if (!syncGate.accept(key, config != null)) {
                progress = progress.copy(droppedFrames = progress.droppedFrames + 1)
                return@synchronized
            }
            queue.add(PendingUnit(config!!.copyOf(), ptsUs, M3Wire.CONFIG, now, generation))
        }
        queue.add(PendingUnit(bytes, ptsUs, if (key) M3Wire.KEY else M3Wire.NORMAL, now, generation))
        progress = progress.copy(queuedFrames = progress.queuedFrames + 1,
            depth = queue.size, highWater = maxOf(progress.highWater, queue.size),
            keyframes = progress.keyframes + if (key) 1 else 0)
        lock.notifyAll()
    }

    private fun resetLocked(closeVideo: Boolean = true, requestKeyFrame: Boolean = true) {
        progress = progress.copy(resets = progress.resets + 1,
            droppedFrames = progress.droppedFrames + queue.clear(), depth = 0)
        syncGate.reset()
        if (closeVideo) runCatching { videoSocket?.close() }
        if (requestKeyFrame) requestSyncFrame()
        lock.notifyAll()
    }

    private fun run() {
        val recovery = RecoveryState()
        while (!stopping) {
            var heartbeatThread: Thread? = null
            var sessionHeartbeat: ControlHeartbeat? = null
            try {
                recovery.attempting()
                progress = progress.copy(state = if (recovery.connectionAttempts == 1L) "Connecting" else "Recovering",
                    connectionAttempts = recovery.connectionAttempts)
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
                val lastPong = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                val heartbeat = ControlHeartbeat(
                    HEARTBEAT_MS, HEARTBEAT_TIMEOUT_MS, SystemClock::elapsedRealtime,
                    { !stopping && !control.isClosed && controlSocket === control },
                    lastPong::get,
                    { M3Wire.writeControl(control.outputStream, "{\"type\":\"ping\"}") },
                )
                sessionHeartbeat = heartbeat
                activeHeartbeat = heartbeat
                Thread({ readControl(control, lastPong, heartbeat) }, "camylla-control-reader").apply { isDaemon = true; start() }
                heartbeatThread = Thread({ heartbeat(control, heartbeat) }, "camylla-control-heartbeat")
                    .apply { isDaemon = true; start() }
                if (control.isClosed) throw java.io.IOException("Control session closed")
                val video = connect(VIDEO_PORT)
                videoSocket = video
                val bind = JSONObject().put("type", "bind").put("version", M3Wire.VERSION)
                    .put("session", welcome.getString("session")).put("secret", secret)
                M3Wire.writeControl(video.outputStream, bind.toString())
                val ready = JSONObject(M3Wire.readControl(video.inputStream))
                if (ready.optString("type") != "ready" || ready.optString("session") != welcome.getString("session"))
                    throw IllegalStateException("Video channel binding rejected")
                if (control.isClosed) throw java.io.IOException("Control session closed")
                synchronized(lock) { resetLocked(closeVideo = false) }
                recovery.streaming()
                progress = progress.copy(state = "Streaming", consecutiveFailures = 0,
                    successfulRecoveries = recovery.successfulRecoveries)
                while (!stopping && !video.isClosed && !control.isClosed) {
                    val unit = synchronized(lock) {
                        while (queue.isEmpty && !stopping && !video.isClosed && !control.isClosed) lock.wait(200)
                        if (queue.isEmpty) null else queue.remove().also {
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
                if (!stopping) throw java.io.IOException("Control or video channel closed")
            } catch (error: Exception) {
                if (!stopping) reportError("Transport: ${error.message}")
            } finally {
                // A channel failure invalidates the entire logical session.
                sessionHeartbeat?.cancel()
                if (activeHeartbeat === sessionHeartbeat) activeHeartbeat = null
                runCatching { videoSocket?.close() }; videoSocket = null
                runCatching { controlSocket?.close() }; controlSocket = null
                heartbeatThread?.join(500)
                synchronized(lock) { resetLocked(closeVideo = false, requestKeyFrame = false) }
            }
            if (!stopping) {
                val delay = recovery.failed()
                progress = progress.copy(state = "Recovering", consecutiveFailures = recovery.consecutiveFailures)
                if (!stopping) retryWaiter.waitFor(delay)
            }
        }
        progress = progress.copy(state = "Stopping")
    }

    private fun heartbeat(control: SSLSocket, schedule: ControlHeartbeat) {
        try {
            schedule.run()
        } catch (error: Exception) {
            if (!stopping && controlSocket === control) reportError("Control heartbeat: ${error.message}")
            runCatching { control.close() }
            if (controlSocket === control) runCatching { videoSocket?.close() }
        }
    }

    private fun readControl(control: SSLSocket, lastPong: java.util.concurrent.atomic.AtomicLong,
                            heartbeat: ControlHeartbeat) {
        try {
            while (!stopping && !control.isClosed) {
                val message = JSONObject(M3Wire.readControl(control.inputStream))
                when (message.optString("type")) {
                    "sync" -> synchronized(lock) { resetLocked(closeVideo = false) }
                    "pong" -> lastPong.set(SystemClock.elapsedRealtime())
                    "error" -> throw IllegalStateException(message.optString("message", "Receiver error"))
                    else -> throw IllegalStateException("Unknown control message")
                }
            }
        } catch (error: Exception) {
            if (!stopping) reportError("Control read: ${error.message}")
            runCatching { control.close() }
            if (controlSocket === control) runCatching { videoSocket?.close() }
        } finally {
            heartbeat.cancel()
        }
    }

    private fun connect(port: Int): SSLSocket {
        val plain = Socket()
        connectingSocket = plain
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val socket = tls.createSocket(plain, host, port, true) as SSLSocket
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = HEARTBEAT_TIMEOUT_MS.toInt()
            return socket
        } catch (error: Exception) { plain.close(); throw error }
        finally { if (connectingSocket === plain) connectingSocket = null }
    }

    private fun reportError(message: String) {
        Log.e("CamyllaProof", message)
        progress = progress.copy(networkErrors = progress.networkErrors + 1, error = message)
    }

    override fun close() {
        stopping = true
        retryWaiter.cancel()
        activeHeartbeat?.cancel()
        synchronized(lock) { queue.clear(); lock.notifyAll() }
        runCatching { connectingSocket?.close() }
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
        private const val HEARTBEAT_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 7_000L
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
