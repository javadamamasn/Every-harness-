package com.harnesshub.codex

import com.harnesshub.spi.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixture test against the official event shapes committed under
 * harness/codex/fixtures/codex-events.jsonl (source: openai/codex sdk
 * typescript events.ts + items.ts; CLI not installed on this device yet —
 * snapshot-derived, honestly).
 */
class CodexStreamParserTest {

    private val lines = javaClass.classLoader!!
        .getResourceAsStream("codex-events.jsonl")!!
        .readBytes().decodeToString().trim().split("\n")
        .onEach { assertTrue(it.isNotBlank()) }

    private val parser = CodexStreamParser()

    private fun all() = lines.flatMap { parser.mapLine(it).filter { e -> e !is ChatEvent.Other } }

    @Test
    fun `thread id becomes SessionStarted`() {
        assertTrue(all().contains(ChatEvent.SessionStarted("th_fix_01")))
    }

    @Test
    fun `reasoning item updates become cumulative deltas`() {
        val thinking = all().filterIsInstance<ChatEvent.ThinkingDelta>()
        assertEquals(listOf("Think", "ing out loud"), thinking.map { it.text })
    }

    @Test
    fun `command execution turns into tool call and result`() {
        val events = all()
        assertTrue(events.contains(ChatEvent.ToolCallStarted("cm1", "ls -la")))
        val result = events.filterIsInstance<ChatEvent.ToolResult>().first { it.id == "cm1" }
        assertEquals(false, result.isError)
        assertTrue(events.contains(ChatEvent.ToolCallCompleted("cm1")))
    }

    @Test
    fun `agent message becomes TextDelta for the item`() {
        val deltas = all().filterIsInstance<ChatEvent.TextDelta>()
        assertEquals(listOf("Hello there"), deltas.map { it.text })
    }

    @Test
    fun `file change proposes a diff and resolves it`() {
        val proposals = all().filterIsInstance<ChatEvent.DiffProposed>()
        assertEquals(1, proposals.size)
        assertEquals("src/main.rs", proposals[0].path)
        val resolved = all().filterIsInstance<ChatEvent.DiffResolved>()
        assertTrue(resolved[0].accepted)
    }

    @Test
    fun `mcp tool call maps with server name`() {
        val events = all()
        assertTrue(events.any { e ->
            e is ChatEvent.ToolCallStarted && e.id == "mcp1" && e.name == "fs:#read"
        })
        val toolResult = events.filterIsInstance<ChatEvent.ToolResult>().first { it.id == "mcp1" }
        assertEquals(false, toolResult.isError)
    }

    @Test
    fun `turn completed carries usage then closes the turn`() {
        val events = all()
        val usage = events.filterIsInstance<ChatEvent.UsageUpdate>().single()
        assertEquals(100L, usage.inputTokens)
        assertEquals(22L, usage.outputTokens)
        assertEquals(
            ChatEvent.TurnEndReason.STOP,
            events.filterIsInstance<ChatEvent.TurnCompleted>().single().reason,
        )
    }

    @Test
    fun `malformed line survives as Other`() {
        val events = CodexStreamParser().mapLine("not-json")
        assertEquals(1, events.size)
        assertTrue(events[0] is ChatEvent.Other)
    }
}
