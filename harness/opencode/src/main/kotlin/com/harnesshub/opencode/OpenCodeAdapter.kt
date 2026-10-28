package com.harnesshub.opencode

import com.harnesshub.model.HarnessId
import com.harnesshub.model.InstanceId
import com.harnesshub.spi.AdapterDescriptor
import com.harnesshub.spi.ApprovalsApi
import com.harnesshub.spi.Capability
import com.harnesshub.spi.ChatApi
import com.harnesshub.spi.ChatEvent
import com.harnesshub.spi.ConnectionHandle
import com.harnesshub.spi.DiscoveryProbe
import com.harnesshub.spi.HarnessAdapter
import com.harnesshub.spi.HarnessCredentials
import com.harnesshub.spi.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tier-1 DIRECT adapter: the app talks straight to `opencode serve`'s own HTTP
 * API + SSE stream (no relay/bridge in the default path), mirroring how the
 * Hermes adapter talks to its dashboard gateway.
 *
 * Verification status (docs/DECISIONS.md, M6):
 *  - live-verified on device v1.19.3: health, session list/create, prompt
 *    admission, SSE event stream, part shapes (text/reasoning/tool/step-*).
 *  - snapshot-derived (marked Partial until exercised): permission replies,
 *    model switch, diff endpoints.
 */
class OpenCodeAdapter(
    private val client: OpenCodeClient,
    private val scope: CoroutineScope,
) : HarnessAdapter {

    override val descriptor = AdapterDescriptor(
        id = HarnessId("opencode"),
        displayName = "OpenCode",
    )

    override val capabilities = setOf(
        Capability.CHAT,
        Capability.STREAMING,
        Capability.REASONING,
        Capability.TOOL_CALLS,
        Capability.SESSION_RESUME,
        Capability.USAGE_STATS,
    )

    override val chat: ChatApi? = null // turn-bound prompt/abort; wiring at M7
    override val sessions: SessionsApi? = null // list/create next phase
    override val approvals: ApprovalsApi? = null // permission reply at M8

    override val probes: List<DiscoveryProbe> = emptyList()

    private val mapper = OpenCodeEventMapper()
    private var sseJob: Job? = null

    override fun probesFor(instance: com.harnesshub.model.InstanceId): List<DiscoveryProbe> =
        probes

    /** Fan-out handle; mapper output lands here for the app-level reducer. */
    class OpenCodeConnectionHandle(
        override val instanceId: com.harnesshub.model.InstanceId,
    ) : ConnectionHandle {

        override val events = kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256)

        fun emit(event: ChatEvent) {
            events.tryEmit(event)
        }

        override suspend fun close() { /* owner cancels the SSE job via adapter.dispose() */ }
    }

    override fun connect(
        instance: com.harnesshub.model.InstanceId,
        credentials: HarnessCredentials,
    ): ConnectionHandle {
        val handle = OpenCodeConnectionHandle(instance)
        sseJob = scope.launch {
            client.events().collect { line ->
                val events = runCatching { mapper.mapLine(line) }.getOrDefault(emptyList())
                events.forEach { handle.emit(it) }
            }
        }
        return handle
    }

    suspend fun health(): OpenCodeClient.Health = client.health()

    suspend fun createSession(title: String = "Harness Hub"): String = client.createSession(title).id

    fun resumeSession(sessionId: String): String = sessionId

    suspend fun send(sessionId: String, text: String) {
        client.prompt(sessionId, text)
    }

    suspend fun stop(sessionId: String): Boolean = client.abort(sessionId)

    suspend fun listSessions(): List<String> = client.listSessions().map { it.id }

    fun dispose() {
        sseJob?.cancel()
    }
}
