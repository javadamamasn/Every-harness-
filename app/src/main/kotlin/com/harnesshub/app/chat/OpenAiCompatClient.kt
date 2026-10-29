package com.harnesshub.app.chat

import com.harnesshub.spi.ChatEvent
import com.harnesshub.transport.OpenAiSse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * M8 adapter: generic OpenAI-compatible endpoint (`/v1/models`,
 * `/v1/chat/completions` with SSE streaming). Wire shapes verified live on
 * the on-device local router (2026-10-29); the parser itself is pure and
 * unit-tested (OpenAiSseTest). Errors surface as ChatEvent, never raw HTTP.
 */
class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "base URL must be http(s)"
        }
    }

    /** GET /v1/models → sorted model ids, or an error text for the UI. */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/models")
                .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("models HTTP ${resp.code}: ${body.take(200)}")
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until data.length())
                    .map { data.getJSONObject(it).optString("id") }
                    .filter { it.isNotBlank() }
                    .sorted()
            }
        }
    }

    /**
     * One streamed turn; emits TextDelta per content piece, UsageUpdate with
     * the final usage chunk, and TurnCompleted when the stream ends. Returns
     * after the connection closes. [onEvent] is called on the IO dispatcher.
     */
    suspend fun streamChat(
        model: String,
        history: List<Pair<String, String>>, // role → content ("user"/"assistant")
        turnId: String,
        onEvent: (ChatEvent) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val messages = JSONArray()
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", true)
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(body)
                .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()

            onEvent(ChatEvent.TurnStarted(turnId))
            val parser = OpenAiSse.newParser()
            var emitted = false
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    throw IllegalStateException("chat HTTP ${resp.code}: ${errBody.take(200)}")
                }
                val source = resp.body?.source() ?: throw IllegalStateException("empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    for (chunk in OpenAiSse.feed(parser, "$line\n")) {
                        chunk.content?.let {
                            emitted = true
                            onEvent(ChatEvent.TextDelta(turnId, it))
                        }
                        val inTok = chunk.inputTokens
                        val outTok = chunk.outputTokens
                        if (inTok != null || outTok != null) {
                            onEvent(
                                ChatEvent.UsageUpdate(
                                    inputTokens = inTok ?: 0,
                                    outputTokens = outTok ?: 0,
                                ),
                            )
                        }
                        if (chunk.finishReason != null) {
                            if (!emitted) onEvent(ChatEvent.TextDelta(turnId, ""))
                        }
                    }
                }
            }
            onEvent(
                ChatEvent.TurnCompleted(
                    turnId,
                    ChatEvent.TurnEndReason.STOP,
                ),
            )
        }
    }
}
