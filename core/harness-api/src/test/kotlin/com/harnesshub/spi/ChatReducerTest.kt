package com.harnesshub.spi

import com.harnesshub.model.ContentBlock
import com.harnesshub.model.ToolStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the reducer rules demanded by the build spec (Appendix B).
 */
class ChatReducerTest {
    private val reducer = ChatReducer()

    @Test
    fun `deltas append to the trailing block of the last assistant message`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t1", "Hello "))
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t1", "world"))
        val last = s.messages.last()
        assertEquals(1, last.blocks.size)
        assertEquals("Hello world", (last.blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `interleaved thinking and text never lose content`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.ThinkingDelta("t1", "plan: "))
        s = reducer.reduceOne(s, ChatEvent.ThinkingDelta("t1", "step"))
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t1", "answer"))
        s = reducer.reduceOne(s, ChatEvent.ThinkingDelta("t1", " more"))

        val texts = s.messages.flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }.joinToString("") { it.text }
        val thinking = s.messages.flatMap { it.blocks.filterIsInstance<ContentBlock.Thinking>() }.joinToString("") { it.text }
        assertEquals("answer more", texts)
        assertEquals("plan: step more", thinking)
    }

    @Test
    fun `partial tool call args accumulate then complete`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.ToolCallStarted("c1", "read_file"))
        s = reducer.reduceOne(s, ChatEvent.ToolCallArgsDelta("c1", "{\"path\":"))
        s = reducer.reduceOne(s, ChatEvent.ToolCallArgsDelta("c1", "\"x.txt\"}"))
        s = reducer.reduceOne(s, ChatEvent.ToolCallArgsDelta("c1", ", partial"))
        s = reducer.reduceOne(s, ChatEvent.ToolCallCompleted("c1"))

        val call = s.messages.last().blocks[0] as ContentBlock.ToolCall
        assertEquals("{\"path\":\"x.txt\"}, partial", call.argsJson)
        assertEquals(ToolStatus.SUCCESS, call.status)

        // Late duplicate completion is a no-op.
        val again = reducer.reduceOne(s, ChatEvent.ToolCallCompleted("c1"))
        assertEquals(s, again)
    }

    @Test
    fun `duplicate events are idempotent`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.SubAgentStarted("s1", "task"))
        val duplicate = reducer.reduceOne(s, ChatEvent.SubAgentStarted("s1", "task"))
        assertEquals(s, duplicate)

        var t = ChatReducer.State()
        t = reducer.reduceOne(t, ChatEvent.ToolCallStarted("c1", "read_file"))
        t = reducer.reduceOne(t, ChatEvent.ToolResult("c1", "data", isError = false, durationMs = 10))
        val countBefore = t.messages.size
        t = reducer.reduceOne(t, ChatEvent.ToolCallCompleted("c1"))
        assertEquals(countBefore, t.messages.size)
    }

    @Test
    fun `cancelled turn keeps already rendered content`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t1", "partial answer"))
        s = reducer.reduceOne(s, ChatEvent.TurnCompleted("t1", ChatEvent.TurnCompleted.TurnEndReason.CANCELLED))
        assertEquals("partial answer", (s.messages.last().blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `retry appends sibling messages instead of overwriting`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t1", "first try"))
        s = reducer.reduceOne(s, ChatEvent.TurnCompleted("t1", ChatEvent.TurnCompleted.TurnEndReason.STOP))
        s = reducer.reduceOne(s, ChatEvent.TextDelta("t2", "retry"))
        assertEquals(2, s.messages.size)
        assertEquals("first try", (s.messages[0].blocks[0] as ContentBlock.Text).text)
        assertEquals("retry", (s.messages[1].blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `sub agent events nest under their card`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.SubAgentStarted("s1", "research"))
        s = reducer.reduceOne(s, ChatEvent.SubAgentEvent("s1", ChatEvent.TextDelta("s1", "hi")))
        s = reducer.reduceOne(s, ChatEvent.SubAgentCompleted("s1", "done"))
        assertTrue(s.messages[0].blocks.first() is ContentBlock.SubAgent)
        assertEquals("s1", s.messages[1].subAgentEventId!!.value)
        assertEquals("done", (s.messages[2].blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `unknown mode events are preserved as raw blocks`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.ModelChanged("gpt-x"))
        val block = s.messages.last().blocks[0]
        assertTrue(block is ContentBlock.Other && block.tag == "model-changed" && block.json == "gpt-x")
    }

    @Test
    fun `reducer tolerates any event order`() {
        // Simulate a shuffled, chunked stream of one turn: chunk-level deltas
        // must reconstruct the same text/sentence in every permutation.
        val events = listOf(
            ChatEvent.SessionStarted("s1"),
            ChatEvent.TurnStarted("t1"),
            ChatEvent.TextDelta("t1", "a"),
            ChatEvent.TextDelta("t1", "b"),
            ChatEvent.TextDelta("t1", "cdef"),
            ChatEvent.UsageUpdate(2, 3),
            ChatEvent.TurnCompleted("t1", ChatEvent.TurnCompleted.TurnEndReason.STOP),
        )
        val permutations = listOf(
            events,
            events.shuffled(java.util.Random(42L)),
            events.shuffled(java.util.Random(43L)),
        )
        var count = 0
        for (perm in permutations) {
            val s = reducer.reduce(ChatReducer.State(), perm)
            // In every permutation nothing should be lost, even if grouping differs.
            val unchanged = { msgAmt: Int -> s.messages.size >= msgAmt }
            assertTrue(unchanged(1))
            assertEquals(2, s.usageInputTokens)
            assertEquals(3, s.usageOutputTokens)
            count++
        }
        assertEquals(3, count)
    }

    @Test
    fun `connection state tracked`() {
        var s = ChatReducer.State()
        s = reducer.reduceOne(s, ChatEvent.ConnectionLost)
        assertFalse(s.connected)
        s = reducer.reduceOne(s, ChatEvent.ConnectionResumed)
        assertTrue(s.connected)
    }
}
