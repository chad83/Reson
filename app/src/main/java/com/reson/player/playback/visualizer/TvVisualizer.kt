package com.reson.player.playback.visualizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import com.reson.player.data.LocalNasNetwork
import com.reson.player.domain.NasException
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TelevisionState(
    val url: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
)

internal class TvVisualizer(
    private val context: Context,
    private val lan: LocalNasNetwork,
    private val settings: VisualizerSettings,
) {
    private val state = MutableStateFlow(TelevisionState())
    val status = state.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val connection = Any()
    private val random = SecureRandom()
    private var server: TelevisionServer? = null

    @Volatile var stream: TelevisionStream? = null
        private set

    suspend fun start() = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (server != null || !settings.enabled.value) return@withLock
            try {
                val endpoint = lan.localIpv4() ?: error("Connect the phone to Wi-Fi with an IPv4 address.")
                val code = buildString(CODE_LENGTH) {
                    repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
                }
                val listener = TelevisionServer(endpoint, code)
                synchronized(connection) { server = listener }
                try {
                    listener.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    listener.watchNetwork()
                    check(lan.isAvailable(endpoint))
                    synchronized(connection) {
                        state.value = state.value.copy(
                            url = "http://${endpoint.address.hostAddress}:${listener.listeningPort}/$code",
                        )
                    }
                } catch (error: Exception) {
                    closeServer()
                    throw error
                }
            } catch (error: NasException) {
                state.value = TelevisionState(error = error.problem.description)
            } catch (_: Exception) {
                state.value = TelevisionState(error = "Couldn’t start TV mode. Check that the phone is connected to Wi-Fi.")
            }
        }
    }

    fun endSession() {
        io.launch { stop() }
    }

    suspend fun stop() = withContext(Dispatchers.IO + NonCancellable) {
        lifecycle.withLock { closeServer() }
    }

    private fun closeServer() {
        val listener = synchronized(connection) {
            stream?.close()
            stream = null
            state.value = TelevisionState()
            server.also { server = null }
        }
        listener?.close()
    }

    private inner class TelevisionServer(private val endpoint: LocalNasNetwork.LocalIpv4, private val code: String) :
        NanoHTTPD(endpoint.address.hostAddress, 0) {
        private val root = "/$code/"
        private val expiresAt = SystemClock.elapsedRealtime() + CONNECTION_WINDOW_MS
        private var expiry: Job? = null
        private var watching = false
        private var claimed = false
        private var incorrectCodes = 0
        private val network = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == endpoint.network) invalidate("TV mode ended because the network changed.")
            }

            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) {
                if (network == endpoint.network && links.linkAddresses.none { it.address == endpoint.address }) {
                    invalidate("TV mode ended because the network address changed.")
                }
            }
        }

        fun watchNetwork() {
            lan.watchLocalNetwork(network)
            watching = true
            expiry = io.launch {
                delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                lifecycle.withLock {
                    if (synchronized(connection) { server === this@TelevisionServer && !claimed }) {
                        closeServer()
                        state.value = TelevisionState(error = "The TV link expired. Start TV mode again.")
                    }
                }
            }
        }

        private fun invalidate(message: String) {
            io.launch {
                lifecycle.withLock {
                    if (server === this@TelevisionServer) {
                        closeServer()
                        state.value = TelevisionState(error = message)
                    }
                }
            }
        }

        fun close() {
            expiry?.cancel()
            try {
                if (watching) lan.unwatchLocalNetwork(network)
            } finally {
                stop()
            }
        }

        init {
            setAsyncRunner(BoundedConnections())
        }

        override fun useGzipWhenAccepted(response: Response): Boolean = false

        override fun serve(session: IHTTPSession): Response {
            val peer = runCatching { InetAddress.getByName(session.remoteIpAddress) }.getOrNull()
            if (session.method != Method.GET || peer == null || !lan.accepts(endpoint, peer) ||
                session.headers["host"] != "${endpoint.address.hostAddress}:$listeningPort" ||
                !settings.enabled.value
            ) return reply(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

            val requestedCode = session.uri.removePrefix("/").substringBefore('/')
            synchronized(connection) {
                if (!canUseLink()) {
                    return reply(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "This TV link has expired.")
                }
                if (requestedCode != code) {
                    if (requestedCode.length == CODE_LENGTH && requestedCode.all { it in CODE_ALPHABET }) {
                        incorrectCodes++
                        if (incorrectCodes == MAX_INCORRECT_CODES) {
                            invalidate("Too many incorrect TV codes. Start TV mode again.")
                        }
                    }
                    return reply(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }

            return when (session.uri) {
                "/$code" -> reply(Response.Status.TEMPORARY_REDIRECT, MIME_PLAINTEXT, "").apply {
                    addHeader("Location", root)
                }
                root -> asset("index.html", "text/html; charset=utf-8")
                root + "player.js" -> asset("player.js", "text/javascript")
                root + "audio" -> synchronized(connection) {
                    if (!canUseLink()) {
                        reply(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "This TV link has expired.")
                    } else {
                        claimed = true
                        expiry?.cancel()
                        val audio = TelevisionStream { finished ->
                            io.launch {
                                lifecycle.withLock {
                                    if (synchronized(connection) { stream === finished }) closeServer()
                                }
                            }
                        }
                        stream = audio
                        state.value = state.value.copy(connected = true)
                        newChunkedResponse(Response.Status.OK, "application/octet-stream", audio).withHeaders()
                    }
                }
                else -> reply(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }

        // Called with the connection lock held so guesses and stream claims share the same limit.
        private fun canUseLink(): Boolean =
            server === this && !claimed && incorrectCodes < MAX_INCORRECT_CODES &&
                SystemClock.elapsedRealtime() < expiresAt

        private fun asset(name: String, mime: String): Response =
            reply(Response.Status.OK, mime, context.assets.open("visualizer/$name").bufferedReader().use { it.readText() })

        private fun reply(status: Response.Status, mime: String, text: String): Response =
            newFixedLengthResponse(status, mime, text).withHeaders()

        private fun Response.withHeaders(): Response = apply {
            closeConnection(true)
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
        }
    }

    private companion object {
        const val CODE_LENGTH = 4
        const val CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
        const val MAX_INCORRECT_CODES = 20
        const val CONNECTION_WINDOW_MS = 5 * 60 * 1000L
    }
}

internal class BoundedConnections : NanoHTTPD.AsyncRunner {
    private val clients = mutableSetOf<NanoHTTPD.ClientHandler>()
    private var closed = false

    @Synchronized override fun exec(handler: NanoHTTPD.ClientHandler) {
        if (closed || clients.size >= 6) {
            handler.close()
            return
        }
        clients.add(handler)
        Thread(handler, "Reson TV browser").apply { isDaemon = true }.start()
    }

    @Synchronized override fun closed(handler: NanoHTTPD.ClientHandler) {
        clients.remove(handler)
    }

    @Synchronized override fun closeAll() {
        closed = true
        for (client in clients.toList()) client.close()
    }
}

internal class TelevisionStream(private val disconnected: (TelevisionStream) -> Unit) : InputStream() {
    private val packets = ArrayBlockingQueue<ByteArray>(16)
    private val closed = AtomicBoolean()
    private var current = ByteArray(0)
    private var offset = 0

    fun offer(packet: ByteArray) {
        if (!closed.get() && !packets.offer(packet)) {
            packets.poll()
            packets.offer(packet)
        }
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed.get()) return -1
        if (offset == current.size) {
            // An empty packet keeps the connection responsive while playback is paused.
            current = packets.poll(1, TimeUnit.SECONDS) ?: ByteArray(8)
            offset = 0
            if (closed.get()) return -1
        }
        val count = minOf(length, current.size - offset)
        current.copyInto(buffer, start, offset, offset + count)
        offset += count
        return count
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        packets.clear()
        disconnected(this)
    }
}
