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

internal fun NetworkProgress.streamed(recoveries: Long) = copy(
    state = "Streaming", consecutiveFailures = 0,
    successfulRecoveries = recoveries, error = "",
)

/** One explicit phone-started session. Socket work is confined to worker/control threads. */
class NetworkSender(
    private val identity: ProductIdentity, private val peer: TrustedDesktop,
    private val mode: String, private val lens: String,
    private val requestSyncFrame: () -> Unit,
) : AutoCloseable {
    private data class PendingUnit(val payload: ByteArray, val ptsUs: Long, val flag: Int,
                                   val queuedNs: Long, val generation: Int)
    private val lock = Object()
    private val controlWrite = Any()
    private val retryWaiter = RetryWaiter()
    @Volatile private var activeHeartbeat: ControlHeartbeat? = null
    private val queue = RecoveryQueue<PendingUnit>({ it.payload.size }, { it.flag == M4Wire.CONFIG })
    private val host = peer.host
    private val worker = Thread(::run, "camylla-network-writer")
    @Volatile private var stopping = false
    @Volatile private var videoSocket: SSLSocket? = null
    @Volatile private var controlSocket: SSLSocket? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var connectingSocket: Socket? = null
    @Volatile private var progress = NetworkProgress()
    private var config: ByteArray? = null
    private var generation = 0
    private val syncGate = SyncGate()
    private val transportGate = VideoTransportGate()
    private var sequence = 0L

    init {
        require(host.isNotBlank() && !peer.revoked && identity.desktops().any { it.id == peer.id && !it.revoked })
        require(mode in listOf("1280x720@30", "1920x1080@30") && lens in listOf("rear", "front"))
        worker.start()
    }

    fun stats(): NetworkProgress = progress

    fun newEncoder() = synchronized(lock) {
        generation++
        config = null
        if (transportGate.isReady) resetLocked() else syncGate.reset()
    }

    fun codecConfig(bytes: ByteArray) = synchronized(lock) {
        if (bytes.isNotEmpty() && bytes.size <= M4Wire.MAX_VIDEO_BYTES) {
            val changed = config != null && !config!!.contentEquals(bytes)
            config = bytes.copyOf()
            if (changed) {
                generation++
                if (transportGate.isReady) resetLocked() else syncGate.reset()
            }
        }
    }

    fun offer(bytes: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (stopping) return@synchronized
        // Keep draining MediaCodec during an outage without building a doomed video queue.
        if (!transportGate.isReady) {
            progress = progress.copy(droppedFrames = progress.droppedFrames + 1)
            return@synchronized
        }
        if (bytes.isEmpty() || bytes.size > M4Wire.MAX_VIDEO_BYTES) { resetLocked(); return@synchronized }
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
            transportGate.enqueue(queue, PendingUnit(config!!.copyOf(), ptsUs, M4Wire.CONFIG, now, generation))
        }
        transportGate.enqueue(queue, PendingUnit(bytes, ptsUs, if (key) M4Wire.KEY else M4Wire.NORMAL, now, generation))
        progress = progress.copy(queuedFrames = progress.queuedFrames + 1,
            depth = queue.size, highWater = maxOf(progress.highWater, queue.size),
            keyframes = progress.keyframes + if (key) 1 else 0)
        lock.notifyAll()
    }

    private fun resetLocked(closeVideo: Boolean = true, requestKeyFrame: Boolean = true) {
        progress = progress.copy(resets = progress.resets + 1,
            droppedFrames = progress.droppedFrames + queue.clear(), depth = 0)
        syncGate.reset()
        if (closeVideo) {
            transportGate.unavailable()
            runCatching { videoSocket?.close() }
        }
        // A closed channel cannot use a sync frame. The next ready bind requests one.
        if (requestKeyFrame && transportGate.isReady) requestSyncFrame()
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
                val hello = JSONObject().put("type", "start").put("version", M4Wire.VERSION)
                    .put("codec", "h264").put("mode", mode).put("lens", lens)
                    .put("modes", JSONArray(listOf("1280x720@30", "1920x1080@30")))
                    .put("phone_id", identity.phoneId).put("client_nonce", ProductIdentity.randomHex(16))
                writeControl(control, hello.toString())
                val welcome = JSONObject(M4Wire.readControl(control.inputStream))
                if (welcome.optString("type") != "started" || welcome.optInt("version") != M4Wire.VERSION ||
                    welcome.optString("mode") != mode || welcome.optString("codec") != "h264" ||
                    welcome.optInt("fps") != 30)
                    throw IllegalStateException("Capability negotiation rejected")
                M4Wire.record(welcome, "started", "session_id", "client_nonce", "server_nonce", "video_ticket", "codec", "mode", "fps")
                require(welcome.getString("client_nonce") == hello.getString("client_nonce"))
                PairInvitation.hex(welcome.getString("server_nonce"), 16); PairInvitation.hex(welcome.getString("video_ticket"), 32)
                sessionId = welcome.getString("session_id")
                val id = M4Wire.hex16(sessionId!!)
                progress = progress.copy(state = "Negotiating")
                val lastPong = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                val heartbeat = ControlHeartbeat(
                    HEARTBEAT_MS, HEARTBEAT_TIMEOUT_MS, SystemClock::elapsedRealtime,
                    { !stopping && !control.isClosed && controlSocket === control },
                    lastPong::get,
                    { writeControl(control, "{\"type\":\"ping\",\"version\":3}") },
                )
                sessionHeartbeat = heartbeat
                activeHeartbeat = heartbeat
                Thread({ readControl(control, lastPong, heartbeat) }, "camylla-control-reader").apply { isDaemon = true; start() }
                heartbeatThread = Thread({ heartbeat(control, heartbeat) }, "camylla-control-heartbeat")
                    .apply { isDaemon = true; start() }
                if (control.isClosed) throw java.io.IOException("Control session closed")
                val video = connect(VIDEO_PORT)
                videoSocket = video
                val bind = JSONObject().put("type", "bind").put("version", M4Wire.VERSION)
                    .put("session_id", welcome.getString("session_id")).put("phone_id", identity.phoneId)
                    .put("client_nonce", welcome.getString("client_nonce")).put("server_nonce", welcome.getString("server_nonce"))
                    .put("video_ticket", welcome.getString("video_ticket"))
                M4Wire.writeControl(video.outputStream, bind.toString())
                val ready = JSONObject(M4Wire.readControl(video.inputStream))
                M4Wire.record(ready, "ready", "session_id")
                if (ready.optString("type") != "ready" || ready.optString("session_id") != welcome.getString("session_id"))
                    throw IllegalStateException("Video channel binding rejected")
                if (control.isClosed) throw java.io.IOException("Control session closed")
                synchronized(lock) {
                    resetLocked(closeVideo = false, requestKeyFrame = false)
                    transportGate.ready(requestSyncFrame)
                }
                recovery.streaming()
                progress = progress.streamed(recovery.successfulRecoveries)
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
                    val packet = M4Wire.video(next, unit.generation, unit.ptsUs, sendUs, id, unit.flag, unit.payload)
                    video.outputStream.write(packet)
                    progress = progress.copy(sentFrames = progress.sentFrames + if (unit.flag == M4Wire.CONFIG) 0 else 1,
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
                synchronized(lock) {
                    if (transportGate.isReady) {
                        transportGate.unavailable()
                        resetLocked(closeVideo = false, requestKeyFrame = false)
                    }
                }
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
                val message = JSONObject(M4Wire.readControl(control.inputStream))
                when (message.optString("type")) {
                    "sync" -> { M4Wire.record(message, "sync"); synchronized(lock) { resetLocked(closeVideo = false) } }
                    "pong" -> { M4Wire.record(message, "pong"); lastPong.set(SystemClock.elapsedRealtime()) }
                    "error" -> throw IllegalStateException(message.optString("code", "Receiver rejected session"))
                    else -> throw IllegalStateException("Unknown control message")
                }
            }
        } catch (error: Exception) {
            if (!stopping && controlSocket === control) reportError("Control read: ${error.message}")
            runCatching { control.close() }
            if (controlSocket === control) runCatching { videoSocket?.close() }
        } finally {
            heartbeat.cancel()
        }
    }

    private fun connect(port: Int): SSLSocket {
        // Trust is rechecked on every recovery; a local revocation stops new connections.
        require(identity.desktops().any { it.id == peer.id && !it.revoked }) { "Desktop trust revoked" }
        val socket = identity.socket(host, port, peer.id)
        if (stopping) { socket.close(); throw java.io.IOException("Capture stopped") }
        socket.soTimeout = HEARTBEAT_TIMEOUT_MS.toInt()
        return socket
    }
    private fun writeControl(control: SSLSocket, json: String) = synchronized(controlWrite) {
        M4Wire.writeControl(control.outputStream, json)
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
        val control = controlSocket
        val session = sessionId
        if (control != null && session != null) {
            val stopNotice = Thread({
                runCatching { writeControl(control, JSONObject().put("type", "stop").put("version", 3).put("session_id", session).toString()) }
                runCatching { control.close() }
            }, "camylla-stop-notice").apply { isDaemon = true; start() }
            stopNotice.join(200)
        }
        runCatching { controlSocket?.close() }
        worker.join(2000)
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
    }
}
