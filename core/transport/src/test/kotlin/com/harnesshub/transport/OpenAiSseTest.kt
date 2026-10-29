package com.harnesshub.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures taken verbatim from a live OpenAI-compatible local router
 * (127.0.0.1:20128, 2026-10-29): role/content/finish/usage chunks, blank
 * keep-alive lines, an artificial [DONE], and byte-streams split mid-line.
 */
class OpenAiSseTest {

    private val roleChunk =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1793261593,"model":"m","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":""}}],"usage":null}"""

    private val textChunk =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1793261593,"model":"m","choices":[{"index":0,"finish_reason":"stop","delta":{"content":"OK"}}],"usage":{"prompt_tokens":948,"completion_tokens":3,"total_tokens":951}}"""

    private val usageChunk =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1793261593,"model":"m","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""

    @Test
    fun `parses live-router shaped lines`() {
        val parser = OpenAiSse.newParser()
        val chunks = OpenAiSse.feed(parser, "$roleChunk\n\n$textChunk\n\n$usageChunk\n\n")
        assertEquals(3, chunks.size)
        assertEquals("assistant", chunks[0].role)
        assertNull(chunks[0].content)
        assertEquals("OK", chunks[1].content)
        assertEquals("stop", chunks[1].finishReason)
        assertEquals(10L, chunks[2].inputTokens)
        assertEquals(2L, chunks[2].outputTokens)
        assertNull(chunks[2].content)
    }

    @Test
    fun `splits mid-line byte streams`() {
        val parser = OpenAiSse.newParser()
        assertTrue(OpenAiSse.feed(parser, roleChunk.substring(0, 40)).isEmpty())
        val rest = OpenAiSse.feed(parser, roleChunk.substring(40) + "\n\n")
        assertEquals(1, rest.size)
        assertEquals("assistant", rest[0].role)
    }

    @Test
    fun `skips done comments and garbage`() {
        val parser = OpenAiSse.newParser()
        // comment + blank lines produce nothing
        assertTrue(OpenAiSse.feed(parser, ": keep-alive\n\n").isEmpty())
        // [DONE] yields nothing, no throw
        assertTrue(OpenAiSse.feed(parser, "data: [DONE]\n\n").isEmpty())
        // unmapped/garbage data lines are skipped silently
        assertTrue(OpenAiSse.feed(parser, "event: ping\ndata: not-json\n\n").isEmpty())
    }

    @Test
    fun `buffer holds partial line until newline`() {
        val parser = OpenAiSse.newParser()
        // No trailing newline: nothing is emitted yet.
        OpenAiSse.feed(parser, "data: {\"choices\":[],\"usage\":{\"prompt").let { /* noop */ }
        val later = OpenAiSse.feed(parser, "_tokens\":7,\"completion_tokens\":1}}\n")
        // Feeding the tail completes the line — parser must not lose bytes.
        val trailing = OpenAiSse.feed(parser, "\n")
        val all = later + trailing
        assertEquals(1, all.size)
        assertEquals(7L, all[0].inputTokens)
    }
}
