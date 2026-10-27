package com.harnesshub.spi

import com.harnesshub.model.ContentBlock
import com.harnesshub.model.EventId
import com.harnesshub.model.Message
import com.harnesshub.model.MessageId
import com.harnesshub.model.SessionId
import com.harnesshub.model.ToolResult
import com.harnesshub.model.ToolStatus

/**
 * Pure reducer turning [ChatEvent] streams into the one display state the chat
 * UI renders. Every adapter's event flow funnels through [reduce] so chat
 * rendering rules live in exactly one place (build-spec Appendix B).
 *
 * Invariants (see ChatReducerTest):
 * - duplicate delivery of the same event is idempotent;
 * - partial or malformed tool-call JSON is tolerated until completion;
 * - interleaved thinking/text never loses content (each delta appends to its
 *   matching trailing block, or opens a new block at the tail);
 * - a cancelled turn keeps everything rendered so far — no event erases content;
 * - a retried turn appends sibling messages rather than overwriting;
 * - sub-agent events nest under their card ([Message.subAgentEventId]);
 * - events with no dedicated hardened rendering are preserved as raw blocks.
 */
class ChatReducer {

    data class State(
        val sessionId: SessionId = SessionId(""),
        val messages: List<Message> = emptyList(),
        val usageInputTokens: Long = 0,
        val usageOutputTokens: Long = 0,
        val connected: Boolean = true,
    )

    private var messageCounter = 0L

    private fun nextMessageId() = MessageId("m-${messageCounter++}")

    fun reduce(current: State, events: List<ChatEvent>): State {
        var state = current
        for (event in events) state = reduceOne(state, event)
        return state
    }

    fun reduceOne(current: State, event: ChatEvent): State = when (event) {
        is ChatEvent.SessionStarted ->
            if (current.sessionId != SessionId("")) current
            else current.copy(sessionId = SessionId(event.sessionId))

        is ChatEvent.TurnStarted -> current
        is ChatEvent.TurnCompleted -> current // keeps everything already rendered

        is ChatEvent.ConnectionLost -> current.copy(connected = false)
        is ChatEvent.ConnectionResumed -> current.copy(connected = true)
        is ChatEvent.UsageUpdate -> current.copy(
            usageInputTokens = current.usageInputTokens + event.inputTokens,
            usageOutputTokens = current.usageOutputTokens + event.outputTokens,
        )

        is ChatEvent.TextDelta -> appendDelta(
            current,
            matches = { it is ContentBlock.Text },
            toBlock = { old, text -> (old as ContentBlock.Text).copy(text = old.text + text) },
            newText = event.text,
            emptyBlock = { ContentBlock.Text(event.text) },
        )

        is ChatEvent.ThinkingDelta -> appendDelta(
            current,
            matches = { it is ContentBlock.Thinking && !it.completed },
            toBlock = { old, text -> (old as ContentBlock.Thinking).copy(text = old.text + text) },
            newText = event.text,
            emptyBlock = { ContentBlock.Thinking(event.text) },
        )

        is ChatEvent.ThinkingCompleted -> {
            val msgIdx = current.messages.indexOfLast { m -> m.blocks.any { it is ContentBlock.Thinking && !it.completed } }
            if (msgIdx < 0) {
                current
            } else {
                val blocks = current.messages[msgIdx].blocks.toMutableList()
                val blockIdx = blocks.indexOfFirst { it is ContentBlock.Thinking && !it.completed }
                blocks[blockIdx] = (blocks[blockIdx] as ContentBlock.Thinking).copy(completed = true)
                replaceMessageBlocks(current, msgIdx, blocks)
            }
        }

        is ChatEvent.ToolCallStarted ->
            if (hasToolCall(current, event.id)) {
                current
            } else {
                addMessage(
                    current,
                    Message(
                        id = nextMessageId(),
                        sessionId = current.sessionId,
                        role = "assistant",
                        blocks = listOf(
                            ContentBlock.ToolCall(
                                id = EventId(event.id),
                                name = event.name,
                                argsJson = "",
                                status = ToolStatus.RUNNING,
                            ),
                        ),
                    ),
                )
            }

        is ChatEvent.ToolCallArgsDelta -> mapToolCall(current, event.id) { call ->
            // Partial JSON: append verbatim; completed call JSON stays monotonic.
            call.copy(argsJson = call.argsJson + event.argsJsonChunk)
        }

        is ChatEvent.ToolCallCompleted -> mapRunningToolCall(current, event.id) { call ->
            call.copy(status = ToolStatus.SUCCESS)
        }

        is ChatEvent.ToolResult -> mapToolCall(current, event.id) { call ->
            call.copy(
                status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS,
                result = ToolResult(
                    output = event.output,
                    isError = event.isError,
                    durationMs = event.durationMs,
                ),
            )
        }

        is ChatEvent.SubAgentStarted ->
            if (hasSubAgent(current, event.id)) {
                current
            } else {
                addMessage(
                    current,
                    Message(
                        id = nextMessageId(),
                        sessionId = current.sessionId,
                        role = "assistant",
                        blocks = listOf(ContentBlock.SubAgent(EventId(event.id), task = event.task)),
                    ),
                )
            }

        is ChatEvent.SubAgentEvent -> addMessage(
            current,
            Message(
                id = nextMessageId(),
                sessionId = current.sessionId,
                role = "assistant",
                subAgentEventId = EventId(event.id),
                blocks = listOf(ContentBlock.Other(tag = "sub-agent", json = event.toString())),
            ),
        )

        is ChatEvent.SubAgentCompleted ->
            if (event.summary.isNullOrBlank()) {
                current
            } else {
                addMessage(
                    current,
                    Message(
                        id = nextMessageId(),
                        sessionId = current.sessionId,
                        role = "assistant",
                        subAgentEventId = EventId(event.id),
                        blocks = listOf(ContentBlock.Text(event.summary)),
                    ),
                )
            }

        is ChatEvent.ImageAdded -> addRaw(
            current,
            ContentBlock.Image(uri = event.uri, alt = event.alt),
        )
        is ChatEvent.FileAdded -> addRaw(
            current,
            ContentBlock.FileContent(name = event.name, uri = event.uri),
        )
        is ChatEvent.ArtifactAvailable -> addRaw(
            current,
            ContentBlock.Artifact(name = event.name, uri = event.uri, mimeType = event.mimeType),
        )
        is ChatEvent.DiffProposed -> addRaw(
            current,
            ContentBlock.Diff(path = event.path, hunks = event.hunks),
        )
        is ChatEvent.DiffResolved -> addRaw(
            current,
            ContentBlock.Diff(path = event.path, hunks = if (event.accepted) "accepted" else "rejected"),
        )
        is ChatEvent.ApprovalRequested -> addRaw(
            current,
            ContentBlock.Error(
                kind = "approval-request",
                message = "Tool '${event.tool}' requested approval; args=${event.arguments}",
                retryable = false,
            ),
        )
        is ChatEvent.ApprovalResolved -> addRaw(
            current,
            ContentBlock.Error(
                kind = "approval-resolved",
                message = "Approval ${event.id} ${if (event.approved) "approved" else "denied"}",
                retryable = false,
            ),
        )
        is ChatEvent.ModelChanged -> addRaw(
            current,
            ContentBlock.Other(tag = "model-changed", json = event.modelId),
        )
        is ChatEvent.ErrorEvent -> addRaw(
            current,
            ContentBlock.Error(kind = event.kind, message = event.message, retryable = event.retryable),
        )
    }

