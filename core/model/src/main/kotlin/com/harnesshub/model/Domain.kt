package com.harnesshub.model

/**
 * Unified domain model for every AI agent harness supported by Harness Hub.
 *
 * Every harness adapter converts its native events into these types;
 * the UI layer knows no other vocabulary. Keep this module dependency-free.
 */

/** Stable unique identifier of a harness type, e.g. "hermes", "claude-code". */
@JvmInline
value class HarnessId(val value: String)

/** One installed/reachable instance of a harness (same harness may exist twice). */
@JvmInline
value class InstanceId(val value: String)

/** Stable unique identifier of a conversation inside a harness. */
@JvmInline
value class SessionId(val value: String)

/** Identifier of a single message inside a session. */
@JvmInline
value class MessageId(val value: String)

/** Identifier of an in-flight tool call, approval or sub-agent event. */
@JvmInline
value class EventId(val value: String)

/** Where a harness instance physically lives. */
enum class HarnessLocation { TERMUX, PROOT_DISTRO, REMOTE }

/** Lifecycle of a discovered instance (see docs/DECISIONS.md, connection state machine). */
enum class InstanceStatus { INSTALLED, RUNNING, REACHABLE, NEEDS_AUTH, ERROR, NOT_FOUND }

/** A user of the system chooses which model talks. */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val providerId: String,
    val contextWindow: Long? = null,
    val supportsReasoning: Boolean = false,
)

data class Provider(
    val id: String,
    val displayName: String,
    val baseUrl: String? = null, // OpenAI-compatible providers; built-ins use null.
    val models: List<ModelInfo> = emptyList(),
)

/** A file attached to a chat message. */
data class Attachment(
    val name: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long? = null,
)

/** Tool-call status as rendered by the UI. */
enum class ToolStatus { RUNNING, SUCCESS, ERROR, CANCELLED }

data class ToolResult(
    val output: String,
    val isError: Boolean = false,
    val durationMs: Long? = null,
)

/** A session is the durable handle to a conversation stored by the harness. */
data class Session(
    val id: SessionId,
    val harnessId: HarnessId,
    val instanceId: InstanceId,
    var title: String? = null,
    val workingDirectory: String? = null,
    val createdAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null,
    var pinned: Boolean = false,
    var model: ModelInfo? = null,
    val projectId: String? = null,
)

/**
 * Every turn is a list of messages; a message is a list of blocks, which is the
 * only structured way the UI renders harness output (Appendix B of the build spec).
 */
data class Message(
    val id: MessageId,
    val sessionId: SessionId,
    /** "user" | "assistant" | "system" | "tool". Adapter-specific labels are normalized. */
    val role: String,
    val blocks: List<ContentBlock> = emptyList(),
    val createdAtEpochMs: Long? = null,
    /** Present when the message was produced by a sub-agent (nested under its card). */
    val subAgentEventId: EventId? = null,
)

data class Skill(
    val id: String,
    val name: String,
    val description: String? = null,
    /** Path inside the harness's skill directory. */
    val directory: String? = null,
    val enabled: Boolean = true,
)

data class Plugin(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
    val source: String? = null,
)

data class McpServer(
    val id: String,
    val name: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val status: McpStatus = McpStatus.UNKNOWN,
)

enum class McpStatus { CONNECTED, ERROR, DISABLED, UNKNOWN }

data class CronJob(
    val id: String,
    val expression: String,
    var description: String? = null,
    val lastRunEpochMs: Long? = null,
    val nextRunEpochMs: Long? = null,
)

data class ApprovalRequest(
    val id: EventId,
    val tool: String,
    val arguments: String, // full, exact arguments JSON/string as offered
    val risk: ApprovalRisk = ApprovalRisk.MEDIUM,
    val sessionId: SessionId? = null,
)

enum class ApprovalRisk { LOW, MEDIUM, HIGH }

data class UsageStat(
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double? = null,
)

/** A discovered + connectable harness instance, the central Hub entity. */
data class HarnessInstance(
    val id: InstanceId,
    val harnessId: HarnessId,
    val displayName: String,
    val location: HarnessLocation = HarnessLocation.TERMUX,
    val executablePath: String? = null,
    val gatewayUrl: String? = null,
    val version: String? = null,
    val status: InstanceStatus = InstanceStatus.NOT_FOUND,
    /** 0..1, produced by the discovery engine when multiple probes agree. */
    val confidence: Float = 0f,
    val activeModel: ModelInfo? = null,
)
