package com.harnesshub.transport

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
 * Contract test: the same client code runs against a wire-level fake bridge
 * (the node bridge suite exercises the daemon itself in CI).
 */
class BridgeTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun up() {
        server = MockWebServer()
        client = OkHttpClient()
    }

    @AfterEach
    fun down() {
        server.shutdown()
    }

    private fun fakeBridge(): MockResponse {
        val serverJson = Json { ignoreUnknownKeys = true }
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val env = serverJson.decodeFromString(Envelope.serializer(), text)
                when (env.type) {
                    "hello" -> ws.send(
                        serverJson.encodeToString(
                            Envelope.serializer(),
                            Envelope(id = "hello", type = "res", result = BridgeJson.obj("protocol" to 1, "bridgeVersion" to "test")),
                        ),
                    )
                    "req" -> if (env.method == "system.info") {
                        ws.send(
                            serverJson.encodeToString(
                                Envelope.serializer(),
                                Envelope(id = env.id, type = "res", method = "system.info", result = BridgeJson.obj("node" to "v0", "androidVersion" to "14")),
                            ),
                        )
                    } else {
                        ws.send(
                            serverJson.encodeToString(
                                Envelope.serializer(),
                                Envelope(id = env.id, type = "err", error = BridgeError("NOT_FOUND", "unknown method")),
                            ),
                        )
                    }
                }
            }
        })
    }

    @Test
    fun `authenticates, requests and surfaces wire errors`() = runBlocking {
        server.enqueue(fakeBridge())
        server.start()
        val transport = BridgeTransport(
            clientFactory = { listener ->
                val url = server.url("/").toString().replaceFirst("http", "ws")
                client.newWebSocket(Request.Builder().url(url).build(), listener)
            },
            json = json,
        )
        transport.connect(token = "s3kr3t")
        val ready = withTimeout(5000) { transport.awaitReady() }
        assertTrue(ready is BridgeTransport.State.Ready, "ready state, got $ready")

        val info = transport.request("system.info")
        assertEquals("v0", BridgeJson.string(info, "node"))
        assertEquals("14", BridgeJson.string(info, "androidVersion"))

        val err = runCatching { transport.request("made.up") }
        assertTrue(err.exceptionOrNull() is BridgeException && (err.exceptionOrNull() as BridgeException).error.code == "NOT_FOUND")

        transport.close()
    }

    @Test
    fun `discovery scan decodes into typed rows`() = runBlocking {
        val serverJson = Json { ignoreUnknownKeys = true }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    val env = serverJson.decodeFromString(Envelope.serializer(), text)
                    if (env.type == "hello") {
                        ws.send(serverJson.encodeToString(Envelope.serializer(), Envelope(id = "hello", type = "res", result = BridgeJson.obj("protocol" to 1))))
                    }
                    if (env.method == "discovery.scan") {
                        val payload = """{"results":[{"harnessId":"hermes","signals":["binary:hermes","path:/x"],"confidence":1.0}]}"""
                        ws.send(
                            serverJson.encodeToString(
                                Envelope.serializer(),
                                Envelope(id = env.id, type = "res", method = "discovery.scan", result = serverJson.parseToJsonElement(payload)),
                            ),
                        )
                    }
                }
            }),
        )
        server.start()
        val transport = BridgeTransport(
            clientFactory = { listener ->
                client.newWebSocket(Request.Builder().url(server.url("/").toString().replaceFirst("http", "ws")).build(), listener)
            },
            json = json,
        )
        transport.connect(token = "t")
        withTimeout(5000) { transport.awaitReady() }
        val rows = transport.discoveryScan()
        assertEquals(1, rows.size)
        assertEquals("hermes", rows[0].harnessId)
        transport.close()
    }
}
