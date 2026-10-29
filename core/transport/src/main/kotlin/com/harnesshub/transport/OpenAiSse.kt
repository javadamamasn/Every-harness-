package com.harnesshub.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure parser for OpenAI-compatible /v1/chat/completions SSE streams
 * (`data: {chunk}` lines, optional `[DONE]`, usage on the final chunk).
 * Wire shape verified live against the on-device local router on 2026-10-29
 * (chunk.delta.content "OK", finish_reason stop, usage.predicted_tokens etc.).
 * Kept OkHttp-free so it is unit-testable; the HTTP layer only feeds chunks in.
 */
object OpenAiSse {

    data class Chunk(
        val role: String? = null,
        val content: String? = null,
        val finishReason: String? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Feed one arbitrary chunk of raw bytes; returns every complete event. */
    fun feed(parser: Parser, raw: String): List<Chunk> {
        val out = mutableListOf<Chunk>()
        parser.buffer.append(raw)
        var lineStart = 0
        while (true) {
            val nl = parser.buffer.indexOf('\n', lineStart)
            if (nl < 0) break
            val line = parser.buffer.substring(lineStart, nl).trimEnd()
            lineStart = nl + 1
            val chunk = parseLine(line)
            if (chunk != null) out.add(chunk)
        }
        if (lineStart > 0) parser.buffer.delete(0, lineStart)
        return out
    }

    fun newParser() = Parser()

    class Parser internal constructor() {
        internal val buffer = StringBuilder()
    }

    /** Parse one `data: …` line; blank lines, comments and [DONE] yield null. */
    internal fun parseLine(line: String): Chunk? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return null
        return runCatching { json.decodeFromString(ChunkDto.serializer(), payload) }
            .getOrNull()
            ?.toChunk()
    }

    @kotlinx.serialization.Serializable
    internal data class ChunkDto(
        val choices: List<ChoiceDto> = emptyList(),
        val usage: UsageDto? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class ChoiceDto(
        val delta: DeltaDto? = null,
        val finish_reason: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class DeltaDto(
        val role: String? = null,
        val content: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class UsageDto(
        val prompt_tokens: Long? = null,
        val completion_tokens: Long? = null,
    )

    private fun ChunkDto.toChunk(): Chunk {
        val choice = choices.firstOrNull()
        return Chunk(
            role = choice?.delta?.role,
            content = choice?.delta?.content?.takeIf { it.isNotEmpty() },
            finishReason = choice?.finish_reason?.takeIf { it != "null" },
            inputTokens = usage?.prompt_tokens,
            outputTokens = usage?.completion_tokens,
        )
    }
}
