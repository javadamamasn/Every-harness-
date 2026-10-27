package com.harnesshub.hermes

import com.harnesshub.spi.ChatEvent
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

/**
 * Wire-level contract tests for the Hermes adapter: a scripted fake gateway
 * speaks the ND-JSON JSON-RPC shapes verified live in M4 and the adapter must
 * land on the unified ChatEvent stream.
 */
class HermesContractTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun up() {
        server = MockWebServer()
        client = OkHttpClient()
    }

    @AfterEach
    fun down() {
        runCatching { server.shutdown() } // MockWebServer shutdown noise tolerated (documented quirk)
    }

    /** Fake gateway: sends gateway.ready on open, then walks a scripted node. */
    private fun fakeGateway(script: (WebSocket) -> Unit): MockResponse {
        val response = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                script(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // generic RPC echo station: session.create and session.most_recent
                val id = Regex("\"id\":(\\d+)").find(text)?.groupValues?.get(1) ?: "1"
                when {
                    text.contains("session.create") -> webSocket.send(
                        """{"jsonrpc":"2.0","id":$id,"result":{"session_id":"sess42","stored_session_id":"20261027_x","info":{}}}""",
                    )
                    text.contains("prompt.submit") -> {
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":$id,"result":{"queued":true}}""",
                        )
                        // then the agent's streamed turn
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","payload":{"text":"go"}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"text":"Hel"}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"text":"lo"}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","payload":{"text":""}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.delta","payload":{"text":"mulling"}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","payload":{"toolId":"t1","name":"Read","argsText":"{}"}}}""")
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","payload":{"toolId":"t1","resultText":"ok","durationS":0.4}}}""")
                    }
                }
            }
        })
        return response
    }

    @Test
    fun `rpc correlation resolves result and rejects error frames`() = runTest {
        server.enqueue(fakeGateway { /* idle */ })
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(server.url("/api/ws?token=abc")).build(), listener)
        }
        gateway.connect(url().toString())
        assertTrue(gateway.awaitReady(10_000), "gateway.ready must arrive")
        val result = gateway.request("session.create", buildJsonObject { put("source", "chat") })
        assertEquals("sess42", (result["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `prompt submit streams a full mapped turn`() = runTest {
        server.enqueue(fakeGateway { /* idle */ })
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(server.url("/api/ws?token=abc")).build(), listener)
        }
        // subscribe BEFORE sending so no streamed event is lost (no replay in SharedFlow)
        val subscription = async {
            gateway.wireEvents.take(6).toList()
        }
        gateway.connect(url().toString())
        assertTrue(gateway.awaitReady(10_000))

        gateway.request("prompt.submit", buildJsonObject {
            put("text", "hello")
            put("session_id", "sess42")
        })

        val frames = withTimeout(20_000) { subscription.await() }
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

    private fun url() = server.url("/api/ws?token=abc").toString().replaceFirst("http", "ws")
}
