package com.harnesshub.claude

import com.harnesshub.spi.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeStreamParserTest {

    private val parser = ClaudeStreamParser()

    @Test
    fun `init line yields SessionStarted`() {
        val events = parser.parseLine(
            "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/tmp\",\"session_id\":\"7ce5c15d-838f-491f-8b25-4a44ee9bd33d\",\"tools\":[\"Bash\",\"Read\"]}",
        )
        assertEquals(listOf(ChatEvent.SessionStarted("7ce5c15d-838f-491f-8b25-4a44ee9bd33d")), events)
    }

    @Test
    fun `assistant text and thinking blocks map into deltas`() {
        val events = parser.parseLine(
            "{\"type\": \"assistant\", \"message\": {\"id\": \"1ac0\", \"type\": \"message\", \"usage\": {\"input_tokens\": 2, \"output_tokens\": 3}, \"content\": [{\"type\": \"text\", \"text\": \"Hello there\"}, {\"type\": \"thinking\", \"thinking\": \"consider all of it\"}]}}",
        )
        assertEquals("1ac0", (events[0] as ChatEvent.TurnStarted).turnId)
        assertEquals("1ac0", (events[1] as ChatEvent.TextDelta).turnId)
        assertEquals("Hello there", (events[1] as ChatEvent.TextDelta).text)
        assertEquals("consider all of it", (events[2] as ChatEvent.ThinkingDelta).text)
        val usage = events.last() as ChatEvent.UsageUpdate
        assertEquals(2, usage.inputTokens)
        assertEquals(3, usage.outputTokens)
    }

    @Test
    fun `tool_use and tool_result pair up`() {
        val use = parser.parseLine(
            "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}",
        )
        val started = use[0] as ChatEvent.ToolCallStarted
        assertEquals("toolu_9", started.id)
        assertEquals("Bash", started.name)

        val result = parser.parseLine(
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_9\",\"content\":\"total 12\",\"is_error\":false}]}}",
        )
        val toolResult = result.single() as ChatEvent.ToolResult
        assertEquals("toolu_9", toolResult.id)
        assertEquals("total 12", toolResult.output)
        assertTrue(!toolResult.isError)
    }

    @Test
    fun `partial stream_event deltas map`() {
        assertEquals(
            listOf(ChatEvent.TextDelta("t1", "Hel")),
            parser.parseLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}}"),
        )
        assertEquals(
            listOf(ChatEvent.ThinkingDelta("t1", "hmm")),
            parser.parseLine("{\"type\":\"stream_event\",\"event\":{\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}}"),
        )
        assertEquals(
            listOf(ChatEvent.ToolCallArgsDelta("t1", "{\"cmd")),
            parser.parseLine("{\"type\":\"stream_event\",\"event\":{\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"cmd\"}}}"),
        )
    }

    @Test
    fun `result line yields usage and turn end, error flagged`() {
        val ok = parser.parseLine(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"OK\",\"duration_ms\":3018,\"usage\":{\"input_tokens\":4,\"output_tokens\":1},\"total_cost_usd\":0.01}",
        )
        val usage = ok[0] as ChatEvent.UsageUpdate
        assertEquals(4, usage.inputTokens)
        assertEquals(1, usage.outputTokens)
        assertEquals(0.01, usage.costUsd!!)
        val runRecord = ok[1] as ChatEvent.ToolResult
        assertEquals("OK", runRecord.output)
        assertEquals(3018L, runRecord.durationMs)

        val err = parser.parseLine(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"API Error: 400\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}",
        )
        val error = err.filterIsInstance<ChatEvent.ErrorEvent>().single()
        assertEquals("exec", error.kind)
        assertTrue(error.message.contains("400"))
    }

    @Test
    fun `status lines are skipped, not fabricated`() {
        assertEquals(0, parser.parseLine("{\"type\":\"system\",\"subtype\":\"status\",\"status\":\"requesting\"}").size)
        assertEquals(0, parser.parseLine("").size)
        assertEquals(0, parser.parseLine("not json at all").size)
    }

    @Test
    fun `command builder shape matches verified flags`() {
        val adapter = ClaudeAdapter(
            claudeCli = "/data/data/com.termux/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js",
        )
        assertEquals(
            listOf(
                "node",
                "/data/data/com.termux/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js",
                "-p", "hi",
                "--output-format", "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--resume", "f560d819",
            ),
            adapter.command("hi", resumeSessionId = "f560d819"),
        )
    }
}
