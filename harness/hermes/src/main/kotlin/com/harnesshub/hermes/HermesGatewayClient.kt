package com.harnesshub.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Client for the Hermes dashboard's tui_gateway WebSocket JSON-RPC server
 * (protocol facts verified M4 against `app/tui_gateway/ws.py` + a live probe —
 * see docs/DECISIONS.md). Frames are ND-JSON; the server immediately emits the
 * `gateway.ready` event; agent events ride `method:"event"` envelopes whose
 * `params.type` discriminates.
 */
class HermesGatewayClient(
    private val clientFactory: (listener: WebSocketListener) -> WebSocket,
) {
    sealed interface State {
        data object Connecting : State
        data object Authenticating : State
        data object Ready : State
        data object Closed : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Debug ledger of every raw inbound frame (test diagnostics; cheap CopyOnWriteArrayList). */
    private val rawInbound = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun rawInboundLines(): List<String> = rawInbound.toList()

    private val _events = MutableSharedFlow<GatewayWireFrame>(extraBufferCapacity = 1024)
    val wireEvents: SharedFlow<GatewayWireFrame> = _events.asSharedFlow()

    private var socket: WebSocket? = null
    private val pending = HashMap<Long, CompletableDeferred<JsonObject>>()
    private var rpcId = 0L

    /** [url] must already include /api/ws?token=... (token handled by caller). */
    fun connect(url: String) {
        _state.value = State.Connecting
        socket = clientFactory(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                _state.value = State.Authenticating
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                rawInbound.add(text)
                text.split("\n").forEach { line ->
                    if (line.isNotBlank()) handleLine(line)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                _state.value = State.Closed
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = State.Closed
            }
        })
    }

    private fun handleLine(line: String) {
        val frame = WireFrameCodec.parse(line) ?: return
        if (frame.result != null || frame.error != null) {
            val id = frame.id ?: return
            val deferred = synchronized(pending) { pending.remove(id) } ?: return
            if (frame.error != null) {
                deferred.completeExceptionally(HermesRpcException("RPC error ${frame.error}"))
            } else {
                deferred.complete(frame.result ?: JsonObject(emptyMap()))
            }
            return
        }
        if (frame.method == "event") {
            val params = frame.params ?: return
            val type = SharedWireJson.string(params, "type") ?: return
            if (type == "gateway.ready") {
                _state.value = State.Ready
                return
            }
            _events.tryEmit(GatewayWireFrame(type, params["payload"] as? JsonObject ?: JsonObject(emptyMap())))
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (_state.value) {
                State.Ready -> return true
                State.Closed -> return false
                else -> delay(50)
            }
        }
        return _state.value == State.Ready
    }

    /** One JSON-RPC request resolving with the server's `result` object. */
    suspend fun request(method: String, params: JsonObject? = null, timeoutMs: Long = 25_000): JsonObject {
        val id = ++rpcId
        val deferred = CompletableDeferred<JsonObject>()
        synchronized(pending) { pending[id] = deferred }
        val sent = try {
            socket?.send(SharedWireJson.buildRequest(id, method, params)) ?: false
        } catch (_: Exception) {
            false
        }
        if (!sent) {
            synchronized(pending) { pending.remove(id) }
            throw HermesRpcException("not connected")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    fun close() {
        try {
            socket?.close(1000, "bye")
        } catch (_: Exception) {
            /* gone */
        }
        _state.value = State.Closed
    }
}

class HermesRpcException(messageBody: String) : RuntimeException(messageBody)
