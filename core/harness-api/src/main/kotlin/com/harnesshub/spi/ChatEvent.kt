package com.harnesshub.spi

/**
 * The unified chat event stream (Appendix B). Every adapter emits only these;
 * harness-specific payloads ride as nested data and are reduced into one UI state.
 */
sealed interface ChatEvent {
    /** ── Lifecycle ─────────────────────────────────────────────────────── */
    data class SessionStarted(val sessionId: String) : ChatEvent
    data class TurnStarted(val turnId: String) : ChatEvent

    enum class TurnEndReason { STOP, CANCELLED, MAX_TOKENS, ERROR, NEEDS_APPROVAL }
    data class TurnCompleted(val turnId: String, val reason: TurnEndReason) : ChatEvent

    /** ── Content ───────────────────────────────────────────────────────── */
    data class TextDelta(val turnId: String, val text: String) : ChatEvent
    data class ThinkingDelta(val turnId: String, val text: String) : ChatEvent
    data class ThinkingCompleted(val turnId: String) : ChatEvent
    data class ImageAdded(val turnId: String, val uri: String, val alt: String? = null) : ChatEvent
    data class FileAdded(val turnId: String, val name: String, val uri: String) : ChatEvent
    data class ArtifactAvailable(val turnId: String, val name: String, val uri: String, val mimeType: String) : ChatEvent

    /** ── Tools ─────────────────────────────────────────────────────────── */
    data class ToolCallStarted(val id: String, val name: String) : ChatEvent
    /** [argsJsonChunk] carries partial JSON; tolerate malformed fragments until completion. */
    data class ToolCallArgsDelta(val id: String, val argsJsonChunk: String) : ChatEvent
    data class ToolCallCompleted(val id: String) : ChatEvent
    data class ToolResult(
        val id: String,
        val output: String,
        val isError: Boolean = false,
        val durationMs: Long? = null,
    ) : ChatEvent

    /** ── Sub-agents ────────────────────────────────────────────────────── */
    data class SubAgentStarted(val id: String, val task: String) : ChatEvent
    data class SubAgentEvent(val id: String, val inner: ChatEvent) : ChatEvent
    data class SubAgentCompleted(val id: String, val summary: String?) : ChatEvent

    /** ── Approvals ─────────────────────────────────────────────────────── */
    data class ApprovalRequested(val id: String, val tool: String, val arguments: String, val risk: Int) : ChatEvent
    data class ApprovalResolved(val id: String, val approved: Boolean) : ChatEvent

    /** ── Edits ─────────────────────────────────────────────────────────── */
    data class DiffProposed(val turnId: String, val path: String, val hunks: String) : ChatEvent
    data class DiffResolved(val turnId: String, val path: String, val accepted: Boolean) : ChatEvent

    /** ── Meta ──────────────────────────────────────────────────────────── */
    data class UsageUpdate(val inputTokens: Long, val outputTokens: Long, val costUsd: Double? = null) : ChatEvent
    data class ModelChanged(val modelId: String) : ChatEvent
    data class ErrorEvent(val kind: String, val message: String, val retryable: Boolean) : ChatEvent
    data object ConnectionLost : ChatEvent
    data object ConnectionResumed : ChatEvent

    /** Unknown harness event preserved verbatim for the collapsible raw block (Appendix B). */
    data class Other(val source: String, val payload: String) : ChatEvent
}
