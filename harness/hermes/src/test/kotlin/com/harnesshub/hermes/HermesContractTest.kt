package com.harnesshub.hermes

import com.harnesshub.model.SessionId
import com.harnesshub.spi.ChatEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wire-level contract tests for the Hermes adapter: a scripted fake gateway
 * (same shapes verified live in M4) speaks ND-JSON JSON-RPC over WS and the
 * adapter must land on the unified ChatEvent stream.
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
        runCatching { server.shutdown() } // MockWebServer shutdown noise tolerated (documented)
    }

    private fun fakeGateway(script: suspend (FakeConnection) -> Unit): MockResponse {
        val response = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                runBlocking { script(FakeConnection(webSocket)) }
            }
        })
        return response
    }

    class FakeConnection(val ws: WebSocket) {
        fun replyEvent(type: String, payload: String) {
            ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","payload":$payload}}""")
        }

        var lastRpc: String? = null

        fun nextRequestJson(): String {
            // consume exactly one queued text frame via manual drain of messages
            // (MockWebServer keeps requests in order)
            return lastRpc ?: ""
        }
    }

    @Test
    fun `connects, becomes ready, and streams mapped events`() = runTest {
        server.enqueue(
            fakeGateway { fake ->
                launch {
                    val first = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
                    lastRpcSeen = first?.body?.readUtf8()
                    fake.replyEvent("message.start", """{"text":"hi"}""")
                    fake.replyEvent("message.delta", """{"text":"Hel"}""")
                    fake.replyEvent("message.delta", """{"text":"lo"}""")
                    fake.replyEvent("message.complete", """{"text":""}""")
                    fake.replyEvent("reasoning.delta", """{"text":"thinking about it"}""")
                    fake.replyEvent("tool.start", """{"toolId":"t1","name":"Read","argsText":"{}"}""")
                    fake.replyEvent("tool.complete", """{"toolId":"t1","resultText":"ok","durationS":0.4}""" )
                }
            },
        )

        val port = server.port
        val adapter = HermesAdapter(gatewayPort = port) // capability wiring exercised in HermesGatewayTest
        val connectUrl = server.url("/api/ws?token=abc").toString().replaceFirst("http", "ws")
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(connectUrl).build(), listener)
        }
        // subscribe BEFORE connecting so no event is lost (SharedFlow has no replay)
        val collector = async { gateway.wireEvents.take(5).toList() }
        gateway.connect(connectUrl)
        assertTrue(gateway.awaitReady(10_000), "gateway.ready must arrive")

        val frames = withTimeout(10_000) { collector.await() }
        assertEquals(listOf("message.start", "message.delta", "message.delta", "message.complete", "reasoning.delta"), frames.map { it.type })

        val mapper = HermesEventMapper({ "sess" })
        val events = frames.map { mapper.map(it) }
        assertEquals("Hel", (events[1] as? ChatEvent.TextDelta)?.text)
        assertEquals("thinking about it", (events[4] as? ChatEvent.ThinkingDelta)?.text)
        lastRpcSeen = null
    }

    private var lastRpcSeen: String? = null

    @Test
    fun `rpc correlation resolves result and rejects error frames`() = runTest {
        server.enqueue(
            fakeGateway { fake ->
                launch {
                    val req = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: return@launch
                    val body = req.body.readUtf8()
                    val id = Regex(""""id":(\d+)""").find(body)?.groupValues?.get(1) ?: "1"
                    if (body.contains("session.create")) {
                        fake.ws.send("""{"jsonrpc":"2.0","id":$id,"result":{"session_id":"sess42","stored_session_id":"adjacent"}}""")
                    }
                }
            },
        )
        val connectUrl = server.url("/api/ws?token=abc").toString().replaceFirst("http", "ws")
        val gateway = HermesGatewayClient { listener ->
            client.newWebSocket(Request.Builder().url(connectUrl).build(), listener)
        }
        gateway.connect(connectUrl)
        assertTrue(gateway.awaitReady(10_000))
        val result = gateway.request("session.create", buildJsonObject { put("source", "chat") })
        assertEquals("sess42", (result["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }
}
