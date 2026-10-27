package com.harnesshub.model

/**
 * Everything a content block can be. The chat renderer maps each block to one
 * designed widget; unknown harness payloads land in an [Other] raw block so
 * nothing is silently dropped.
 */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /** Interleaved thinking/reasoning; collapsible in the UI. */
    data class Thinking(val text: String, val completed: Boolean = false) : ContentBlock

    data class ToolCall(
        val id: EventId,
        var name: String,
        /** Accumulated, possibly still-partial JSON arguments. */
        var argsJson: String,
        val status: ToolStatus = ToolStatus.RUNNING,
        val result: ToolResult? = null,
    ) : ContentBlock

    data class SubAgent(val id: EventId, val task: String, val summary: String? = null) : ContentBlock

    data class Image(val uri: String, val alt: String? = null) : ContentBlock

    data class FileContent(val name: String, val uri: String) : ContentBlock

    data class Diff(val path: String, val hunks: String) : ContentBlock

    data class Artifact(val name: String, val uri: String, val mimeType: String) : ContentBlock

    data class Error(val kind: String, val message: String, val retryable: Boolean = false) : ContentBlock

    /** Preserved uninterpreted harness payload, rendered as a collapsible raw block. */
    data class Other(val tag: String, val json: String) : ContentBlock
}
