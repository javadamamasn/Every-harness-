package com.harnesshub.claude

import com.harnesshub.spi.ChatEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Claude Code stream-json parser → unified [ChatEvent]s.
 *
 * Line shapes captured live from `claude 2.1.112 -p ... --output-format
 * stream-json --include-partial-messages --verbose` (M5, docs/DECISIONS.md):
 * {"type":"system","subtype":"init","session_id":...} / {"type":"system",
 * "subtype":"status",...} / {"type":"assistant","message":{"content":[
 * {type:text|thinking|tool_use,...}],"usage":{...}}} / {"type":"user",
 * "message":{"content":[{type:tool_result,tool_use_id,content,is_error}]}} /
 * {"type":"result","subtype":"success","is_error":false,"result":...,
 * "usage":{input_tokens,...},"total_cost_usd":...,"duration_ms":...} /
 * {"type":"stream_event","event":{"type":"content_block_delta","delta":
 * {"type":"text_delta","text":...}}} — all verified; partial-delta routing
 * follows the stream_event envelope (same run, flag verified live).
 */
class ClaudeStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    fun parseAll(lines: List<String>): List<ChatEvent> =
        lines.flatMap { line -> parse(line) }

    fun parseLine(line: String): List<ChatEvent> = parse(line)

    private fun parse(line: String): List<ChatEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = try {
            json.parseToJsonElement(trimmed).let { it as? JsonObject } ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return when (obj.str("type")) {
            "system" -> obj.str("subtype")?.let { sub ->
                if (sub == "init" && obj.str("session_id") != null) {
                    listOf(ChatEvent.SessionStarted(obj.str("session_id")!!))
                } else {
                    emptyList() // status lines are transient CLI chatter (documented skip)
                }
            } ?: emptyList()

            "assistant" -> obj.obj("message")?.let { emitAssistant(it) } ?: emptyList()
            "user" -> obj.obj("message")?.let { emitUser(it) } ?: emptyList()
            "stream_event" -> obj.obj("event")?.let { emitPartial(it) } ?: emptyList()
            "result" -> emitResult(obj)
            else -> emptyList()
        }
    }

    private fun emitAssistant(message: JsonObject): List<ChatEvent> {
        val content = message.arr("content") ?: return usageOf(message)
        val turnId = message.str("id") ?: "t1"
        val events = ArrayList<ChatEvent>(content.size + 2)
        var opened = false
        content.forEach { el ->
            val block = el as? JsonObject
            if (block != null) {
                when (block.str("type")) {
                    "text" -> {
                        if (!opened) {
                            events.add(ChatEvent.TurnStarted(turnId))
                            opened = true
                        }
                        block.str("text")?.takeIf { it.isNotEmpty() }?.let {
                            events.add(ChatEvent.TextDelta(turnId, it))
                        }
                    }
                    "thinking" -> block.str("thinking")?.let {
                        events.add(ChatEvent.ThinkingDelta(turnId, it))
                    }
                    "tool_use" -> {
                        events.add(ChatEvent.ToolCallStarted(block.str("id") ?: "tool", block.str("name") ?: "tool"))
                        events.add(ChatEvent.ToolCallArgsDelta(block.str("id") ?: "tool", block.strOrRaw("input")))
                    }
                    else -> {}
                }
            }
        }
        message.obj("usage")?.let { usageOf(message) }?.let { events.addAll(it) }
        return events
    }

    private fun emitUser(message: JsonObject): List<ChatEvent> {
        val content = message.arr("content") ?: return emptyList()
        return content.mapNotNull { el ->
            (el as? JsonObject)?.takeIf { it.str("type") == "tool_result" }?.let { block ->
                val out = when (val c = block["content"]) {
                    is JsonPrimitive -> c.content
                    is JsonArray -> c.joinToString("\n") { (it as? JsonObject)?.str("text") ?: "" }
                    else -> ""
                }
                ChatEvent.ToolResult(
                    id = block.str("tool_use_id") ?: "tool",
                    output = out,
                    isError = block.bool("is_error") == true,
                    durationMs = null,
                )
            }
        }
    }

    private fun emitPartial(event: JsonObject): List<ChatEvent> {
        val delta = event.obj("delta") ?: return emptyList()
        val turnId = "t1"
        return when (delta.str("type")) {
            "text_delta" -> listOf(ChatEvent.TextDelta(turnId, delta.str("text") ?: ""))
            "thinking_delta" -> listOf(ChatEvent.ThinkingDelta(turnId, delta.str("thinking") ?: ""))
            "input_json_delta" -> listOf(ChatEvent.ToolCallArgsDelta(turnId, delta.str("partial_json") ?: ""))
            else -> emptyList()
        }
    }

    private fun emitResult(obj: JsonObject): List<ChatEvent> {
        val usage = obj.obj("usage")
        val cost = obj.str("total_cost_usd")?.toDoubleOrNull()
        val events = ArrayList<ChatEvent>(2)
        if (usage != null) {
            events.add(
                ChatEvent.UsageUpdate(
                    inputTokens = usage.str("input_tokens")?.toLongOrNull() ?: 0,
                    outputTokens = usage.str("output_tokens")?.toLongOrNull() ?: 0,
                    costUsd = cost,
                ),
            )
        }
        obj.str("duration_ms")?.toLongOrNull()?.let { ms ->
            obj.str("result")?.let {
                events.add(ChatEvent.ToolResult(id = "run", output = it, isError = false, durationMs = ms))
            }
        }
        if (obj.bool("is_error") == true) {
            events.add(ChatEvent.ErrorEvent("exec", obj.str("result") ?: "claude run failed", retryable = true))
        }
        return events
    }

    private fun usageOf(message: JsonObject): List<ChatEvent> {
        val usage = message.obj("usage") ?: return emptyList()
        return listOf(
            ChatEvent.UsageUpdate(
                inputTokens = usage.str("input_tokens")?.toLongOrNull() ?: 0,
                outputTokens = usage.str("output_tokens")?.toLongOrNull() ?: 0,
                costUsd = null,
            ),
        )
    }
}

// small JsonObject string utilities
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content.ifBlankToNull()

private fun String?.ifBlankToNull(): String? = this?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonObject.strOrRaw(key: String): String {
    val v = this[key] ?: return ""
    return v.toString()
}
