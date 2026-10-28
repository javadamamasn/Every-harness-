package com.harnesshub.codex

import com.harnesshub.spi.ChatEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes the JSONL thread events of `codex exec --json` (official OpenAI
 * schemas, fetched 2026-10-28 and committed under fixtures/):
 *   thread.started {thread_id}
 *   turn.started / turn.completed {usage{input_tokens, output_tokens, ...}} /
 *   turn.failed {error{message}} / error {message}
 *   item.started|updated|completed {item {id, type, ...}}
 *     agent_message{text} reasoning{text} command_execution{command,
 *     aggregated_output, exit_code?, status} file_change{changes[{path,kind}],
 *     status} mcp_tool_call{server, tool, arguments, status, result?/error?}
 *     web_search{query} todo_list{items} error{message}
 *
 * item payloads are FULL values (not deltas) → diffed into deltas per item id,
 * exactly like the OpenCode part mapping.
 */
class CodexStreamParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val seen = HashMap<String, Int>() // itemId → last length (text-like items)
    private val execStarted = HashSet<String>()

    fun mapLine(line: String): List<ChatEvent> {
        val obj = json.parseToJsonElement(line) as? JsonObject
            ?: return listOf(other("malformed", line))
        val type = string(obj, "type") ?: return listOf(other("malformed", line))
        return when (type) {
            "thread.started" -> listOf(
                ChatEvent.SessionStarted(string(obj, "thread_id") ?: ""),
            )
            "turn.started" -> listOf(other("turn.started", line)) // turn ids are implicit in codex
            "turn.completed" -> {
                val usage = obj["usage"] as? JsonObject
                listOf(
                    ChatEvent.UsageUpdate(
                        inputTokens = usage?.number("input_tokens") ?: 0,
                        outputTokens = usage?.number("output_tokens") ?: 0,
                    ),
                    ChatEvent.TurnCompleted(turnId = "codex", reason = ChatEvent.TurnEndReason.STOP),
                )
            }
            "turn.failed" -> {
                val message = (obj["error"] as? JsonObject)?.let { string(it, "message") } ?: "turn failed"
                listOf(
                    ChatEvent.ErrorEvent("provider", message, retryable = false),
                    ChatEvent.TurnCompleted(turnId = "codex", reason = ChatEvent.TurnEndReason.ERROR),
                )
            }
            "error" -> listOf(
                ChatEvent.ErrorEvent("provider", string(obj, "message") ?: "error", retryable = false),
            )
            "item.started", "item.updated", "item.completed" -> {
                val item = obj["item"] as? JsonObject ?: return listOf(other(type, line))
                mapItem(item, terminal = type == "item.completed")
            }
            else -> listOf(other(type, line))
        }
    }

    private fun mapItem(item: JsonObject, terminal: Boolean): List<ChatEvent> {
        val itemType = string(item, "type") ?: return emptyList()
        val id = string(item, "id") ?: itemType
        return when (itemType) {
            "agent_message" -> diffText(item, id, text = true) { turnId, d -> ChatEvent.TextDelta(turnId, d) }
            "reasoning" -> diffText(item, id, text = true) { turnId, d -> ChatEvent.ThinkingDelta(turnId, d) }
            "command_execution" -> commandExecution(item, id, terminal)
            "file_change" -> fileChange(item, id, terminal)
            "mcp_tool_call" -> mcpToolCall(item, id, terminal)
            else -> listOf(other("item:$itemType", item.toString()))
        }
    }

    private fun diffText(
        item: JsonObject,
        id: String,
        text: Boolean,
        factory: (String, String) -> ChatEvent,
    ): List<ChatEvent> = run {
        val value = string(item, "text") ?: ""
        val previous = seen.put(id, value.length) ?: 0
        if (value.length > previous) listOf(factory(id, value.substring(previous))) else emptyList()
    }

    private fun commandExecution(item: JsonObject, id: String, terminal: Boolean): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        if (execStarted.add(id)) {
            events.add(ChatEvent.ToolCallStarted(id, string(item, "command") ?: "command_execution"))
        }
        item["aggregated_output"]?.let { out ->
            diffText(
                JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(out.toString()))),
                "$id:output",
                text = true,
            ) { _, d -> other("command_output", d) }
                .forEach(events::add)
        }
        events.add(ChatEvent.ToolCallArgsDelta(id, item["command"]?.toString() ?: ""))
        if (terminal) {
            events.add(
                ChatEvent.ToolResult(
                    id,
                    item["aggregated_output"]?.toString() ?: "",
                    isError = string(item, "status") == "failed",
                ),
            )
            events.add(ChatEvent.ToolCallCompleted(id))
        }
        return events
    }

    private fun fileChange(item: JsonObject, id: String, terminal: Boolean): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>(
            ChatEvent.DiffProposed(
                turnId = id,
                path = firstChangePath(item),
                hunks = item["changes"]?.toString() ?: "[]",
            ),
        )
        if (terminal) {
            events.add(
                ChatEvent.DiffResolved(
                    turnId = id,
                    path = firstChangePath(item),
                    accepted = string(item, "status") == "completed",
                ),
            )
        }
        return events
    }

    private fun mcpToolCall(item: JsonObject, id: String, terminal: Boolean): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        if (execStarted.add(id)) {
            val server = string(item, "server") ?: "mcp"
            events.add(
                ChatEvent.ToolCallStarted(
                    id,
                    "${server}:#${string(item, "tool") ?: "?"}",
                ),
            )
        }
        item["arguments"]?.let { events.add(ChatEvent.ToolCallArgsDelta(id, it.toString())) }
        if (terminal) {
            val output = item["result"]?.toString()
                ?: (item["error"] as? JsonObject)?.let { string(it, "message") }
                ?: ""
            events.add(
                ChatEvent.ToolResult(
                    id,
                    output,
                    isError = item["error"] != null || string(item, "status") == "failed",
                ),
            )
            events.add(ChatEvent.ToolCallCompleted(id))
        }
        return events
    }

    private fun firstChangePath(item: JsonObject) =
        (item["changes"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.let { string(it, "path") } ?: ""

    private fun JsonObject.number(key: String): Long =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content

    private fun type(raw: String) = "co.codex.$raw"

    private fun other(rawType: String, raw: String) =
        ChatEvent.Other(type(rawType), raw)
}
