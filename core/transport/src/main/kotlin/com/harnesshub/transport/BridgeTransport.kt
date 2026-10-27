package com.harnesshub.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Typed client over the bridge's multiplexed WebSocket (protocol 1):
 * authenticates with a hello frame, correlates req/res by id, surfaces events,
 * and reports the connection StateFlow per build-spec Appendix D. One session
 * per [connect]; the wrapper service adds auto-reconnect (M6).
 */
class BridgeTransport(
    private val clientFactory: (listener: okhttp3.WebSocketListener) -> okhttp3.WebSocket,
    private val json: Json = BridgeJson.default,
    private val requestTimeoutMs: Long = 20_000,
) {
    sealed interface State {
        data object Connecting : State
        data object Authenticating : State
        data class Ready(val protocol: Int, val bridgeVersion: String?, val home: String?) : State
        data class Failed(val error: BridgeError?) : State
        data object Closed : State
    }

    private var socket: okhttp3.WebSocket? = null
    private val pending = HashMap<String, CompletableDeferred<Envelope>>()
    private val requestId = java.util.concurrent.atomic.AtomicLong(0)
    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    val events: kotlinx.coroutines.flow.SharedFlow<Envelope> = _events

    fun connect(token: String) {
        _state.value = State.Connecting
        val listener = object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                _state.value = State.Authenticating
                webSocket.send(
                    json.encodeToString(
                        Envelope.serializer(),
                        Envelope(id = "hello", type = "hello", params = BridgeJson.obj("protocol" to 1, "token" to token)),
                    ),
                )
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val envelope = try {
                    json.decodeFromString(Envelope.serializer(), text)
                } catch (_: Exception) {
                    return
                }
                when {
                    envelope.id == "hello" -> {
                        _state.value = if (envelope.result != null) {
                            State.Ready(
                                protocol = BridgeJson.int(envelope.result, "protocol") ?: 1,
                                bridgeVersion = BridgeJson.string(envelope.result, "bridgeVersion"),
                                home = BridgeJson.string(envelope.result, "home"),
                            )
                        } else {
                            State.Failed(envelope.error)
                        }
                        synchronized(pending) { pending.values.toList() }.forEach { it.complete(envelope) }
                        synchronized(pending) { pending.clear() }
                    }

                    envelope.type == "evt" -> _events.tryEmit(envelope)

                    envelope.id != null -> {
                        val deferred = synchronized(pending) { pending.remove(envelope.id) }
                        deferred?.complete(envelope)
                    }
                }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                fail(BridgeError("INTERNAL", "transport failure: ${t.javaClass.simpleName}"))
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                fail(BridgeError("INTERNAL", "socket closed"))
                _state.value = State.Closed
            }
        }
        socket = clientFactory(listener)
    }

    private fun fail(error: BridgeError) {
        synchronized(pending) {
            pending.values.toList().forEach { it.complete(Envelope(type = "err", error = error)) }
            pending.clear()
        }
        if (_state.value !is State.Ready && _state.value !is State.Closed) {
            _state.value = State.Failed(error)
        }
    }

    /** Resolves as soon as the session is Ready; short-circuits failures. */
    suspend fun awaitReady(timeoutMs: Long = 10_000): State {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val s = _state.value) {
                is State.Ready -> return s
                is State.Failed, is State.Closed -> return s
                else -> delay(50)
            }
        }
        return _state.value
    }

    /** Sends one request and resolves the correlated envelope (res or err). */
    suspend fun requestEnvelope(method: String, params: JsonElement? = null): Envelope {
        val id = "hc-${requestId.incrementAndGet()}"
        val deferred = CompletableDeferred<Envelope>()
        synchronized(pending) { pending[id] = deferred }
        val sent = try {
            socket?.send(json.encodeToString(Envelope.serializer(), Envelope(id = id, type = "req", method = method, params = params)))
                ?: false
        } catch (_: Exception) {
            false
        }
        if (!sent) {
            synchronized(pending) { pending.remove(id) }
            throw BridgeException(BridgeError("INTERNAL", "not connected"))
        }
        return try {
            withTimeout(requestTimeoutMs) { deferred.await() }
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    /** Result-oriented request; errors surface as [BridgeException]. */
    suspend fun request(method: String, params: JsonElement? = null): JsonElement {
        val env = requestEnvelope(method, params)
        if (env.error != null) throw BridgeException(env.error)
        return env.result ?: throw BridgeException(BridgeError("INTERNAL", "missing result"))
    }

    fun close() {
        try {
            socket?.close(1000, "bye")
        } catch (_: Exception) {
            /* already gone */
        }
        fail(BridgeError("INTERNAL", "client closed"))
        _state.value = State.Closed
    }
}

class BridgeException(val error: BridgeError) : RuntimeException("${error.code}: ${error.message}")
