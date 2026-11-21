package com.androidharness.app.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class RelayConnection { CLOSED, CONNECTING, OPEN, WAITING, ERROR }

/**
 * WebSocket to the Codespace relay. The relay numbers every event, so a drop
 * (screen lock, network change, Codespace restart) never loses work: the socket
 * reopens with the last rendered sequence and the relay replays the rest while
 * the task keeps running in the cloud.
 */
class CloudRelayClient(
    private val origin: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    private val _frames = MutableSharedFlow<CloudFrame>(extraBufferCapacity = 512)
    val frames: SharedFlow<CloudFrame> = _frames.asSharedFlow()

    private val _status = MutableStateFlow(RelayConnection.CLOSED)
    val status: StateFlow<RelayConnection> = _status.asStateFlow()

    private val _applicationError = MutableStateFlow<String?>(null)
    val applicationError: StateFlow<String?> = _applicationError.asStateFlow()

    private val replay = RelayReplay(0)
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var stopped = false

    val lastSeq: Long get() = replay.lastSeq

    fun start() {
        if (loop?.isActive == true) return
        stopped = false
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        stopped = true
        loop?.cancel()
        loop = null
        socket?.close(1000, "closed")
        socket = null
        _status.value = RelayConnection.CLOSED
    }

    fun send(text: String): Boolean {
        val live = socket ?: return false
        return live.send(text)
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (!stopped) {
            _status.value = RelayConnection.CONNECTING
            val opened = open()
            if (opened) backoff = 1_000L
            if (stopped) break
            _status.value = RelayConnection.WAITING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun open(): Boolean {
        val url = origin.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/relay"
        val request = Request.Builder().url(url).build()
        val connected = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                webSocket.send(RelayProtocol.hello(token, replay.lastSeq))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                RelayProtocol.readySeq(text)?.let {
                    if (_status.value != RelayConnection.OPEN) _status.value = RelayConnection.OPEN
                    connected.complete(true)
                    return
                }
                RelayProtocol.decode(text)?.let { frame ->
                    if (replay.accept(frame)) _frames.tryEmit(frame)
                } ?: run {
                    RelayProtocol.errorMessage(text)?.let { _applicationError.value = it }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                _status.value = RelayConnection.ERROR
                _applicationError.value = t.message ?: "Lost the connection to the cloud relay."
                connected.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (_status.value == RelayConnection.OPEN) _status.value = RelayConnection.WAITING
                connected.complete(false)
            }
        }
        client.newWebSocket(request, listener)
        // The relay answers hello with a ready frame; a timeout means the port is not open yet.
        return withTimeoutOrFalse(20_000) { connected.await() }
    }

    private suspend fun withTimeoutOrFalse(timeoutMs: Long, block: suspend () -> Boolean): Boolean =
        try {
            kotlinx.coroutines.withTimeout(timeoutMs) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /** Probe `/health` (HTTP) before opening the socket; the relay answers without any secret. */
    suspend fun healthy(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${origin.trimEnd('/')}/health").build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
