package com.harnesshub.hermes

import com.harnesshub.spi.ChatEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wire-level contract tests for the Hermes adapter: a scripted fake gateway
 * speaks the ND-JSON JSON-RPC shapes verified live in M4 and the adapter must
 * land on the unified ChatEvent stream. Ledgers expose which side stalled
 * (never invent success).
 */
class HermesContractTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private class FakeState {
        val received = CopyOnWriteArrayList<String>()
        val sent = CopyOnWriteArrayList<String>()
    }

    private val lastFake = FakeState()

    @BeforeEach
    fun up() {
        server = MockWebServer()
        client = OkHttpClient()
        lastFake.received.clear()
        lastFake.sent.clear()
    }

    @AfterEach
    fun down() {
        runCatching { server.shutdown() } // MockWebServer shutdown noise tolerated (documented quirk)
    }

    /** Fake gateway: gateway.ready on open; echoes session.create; streams a turn after prompt.submit. */
    private fun fakeGateway(streamAfterSubmit: Boolean): MockResponse {
        val response = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"gateway.ready\",\"payload\":{}}}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFake.received.add(text)
                val id = Regex("\"id\":(\\d+)").find(text)?.groupValues?.get(1) ?: "1"
                when {
                    text.contains("session.create") -> send(
                        webSocket,
                        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"session_id\":\"sess42\",\"stored_session_id\":\"20261027_x\",\"info\":{}}}",
                    )
                    text.contains("prompt.submit") -> {
                        send(webSocket, "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"queued\":true}}")
                        if (streamAfterSubmit) {
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.start\",\"payload\":{\"text\":\"go\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"payload\":{\"text\":\"Hel\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"payload\":{\"text\":\"lo\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.complete\",\"payload\":{\"text\":\"\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"reasoning.delta\",\"payload\":{\"text\":\"mulling\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"tool.start\",\"payload\":{\"toolId\":\"t1\",\"name\":\"Read\",\"argsText\":\"{}\"}}}")
                            send(webSocket, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"tool.complete\",\"payload\":{\"toolId\":\"t1\",\"resultText\":\"ok\",\"durationS\":0.4}}}")
                        }
                    }
                    else -> send(
                        webSocket,
                        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-32601,\"message\":\"method not found\"}}",
                    )
                }
            }

            private fun send(ws: WebSocket, s: String) {
                lastFake.sent.add(s)
                ws.send(s)
            }
        })
        return response
    }

    private fun url(): String = server.url("/api/ws?token=abc").toString().replaceFirst("http", "ws")

    @Test
    fun `rpc correlation resolves result`() = runTest {
        server.enqueue(fakeGateway(streamAfterSubmit = false))
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(url()).build(), listener)
        }
        gateway.connect(url())
        assertTrue(gateway.awaitReady(10_000), "gateway.ready must arrive")
        val result = try {
            withTimeout(5_000) { gateway.request("session.create", buildJsonObject { put("source", "chat") }) }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "request() stalled. fake received=${lastFake.received.joinToString("|")} fake sent=${lastFake.sent.joinToString("|")} clientState=${gateway.state.value} rawInbound=${gateway.rawInboundLines().joinToString("|")}",
            )
        }
        assertEquals("sess42", (result["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `prompt submit streams a full mapped turn`() = runTest {
        server.enqueue(fakeGateway(streamAfterSubmit = true))
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(url()).build(), listener)
        }
        // subscribe BEFORE sending so no streamed event is lost (no replay in SharedFlow)
        val subscription = async { gateway.wireEvents.take(6).toList() }
        gateway.connect(url())
        assertTrue(gateway.awaitReady(10_000))

        try {
            withTimeout(5_000) {
                gateway.request("prompt.submit", buildJsonObject {
                    put("text", "hello")
                    put("session_id", "sess42")
                })
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(
                "prompt.submit stalled. fake received=${lastFake.received.joinToString("|")} fake sent=${lastFake.sent.joinToString("|")} clientState=${gateway.state.value} rawInbound=${gateway.rawInboundLines().joinToString("|")}",
            )
        }

        val frames = withTimeout(10_000) { subscription.await() }
        val mapper = HermesEventMapper({ "sess42" })
        val events = frames.map { mapper.map(it) }
        val types = frames.map { it.type }
        assertEquals(
            listOf("message.start", "message.delta", "message.delta", "message.complete", "reasoning.delta", "tool.start"),
            types,
        )
        assertEquals("Hel", (events[1] as? ChatEvent.TextDelta)?.text)
        assertEquals("mulling", (events[4] as? ChatEvent.ThinkingDelta)?.text)
        val tool = events[5] as? ChatEvent.ToolCallStarted
        assertEquals("Read", tool?.name)
    }
}
