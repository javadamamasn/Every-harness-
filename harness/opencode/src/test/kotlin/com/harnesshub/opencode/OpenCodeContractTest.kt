package com.harnesshub.opencode

import com.harnesshub.spi.ChatEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contract tests against a fake OpenCode server (same shapes as the committed
 * OpenAPI snapshot). Wire-level uses runBlocking — real sockets, real IO.
 */
class OpenCodeContractTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/global/health") -> ok("""{"healthy":true,"version":"1.19.3"}""")
                    path == "/session" && request.method == "POST" ->
                        ok(
                            """{"id":"ses_contract01","slug":"eager","projectID":"p","directory":"/tmp",""" +
                                """"path":"","cost":0.5,"tokens":{"input":10,"output":20,"reasoning":0,""" +
                                """"cache":{"read":0,"write":0}},"title":"t","version":"1.19.3","time":{}}""",
                        )
                    path == "/session" -> ok("""[]""")
                    path.endsWith("/prompt") ->
                        ok(
                            """{"data":{"admittedSeq":1,"id":"msg_1","sessionID":"ses_contract01",""" +
                                """"prompt":{"text":"hi"},"delivery":"steer","timeCreated":1}}""",
                        )
                    path.endsWith("/abort") -> ok("true")
                    path == "/event" -> MockResponse()
                        .setBody("event: x\n" + "data: {\"id\":\"evt_1\",\"type\":\"server.connected\",\"properties\":{}}\n\n" +
                            "data: {\"id\":\"evt_2\",\"type\":\"message.part.updated\",\"properties\":{\"" +
                            "sessionID\":\"ses_contract01\",\"part\":{\"id\":\"prt_1\",\"sessionID\":\"s1\"," +
                            "\"messageID\":\"msg_1\",\"type\":\"text\",\"text\":\"Hel\",\"time\":{\"start\":1}},\"time\":2}}\n\n" +
                            "data: {\"id\":\"evt_3\",\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"s1\"," +
                            "\"part\":{\"id\":\"prt_1\",\"sessionID\":\"s1\",\"messageID\":\"msg_1\",\"type\":\"text\"," +
                            "\"text\":\"Hello\",\"time\":{\"start\":1}},\"time\":3}}\n\n")
                        .setHeader("Content-Type", "text/event-stream")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = OpenCodeClient(host = "127.0.0.1", port = server.port, password = "secret")
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun ok(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `health returns version`() = runBlocking {
        val health = client.health()
        assertTrue(health.healthy)
        assertEquals("1.19.3", health.version)
    }

    @Test
    fun `request carries basic auth when password set`() = runBlocking {
        client.health()
        val auth = server.takeRequest().getHeader("Authorization")
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", auth) // opencode:secret
    }

    @Test
    fun `create session parses tokens`() = runBlocking {
        val session = client.createSession("t")
        assertEquals("ses_contract01", session.id)
        assertEquals(10L, session.inputTokens)
        assertEquals(20L, session.outputTokens)
        assertEquals(0.5, session.cost)
    }

    @Test
    fun `prompt posts to api path`() = runBlocking {
        assertTrue(client.prompt("ses_contract01", "hi"))
        val recorded = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request recorded")
        assertTrue(recorded.path!!.endsWith("/api/session/ses_contract01/prompt"))
        assertTrue(recorded.body.readUtf8().contains("\"delivery\":\"steer\""))
    }

    @Test
    fun `sse stream maps text parts to cumulative deltas`() {
        val events = mutableListOf<ChatEvent>()
        val handle = OpenCodeAdapter.OpenCodeConnectionHandle(com.harnesshub.model.InstanceId("opencode"))
        val mapper = OpenCodeEventMapper()
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                client.events().take(3).collect { line ->
                    mapper.mapLine(line).forEach { evt -> (events as java.util.ArrayList<ChatEvent>).add(evt) }
                }
            }
        }
        assertTrue(events[0] is ChatEvent.ConnectionResumed)
        val deltas = events.filterIsInstance<ChatEvent.TextDelta>()
        assertEquals(listOf("Hel", "Hello"), deltas.map { it.text }) // diffed, not duplicated
    }
}
