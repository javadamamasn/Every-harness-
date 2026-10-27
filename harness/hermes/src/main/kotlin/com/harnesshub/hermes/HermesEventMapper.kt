package com.harnesshub.hermes

import com.harnesshub.spi.ChatEvent

/**
 * Maps Hermes gateway wire events into the unified [ChatEvent] stream so the
 * shared [com.harnesshub.spi.ChatReducer] can render any harness identically.
 *
 * Verified event shapes (M4, GatewayEvent.kt of the reference client + live
 * probe): message.delta{ text, rendered } / message.interim{ text } /
 * message.complete{ text, reasoning, usage } / thinking.delta{ text } (a status
 * spinner line, NOT reasoning) / reasoning.delta{ text } / tool.start{
 * toolId, name, argsText } / tool.complete{ toolId, result|resultText,
 * durationS, error } / approval.request{ requestId, ... } / session.info /
 * status.update{ kind, text } / error{ message }.
 */
class HermesEventMapper(private val sessionId: () -> String) {

    private var turnCounter = 0

    fun map(frame: GatewayWireFrame): ChatEvent = when (frame.type) {
        "message.start" -> ChatEvent.TurnStarted(nextTurn())

        "message.interim" -> ChatEvent.TextDelta(currentTurn(), text(frame))
        "message.delta" -> ChatEvent.TextDelta(currentTurn(), text(frame))

        // message.complete re-seals the interim bubble; deltas already rendered,
        // so only step the turn forward (spec: keep what was already rendered).
        "message.complete" -> ChatEvent.TurnCompleted(currentTurn(), ChatEvent.TurnCompleted.TurnEndReason.STOP)

        "thinking.delta" -> ChatEvent.ThinkingDelta(currentTurn(), text(frame)) // status line, kept for trace
        "reasoning.delta" -> ChatEvent.ThinkingDelta(currentTurn(), text(frame))
        "reasoning.available" -> ChatEvent.ThinkingDelta(currentTurn(), text(frame))

        "tool.start" -> ChatEvent.ToolCallStarted(toolId(frame), name(frame))
        "tool.generating" -> ChatEvent.ToolCallArgsDelta(toolId(frame), SharedWireJson.stringFor(frame, "preview") ?: (SharedWireJson.stringFor(frame, "argsText") ?: ""))
        "tool.progress" -> ChatEvent.ToolCallArgsDelta(toolId(frame), SharedWireJson.stringFor(frame, "preview") ?: "")
        "tool.complete" -> {
            val id = toolId(frame)
            if (SharedWireJson.stringFor(frame, "error") != null) {
                ChatEvent.ToolResult(id, SharedWireJson.stringFor(frame, "error") ?: "", isError = true)
            } else {
                ChatEvent.ToolResult(
                    id = id,
                    output = SharedWireJson.stringFor(frame, "result") ?: SharedWireJson.stringFor(frame, "resultText") ?: "",
                    isError = false,
                    durationMs = SharedWireJson.double(frame, "durationS")?.times(1000)?.toLong(),
                )
            }
        }

        "approval.requested" -> ChatEvent.ApprovalRequested(
            SharedWireJson.stringFor(frame, "requestId") ?: "approval",
            SharedWireJson.stringFor(frame, "command") ?: "",
            SharedWireJson.stringFor(frame, "description") ?: "",
            if (SharedWireJson.bool(frame, "allowPermanent") == false) 2 else 1,
        )

        "error" -> ChatEvent.ErrorEvent("error", SharedWireJson.stringFor(frame, "message") ?: "unknown error", retryable = true)
        "status.update" -> ChatEvent.ThinkingDelta(currentTurn(), SharedWireJson.stringFor(frame, "text") ?: "")
        "session.info" -> ChatEvent.SessionStarted(SharedWireJson.stringFor(frame, "session_id") ?: sessionId())
        "message.usage" -> ChatEvent.UsageUpdate(SharedWireJson.long(frame, "input") ?: 0, SharedWireJson.long(frame, "output") ?: 0)

        else -> ChatEvent.ModelChanged(frame.type) // preserved as raw block, never dropped
    }

    private fun text(frame: GatewayWireFrame) = SharedWireJson.stringFor(frame, "text")
        ?: SharedWireJson.stringFor(frame, "delta")
        ?: ""

    private fun nextTurn(): String = "t${++turnCounter}"
    private fun currentTurn(): String = "t${if (turnCounter == 0) 1 else turnCounter}"

    private fun toolId(frame: GatewayWireFrame): String =
        SharedWireJson.stringFor(frame, "toolId") ?: SharedWireJson.stringFor(frame, "id") ?: "tool"
    private fun name(frame: GatewayWireFrame): String = SharedWireJson.stringFor(frame, "name") ?: "tool"
}
