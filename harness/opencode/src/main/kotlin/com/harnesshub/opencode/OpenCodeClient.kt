package com.harnesshub.opencode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Direct Tier-1 HTTP+SSE client for `opencode serve` (no relay, no WebSocket).
 * Loopback-only base URL, HTTP Basic auth when a password is set
 * (OPENCODE_SERVER_PASSWORD; username kept at the documented default `opencode`).
 *
 * Verified live against `opencode serve` v1.19.3 (2026-10-28) and the committed
 * OpenAPI snapshot:
 *   GET  /global/health                          -> {healthy, version}
 *   GET  /session / POST /session {title}        -> Session {id "^ses", ...}
 *   POST /api/session/{id}/prompt {prompt:{text}}-> admitted
 *   GET  /event                                  -> SSE, `data: {...}` lines
 *   POST /session/{id}/abort                     -> aborted
 *   POST /permission/{requestID}/reply {reply}   -> snapshot-derived (not live yet)
 */
class OpenCodeClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val password: String? = null,
) {

    private val base = "http://$host:$port"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams stay open
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    data class Health(val healthy: Boolean, val version: String)

    data class OcSession(
        val id: String,
        val title: String,
        val cost: Double,
        val inputTokens: Long,
        val outputTokens: Long,
    )

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (password != null) builder.header("Authorization", Credentials.basic("opencode", password))
        return builder
    }

    private suspend fun call(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private inline fun <T> Response.useJson(block: (String) -> T): T =
        body?.let { resp -> block(resp.string()) } ?: error("empty body (HTTP $code)")

    suspend fun health(): Health =
        call(authorized(Request.Builder()).url("$base/global/health").build()).use { resp ->
            resp.useJson { body ->
                val obj = json.parseToJsonElement(body).let { it as? JsonObject } ?: error("malformed health body")
                Health(
                    healthy = (obj["healthy"] as? JsonPrimitive)?.content == "true",
                    version = (obj["version"] as? JsonPrimitive)?.content ?: "",
                )
            }
        }

    suspend fun createSession(title: String = "Harness Hub"): OcSession =
        call(
            authorized(Request.Builder())
                .url("$base/session")
                .post(jsonBody("""{"title":${quote(title)}}"""))
                .build(),
        ).use { resp ->
            resp.useJson { parseSession(it) }
        }

    suspend fun listSessions(): List<OcSession> =
        call(authorized(Request.Builder()).url("$base/session").get().build()).use { resp ->
            resp.useJson { body ->
                val arr = json.parseToJsonElement(body).let { arr -> arr as? JsonArray } ?: error("malformed sessions body")
                arr.map { parseSession(it.toString()) }
            }
        }

    /** Sends one user turn; delivery "steer" matches live server behavior. */
    suspend fun prompt(sessionId: String, text: String, delivery: String = "steer"): Boolean =
        call(
            authorized(Request.Builder())
                .url("$base/api/session/$sessionId/prompt")
                .post(jsonBody("""{"prompt":{"text":${quote(text)}},"delivery":${quote(delivery)}}"""))
                .build(),
        ).use { it.isSuccessful }

    suspend fun abort(sessionId: String): Boolean =
        call(
            authorized(Request.Builder())
                .url("$base/session/$sessionId/abort")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ).use { it.isSuccessful }

    /**
     * Approval flow (fixture-derived, not yet live-verified — honest partial).
     * reply is one of once|always|reject per the snapshot enum.
     */
    suspend fun replyPermission(requestId: String, reply: String, message: String? = null): Boolean {
        require(reply in setOf("once", "always", "reject")) { "invalid reply: $reply" }
        val payload = message?.let { """{"reply":${quote(reply)},"message":${quote(it)}}""" }
            ?: """{"reply":${quote(reply)}}"""
        return call(
            authorized(Request.Builder())
                .url("$base/permission/$requestId/reply")
                .post(jsonBody(payload))
                .build(),
        ).use { it.isSuccessful }
    }

    /**
     * Long-lived SSE flow over GET /event. Reconnects with jittered exponential
     * backoff; each emission is one raw JSON event line (envelope id/type/properties).
     */
    fun events(): Flow<String> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            var backoff = 250L
            while (isActive) {
                try {
                    call(authorized(Request.Builder()).url("$base/event").get().build()).use { resp ->
                        if (!resp.isSuccessful) throw IOException("SSE HTTP ${resp.code}")
                        backoff = 250L
                        val src = resp.body?.source() ?: throw IOException("no SSE body")
                        while (true) {
                            val line = src.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) trySend(line.removePrefix("data: ").trim())
                        }
                    }
                } catch (e: IOException) {
                    // retry below
                }
                delay(backoff + Random.nextLong(0, 300))
                backoff = (backoff * 2).coerceAtMost(8_000)
            }
        }
        awaitClose {
            job.cancel()
        }
    }

    private fun parseSession(body: String): OcSession {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("malformed session body")
        val tokens = obj["tokens"] as? JsonObject
        fun tok(key: String): Long =
            (tokens?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
        return OcSession(
            id = (obj["id"] as? JsonPrimitive)?.content ?: error("session without id"),
            title = (obj["title"] as? JsonPrimitive)?.content ?: "",
            cost = (obj["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            inputTokens = tok("input"),
            outputTokens = tok("output"),
        )
    }

    private fun jsonBody(payload: String): RequestBody = payload.toRequestBody("application/json".toMediaType())

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun quote(s: String): String =
            "\u0022" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\u0022"
    }
}
