package com.harnesshub.spi

import com.harnesshub.model.InstanceId
import kotlinx.coroutines.flow.Flow

/** Discovery probe contributed by an adapter; the discovery engine runs these. */
interface DiscoveryProbe {
    enum class Kind { BINARY, FOOTPRINT, PORT, MANUAL }

    val kind: Kind
    val weight: Float // 0..1 contribution to instance confidence

    /** Emits observed signals, e.g. "binary:claude", "dir:~/.claude", "port:8080". */
    suspend fun probe(): Flow<ProbeResult>
}

data class ProbeResult(
    val harnessId: String,
    val signals: List<String>,
    val instanceId: InstanceId,
)

/** Minimal chat surface every adapter claims through [Capability.CHAT]. */
interface ChatApi {
    fun send(
        sessionId: SessionId,
        text: String,
        attachments: List<com.harnesshub.model.Attachment> = emptyList(),
    ): Flow<ChatEvent>

    fun cancel(sessionId: com.harnesshub.model.SessionId)
}

/** Session browse/resume surface. */
interface SessionsApi {
    fun sessions(): Flow<List<com.harnesshub.model.Session>>
    fun open(sessionId: SessionId): Flow<ChatEvent>
    suspend fun delete(sessionId: SessionId)
    suspend fun rename(sessionId: SessionId, title: String)
}

/** Approval inbox surface; only present with [Capability.APPROVALS]. */
interface ApprovalsApi {
    fun requestApproval(request: com.harnesshub.model.ApprovalRequest): Flow<ChatEvent>
    suspend fun resolve(id: EventId, approved: Boolean)
}

/**
 * A live connection to one harness instance; every outbound stream and inbound
 * event channel in one place, so the connection state machine stays per-instance.
 */
interface ConnectionHandle {
    val instanceId: InstanceId
    /** Aggregated event flow; app-level reducer consumes this. */
    val events: Flow<ChatEvent>
    suspend fun close()
}
