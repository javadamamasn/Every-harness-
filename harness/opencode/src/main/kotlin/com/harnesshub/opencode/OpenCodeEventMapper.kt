package com.harnesshub.opencode

import com.harnesshub.spi.ChatEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps OpenCode SSE events (`/event`, one JSON object per `data:` line) to the
 * unified [ChatEvent] hierarchy, per Appendix B.
 *
 * Shapes verified against the OpenAPI snapshot
 * `harness/opencode/fixtures/openapi-1.19.3.json` + live probes of
 * `opencode serve` v1.19.3 (2026-10-28):
 *   envelope {id:"evt_..", type, properties:{...}}
 *   message.part.updated {sessionID, part (full value!), time} → diffed into deltas
 *   step-finish carries tokens{input,output,reasoning} + cost
 *   session.next.step.failed / session.error → ErrorEvent("provider")
 * Unknown event types survive as [ChatEvent.Other] (collapsible raw block).
 */
class OpenCodeEventMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** partID → last cumulative length: full part values become deltas. */
    private val seenParts = HashMap<String, Int>()
    private val openTools = HashSet<String>()

    fun mapLine(line: String): List<ChatEvent> {
        val json = this.json.parseToJsonElement(line) as? JsonObject
            ?: return listOf(other("malformed", line))
        val type = string(json, "type") ?: return listOf(other("malformed", line))
        val props = json["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return when (type) {
            "server.connected" -> listOf(ChatEvent.ConnectionResumed)
            "session.error", "session.next.step.failed" -> {
                val message = (props["error"] as? JsonObject)?.let { string(it, "message") }
                    ?: "unknown error (type $type)"
                listOf(ChatEvent.ErrorEvent("provider", message, retryable = false))
            }
            "message.updated" -> usageFromMessage(props, line)
            "message.part.updated" -> mapPart(props, line)
            else -> listOf(other(type, line))
        }
    }

    private fun usageFromMessage(props: JsonObject, line: String): List<ChatEvent> {
        val info = props["info"] as? JsonObject ?: return emptyList()
        val tokens = info["tokens"] as? JsonObject ?: return emptyList()
        return listOf(
            ChatEvent.UsageUpdate(
                inputTokens = tokens.number("input"),
                outputTokens = tokens.number("output"),
            ),
        )
    }

    private fun mapPart(props: JsonObject, line: String): List<ChatEvent> {
        val part = props["part"] as? JsonObject ?: return listOf(other("message.part.updated", line))
        val partId = string(part, "id") ?: "unknown-part"
        return when (val partType = string(part, "type")) {
            "text" -> {
                val text = (part["text"] as? JsonPrimitive)?.content ?: ""
                diffDelta(partId, text)?.let { listOf(ChatEvent.TextDelta(turnId = partId, text = it)) } ?: emptyList()
            }
            "reasoning" -> {
                val text = (part["text"] as? JsonPrimitive)?.content ?: ""
                diffDelta(partId, text)?.let { listOf(ChatEvent.ThinkingDelta(turnId = partId, text = it)) } ?: emptyList()
            }
            "tool" -> toolPart(part)
            "step-start" -> listOf(ChatEvent.TurnStarted(turnId = partId))
            "step-finish" -> {
                val events = mutableListOf<ChatEvent>()
                (part["tokens"] as? JsonObject)?.let { tokens ->
                    events.add(
                        ChatEvent.UsageUpdate(
                            inputTokens = tokens.number("input"),
                            outputTokens = tokens.number("output"),
                        ),
                    )
                }
                (part["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { cost ->
                    if (cost > 0) events.add(ChatEvent.UsageUpdate(0, 0, costUsd = cost))
                }
                events.add(ChatEvent.TurnCompleted(turnId = partId, reason = ChatEvent.TurnEndReason.STOP))
                events.toList()
            }
            else -> listOf(other("part:$partType", line))
        }
    }

    private fun toolPart(part: JsonObject): List<ChatEvent> {
        val tool = string(part, "tool") ?: "tool"
        val callId = string(part, "callID") ?: string(part, "id") ?: tool
        val state = part["state"] as? JsonObject
        val status = state?.let { string(it, "status") } ?: "pending"
        val events = mutableListOf<ChatEvent>()
        if (openTools.add(callId)) {
            events.add(ChatEvent.ToolCallStarted(callId, tool))
        }
        state?.get("input")?.let { input ->
            events.add(ChatEvent.ToolCallArgsDelta(callId, input.toString()))
        }
        if (status == "completed" || status == "error") {
            openTools.remove(callId)
            val output = state?.get("output")?.toString()
                ?: (state?.get("error") as? JsonPrimitive)?.content
                ?: ""
            events.add(
                ChatEvent.ToolResult(
                    callId,
                    output,
                    isError = status == "error",
                ),
            )
            events.add(ChatEvent.ToolCallCompleted(callId))
        } else {
            events.add(ChatEvent.ToolCallCompleted(callId))
        }
        return events
    }

    private fun diffDelta(partId: String, fullText: String): String? {
        val previous = seenParts[partId] ?: 0
        if (fullText.length <= previous) {
            seenParts[partId] = previous.coerceAtLeast(fullText.length)
            return null
        }
        seenParts[partId] = fullText.length
        return fullText.substring(previous)
    }

    private fun JsonObject.number(key: String): Long =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content

    private fun type(raw: String) = "co.opencode.$raw"

    private fun other(rawType: String, raw: String) = ChatEvent.Other(type(rawType), raw)
}
