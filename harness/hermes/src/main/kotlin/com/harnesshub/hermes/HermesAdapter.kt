package com.harnesshub.hermes

import com.harnesshub.model.HarnessId
import com.harnesshub.model.InstanceId
import com.harnesshub.model.SessionId
import com.harnesshub.spi.Capability
import com.harnesshub.spi.HarnessCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Hermes Agent adapter. Talks to the dashboard's tui_gateway WebSocket
 * JSON-RPC server (all protocol facts verified M4 — docs/DECISIONS.md); on
 * Termux-first devices this direct-loopback transport is the intended one.
 */
class HermesAdapter(
    val gatewayPort: Int = 9119,
) : HarnessAdapter {

    override val descriptor = com.harnesshub.spi.AdapterDescriptor(
        id = HarnessId("hermes"),
        displayName = "Hermes Agent",
    )

    override val capabilities = setOf(
        Capability.CHAT,
        Capability.STREAMING,
        Capability.REASONING,
        Capability.REASONING_EFFORT,
        Capability.TOOL_CALLS,
        Capability.SUB_AGENTS,
        Capability.SESSIONS,
        Capability.SESSION_RESUME,
        Capability.SLASH_COMMANDS,
        Capability.STEERING,
        Capability.MODEL_SWITCH,
        Capability.APPROVALS,
        Capability.USAGE_STATS,
        Capability.LOGS,
        Capability.PROJECTS,
    )

    override val probes: List<com.harnesshub.spi.DiscoveryProbe> = emptyList()
    override val chat: com.harnesshub.spi.ChatApi? get() = null // session-bound; see connect()
    override val sessions: com.harnesshub.spi.SessionsApi? get() = null
    override val approvals: com.harnesshub.spi.ApprovalsApi? get() = null

    override suspend fun connect(instance: InstanceId, credentials: HarnessCredentials): ConnectionHandle {
        val token = when (credentials) {
            is HarnessCredentials.BearerToken -> credentials.token
            else -> error("Hermes gateway requires a dashboard session token")
        }
        val client = HermesGatewayClient(clientFactory = { listener -> socketFor(listener) })
        client.connect("ws://127.0.0.1:$gatewayPort/api/ws?token=${java.net.URLEncoder.encode(token, "UTF-8")}")
        if (!client.awaitReady()) throw HermesRpcException("gateway did not become ready")
        return HermesConnection(client)
    }

    /** extractable for tests; used by the fake-wire contract suite. */
    internal fun socketFor(listener: WebSocketListener): WebSocket =
        OkHttpClient().newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$gatewayPort/api/ws").build(),
            listener,
        )
}

/** Live Hermes session bound by gateway events. */
class HermesConnection(
    private val client: HermesGatewayClient,
) : ConnectionHandle, com.harnesshub.spi.ChatApi, com.harnesshub.spi.SessionsApi {

    private var sessionId: String? = null

    override val instanceId: InstanceId = InstanceId("hermes")

    private val mapper = HermesEventMapper({ sessionId })

    /** Unified display stream: mapped gateway events + connection lifecycle. */
    override val events: Flow<com.harnesshub.spi.ChatEvent> = merge(
        client.wireEvents.map { mapper.map(it) },
        client.state.map { state ->
            when (state) {
                HermesGatewayClient.State.Ready -> com.harnesshub.spi.ChatEvent.ConnectionResumed
                else -> com.harnesshub.spi.ChatEvent.ConnectionLost
            }
        },
    )

    /** session.create (verified params: cwd?, source="chat"). */
    suspend fun createSession(cwd: String? = null): String {
        val params = buildJsonObject {
            cwd?.let { put("cwd", it) }
            put("source", "chat")
        }
        val created = client.request("session.create", params)
        val id = (created["session_id"] as? JsonPrimitive)?.content
            ?: throw HermesRpcException("session.create returned no session_id")
        sessionId = id
        return id
    }

    override fun send(
        session: SessionId,
        text: String,
        attachments: List<com.harnesshub.model.Attachment>,
    ): Flow<com.harnesshub.spi.ChatEvent> = flow {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val live = sessionId ?: createSession()
                client.request("prompt.submit", buildJsonObject {
                    put("text", text)
                    put("session_id", live)
                })
            } catch (e: Exception) {
                emit(
                    com.harnesshub.spi.ChatEvent.ErrorEvent(
                        "prompt.submit",
                        e.message ?: "prompt submit failed",
                        retryable = true,
                    ),
                )
            }
        }
        emitAll(events)
    }

    override fun cancel(session: SessionId) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                client.request("session.interrupt", buildJsonObject { put("session_id", session.value) })
            }
        }
    }

    override suspend fun close() {
        client.close()
    }

    override fun sessions(): Flow<List<com.harnesshub.model.Session>> = flow {
        val result = runCatching { client.request("session.most_recent") }.getOrNull()
        val id = (result?.get("session_id") as? JsonPrimitive)?.content
        if (id != null) {
            emit(
                listOf(
                    com.harnesshub.model.Session(
                        id = SessionId(id),
                        harnessId = HarnessId("hermes"),
                        instanceId = instanceId,
                        title = (result["title"] as? JsonPrimitive)?.content,
                    ),
                ),
            )
        } else {
            emit(emptyList())
        }
    }

    override fun open(session: SessionId): Flow<com.harnesshub.spi.ChatEvent> = flow {
        sessionId = session.value
        client.request("session.resume", buildJsonObject { put("session_id", session.value) })
        emitAll(events)
    }

    override suspend fun delete(session: SessionId) {
        client.request("session.delete", buildJsonObject { put("session_id", session.value) })
    }

    override suspend fun rename(session: SessionId, title: String) {
        client.request("session.title", buildJsonObject {
            put("session_id", session.value)
            put("title", title)
        })
    }
}
