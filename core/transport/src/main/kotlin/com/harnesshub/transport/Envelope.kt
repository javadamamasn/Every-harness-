package com.harnesshub.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire envelope of the Harness Hub bridge protocol (protocol 1) — the exact
 * mirror of bridge/bridge.js in the repo. One JSON object per WebSocket frame.
 */
@Serializable
data class Envelope(
    val id: String? = null,
    val type: String, // req | res | evt | err | hello
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: BridgeError? = null,
    val channel: String? = null,
    val seq: Long? = null,
)

@Serializable
data class BridgeError(
    val code: String,
    val message: String,
    val data: JsonElement? = null,
)

/** Error codes the bridge is allowed to report (build-spec Appendix A). */
enum class BridgeErrorCode {
    AUTH_FAILED, FORBIDDEN_PATH, NOT_ALLOWLISTED, NOT_FOUND,
    ALREADY_RUNNING, TIMEOUT, PROTOCOL_MISMATCH, INTERNAL;

    companion object {
        fun from(raw: String): BridgeErrorCode? = entries.firstOrNull { it.name == raw }
    }
}