    // ── append helpers ──────────────────────────────────────────────────────

    /**
     * Appends [newText] to the matching trailing block of the last assistant
     * message; if none matches (or there is none), opens a new message with
     * [emptyBlock] so nothing is ever lost when thinking and text interleave.
     */
    private inline fun appendDelta(
        state: State,
        matches: (ContentBlock) -> Boolean,
        toBlock: (ContentBlock, String) -> ContentBlock,
        newText: String,
        emptyBlock: () -> ContentBlock,
    ): State {
        val msgIdx = state.messages.indexOfLast { it.role == "assistant" }
        val blocksIdx = if (msgIdx < 0) -1 else state.messages[msgIdx].blocks.indexOfLast(matches)
        if (msgIdx < 0 || blocksIdx < 0) {
            return addMessage(
                state,
                Message(id = nextMessageId(), sessionId = state.sessionId, role = "assistant", blocks = listOf(emptyBlock())),
            )
        }
        val blocks = state.messages[msgIdx].blocks.toMutableList()
        blocks[blocksIdx] = toBlock(blocks[blocksIdx], newText)
        return replaceMessageBlocks(state, msgIdx, blocks)
    }

    // ── tool-call helpers ───────────────────────────────────────────────────

    private fun hasToolCall(state: State, id: String): Boolean = state.messages.any { m ->
        m.blocks.any { it is ContentBlock.ToolCall && it.id == EventId(id) }
    }

    private fun hasSubAgent(state: State, id: String): Boolean = state.messages.any { m ->
        m.blocks.any { it is ContentBlock.SubAgent && it.id == EventId(id) }
    }

    private fun toolCallRunning(state: State, id: String): Boolean {
        val call = findToolCall(state, id) ?: return false
        return call.status == ToolStatus.RUNNING
    }

    private fun mapToolCall(state: State, id: String, mapping: (ContentBlock.ToolCall) -> ContentBlock.ToolCall): State {
        val msgIdx = state.messages.indexOfFirst { m ->
            m.blocks.any { it is ContentBlock.ToolCall && it.id == EventId(id) }
        }
        if (msgIdx < 0) return state
        val blocks = state.messages[msgIdx].blocks.toMutableList()
        val blockIdx = blocks.indexOfFirst { it is ContentBlock.ToolCall && it.id == EventId(id) }
        val call = blocks[blockIdx] as? ContentBlock.ToolCall ?: return state
        blocks[blockIdx] = mapping(call)
        return replaceMessageBlocks(state, msgIdx, blocks)
    }

    private fun findToolCall(state: State, id: String): ContentBlock.ToolCall? {
        for (m in state.messages) {
            for (b in m.blocks) {
                if (b is ContentBlock.ToolCall && b.id == EventId(id)) return b
            }
        }
        return null
    }

    private inline fun mapRunningToolCall(state: State, id: String, mapping: (ContentBlock.ToolCall) -> ContentBlock.ToolCall): State =
        if (toolCallRunning(state, id)) mapToolCall(state, id, mapping) else state

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun replaceMessageBlocks(state: State, msgIdx: Int, blocks: List<ContentBlock>): State {
        val m = state.messages[msgIdx]
        val newMessages = state.messages.toMutableList()
        newMessages[msgIdx] = m.copy(blocks = blocks)
        return state.copy(messages = newMessages)
    }

    private fun addMessage(state: State, message: Message): State =
        state.copy(messages = state.messages + message)

    private fun addRaw(state: State, block: ContentBlock): State = addMessage(
        state,
        Message(id = nextMessageId(), sessionId = state.sessionId, role = "assistant", blocks = listOf(block)),
    )
}
