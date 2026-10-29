package com.harnesshub.app.chat

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.app.data.CompatSettingsStore
import com.harnesshub.app.data.HermesSettingsStore
import com.harnesshub.app.data.OpenCodeSettingsStore
import com.harnesshub.app.data.ProjectsStore
import com.harnesshub.opencode.OpenCodeAdapter
import com.harnesshub.opencode.OpenCodeClient
import com.harnesshub.claude.ClaudeAdapter
import com.harnesshub.codex.CodexAdapter
import com.harnesshub.hermes.HermesAdapter
import com.harnesshub.hermes.HermesConnection
import com.harnesshub.model.InstanceId
import com.harnesshub.model.SessionId
import com.harnesshub.spi.ChatEvent
import com.harnesshub.spi.ChatReducer
import com.harnesshub.spi.HarnessCredentials
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * M4 chat engine: funnels one Hermes session's wire events through the shared
 * [ChatReducer] and exposes send/stop. Hermes gateway is the local dashboard
 * (verified M4); credentials live in DataStore — never logged.
 */
/** Cross-screen handoff for "open this session" taps. */
object SessionOpen {
    var engine: ChatViewModel.Engine? = null
    var sessionId: String? = null

    fun consume(): Pair<ChatViewModel.Engine, String>? {
        val e = engine ?: return null
        engine = null
        return sessionId?.let { sid ->
            sessionId = null
            e to sid
        }
    }
}

class ChatViewModel(application: Application) {

    enum class Engine { HERMES, CLAUDE, OPENCODE, CODEX, OPENAI }

    private val settings = HermesSettingsStore(application)
    private val bridgeSettings = BridgeSettingsStore(application)
    private val opencodeSettings = OpenCodeSettingsStore(application)
    private val compatSettings = CompatSettingsStore(application)
    private val sessionStore = SessionStore(application)
    private val projectsStore = ProjectsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reducer = ChatReducer()

    /** Termux constants: both CLIs live under the npm global prefix. */
    private val claudeCli = "/data/data/com.termux/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"

    data class ChatUiState(
        val sessionId: String = "",
        val error: String? = null,
        val connectionLost: Boolean = false,
        val messages: List<com.harnesshub.model.Message> = emptyList(),
        val usageIn: Long = 0,
        val usageOut: Long = 0,
        val busy: Boolean = false,
    )

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui

    private val _engine = MutableStateFlow(Engine.HERMES)
    val engine: StateFlow<Engine> = _engine

    private var session: String? = null
    private var lastTitle: String = ""
    private var connection: HermesConnection? = null
    private var claudeSession: String? = null
    private var opencodeSession: String? = null
    private var opencodeAdapter: OpenCodeAdapter? = null
    private var codexThread: String? = null
    private var state = ChatReducer.State()

    fun useEngine(engine: Engine) { _engine.value = engine }



    fun start() {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) {
                _ui.value = _ui.value.copy(error = "hermes_not_configured")
                return@launch
            }
            connection?.let { return@launch } // already live
            try {
                val adapter = HermesAdapter(prefs.port)
                val handle = adapter.connect(InstanceId("hermes"), HarnessCredentials.BearerToken(prefs.token))
                connection = handle as? HermesConnection
                val conn = connection
                if (conn == null) {
                    _ui.value = _ui.value.copy(error = "bad connection handle")
                    return@launch
                }
                scope.launch {
                    conn.events.collect { event -> onEvent(event) }
                }
                val pending = SessionOpen.consume()
                if (pending?.first == Engine.HERMES) {
                    session = pending.second
                } else {
                    session = conn.createSession()
                    sessionStore.record(
                        SessionStore.ENGINE_HERMES,
                        session.orEmpty(),
                        lastTitle.ifBlank { "Hermes chat" },
                    )
                }
                _ui.value = _ui.value.copy(error = null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message ?: "connect failed")
            }
        }
    }

    private fun onEvent(event: ChatEvent) {
        val next = reducer.reduceOne(state, event)
        state = next
        _ui.value = _ui.value.copy(
            messages = next.messages,
            usageIn = next.usageInputTokens,
            usageOut = next.usageOutputTokens,
            connectionLost = !next.connected,
            sessionId = next.sessionId.value.ifBlank { session.orEmpty() },
            error = when (val e = event) {
                is ChatEvent.ErrorEvent -> if (e.kind == "session") e.message else _ui.value.error
                else -> _ui.value.error
            },
        )
    }

    fun send(text: String) {
        if (text.isBlank()) return
        scope.launch {
            _ui.value = _ui.value.copy(busy = true)
            lastTitle = text.take(60)
            try {
                when (_engine.value) {
                    Engine.HERMES -> {
                        val conn = connection ?: return@launch
                        val live = session ?: conn.createSession().also { session = it }
                        conn.send(SessionId(live), text)
                    }
                    Engine.CLAUDE -> claudeTurn(text)
                    Engine.OPENCODE -> opencodeTurn(text)
                    Engine.CODEX -> codexTurn(text)
                    Engine.OPENAI -> openaiTurn(text)
                }
            } catch (e: Exception) {
                onEvent(ChatEvent.ErrorEvent("send", e.message ?: "send failed", retryable = true))
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    /** One-shot Claude Code turn over the bridge (exec.run; streaming at M7). */
    private suspend fun claudeTurn(text: String) {
        if (claudeSession == null) {
            val pending = SessionOpen.consume()
            if (pending?.first == Engine.CLAUDE) claudeSession = pending.second
        }
        val prefs = bridgeSettings.settings().first()
        if (!prefs.configured) {
            onEvent(ChatEvent.ErrorEvent("exec", "bridge_not_configured", retryable = true))
            return
        }
        val adapter = ClaudeAdapter(claudeCli)
        val transport = BridgeTransport(clientFactory = { listener ->
            OkHttpClient().newWebSocket(Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(), listener)
        })
        try {
            transport.connect(prefs.token)
            if (transport.awaitReady(8_000) != BridgeTransport.State.Ready(1, null, null)) {
                onEvent(ChatEvent.ErrorEvent("exec", "bridge not ready", retryable = true))
                return
            }
            val executor = ClaudeAdapter.BridgeExecutor { command, timeoutMs ->
                val result = transport.request(
                    "exec.run",
                    BridgeJson.obj(
                        "command" to BridgeJson.arrOfStrings(command),
                        "timeoutMs" to timeoutMs,
                        "cwd" to projectsStore.activePathData(),
                    ),
                )
                ClaudeAdapter.ExecOutcome(
                    exitCode = BridgeJson.int(result, "exitCode") ?: 1,
                    stdout = BridgeJson.string(result, "stdout") ?: "",
                    stderr = BridgeJson.string(result, "stderr") ?: "",
                )
            }
            val outcome = adapter.turn(executor, text, claudeSession, timeoutMs = 55_000)
            claudeSession = outcome.sessionId ?: claudeSession
            claudeSession?.let { sessionStore.record(SessionStore.ENGINE_CLAUDE, it, lastTitle) }
            outcome.events.forEach { onEvent(it) }
        } finally {
            transport.close()
        }
    }

    fun stop() {
        scope.launch {
            val live = session ?: return@launch
            connection?.cancel(SessionId(live))
        }
    }

    /** Direct Tier-1 connection to `opencode serve` (HTTP + SSE, no bridge). */
    suspend fun connectOpenCode(): Boolean {
        val prefs = opencodeSettings.settings().first()
        if (!prefs.configured) return false
        if (opencodeAdapter != null) return true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = OpenCodeClient(port = prefs.port, password = prefs.password.ifBlank { null })
        val adapter = OpenCodeAdapter(client, scope)
        val handle = adapter.connect(
            com.harnesshub.model.InstanceId("opencode-direct"),
            HarnessCredentials.BearerToken(prefs.password),
        )
        val conn = handle as? OpenCodeAdapter.OpenCodeConnectionHandle
            ?: run { adapter.dispose(); return false }
        opencodeAdapter = adapter
        scope.launch {
            conn.events.collect { event -> onEvent(event) }
        }
        _ui.value = _ui.value.copy(error = null)
        return true
    }

    fun saveOpenCode(port: Int, password: String) {
        scope.launch {
            opencodeSettings.save(port, password)
            opencodeAdapter?.dispose()
            opencodeAdapter = null
            opencodeSession = null
            if (!connectOpenCode()) {
                _ui.value = _ui.value.copy(error = "opencode_unreachable")
            }
        }
    }

    fun opencodeConfigured(onDone: (Boolean) -> Unit) {
        scope.launch {
            val ready = connectOpenCode()
            if (!ready) opencodeSession = null
            onDone(ready)
        }
    }

    private suspend fun opencodeTurn(text: String) {
        val prefs = opencodeSettings.settings().first()
        if (!connectOpenCode()) {
            _ui.value = _ui.value.copy(error = "opencode_not_configured")
            return
        }
        val adapter = opencodeAdapter
        if (adapter == null) {
            _ui.value = _ui.value.copy(error = "opencode_not_connected")
            return
        }
        val pending = SessionOpen.consume()
        opencodeSession = when {
            opencodeSession != null -> opencodeSession
            pending?.first == Engine.OPENCODE -> pending.second
            else -> adapter.createSession(
                model = prefs.preferredModel,
            )
        }
        sessionStore.record(SessionStore.ENGINE_OPENCODE, opencodeSession!!, text.take(60))
        adapter.send(opencodeSession!!, text)
    }

    /** Codex CLI one-shot turn — argv documented, CLI not installed here yet. */
    private suspend fun codexTurn(text: String) {
        val prefs = bridgeSettings.settings().first()
        if (!prefs.configured) {
            onEvent(ChatEvent.ErrorEvent("exec", "bridge_not_configured", retryable = true))
            return
        }
        val adapter = CodexAdapter(codexBin = "codex")
        val transport = BridgeTransport(clientFactory = { listener ->
            OkHttpClient().newWebSocket(Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(), listener)
        })
        try {
            transport.connect(prefs.token)
            if (transport.awaitReady(8_000) != BridgeTransport.State.Ready(1, null, null)) {
                onEvent(ChatEvent.ErrorEvent("exec", "bridge not ready", retryable = true))
                return
            }
            val executor = CodexAdapter.BridgeExecutor { command, timeoutMs ->
                val result = transport.request(
                    "exec.run",
                    BridgeJson.obj(
                        "command" to BridgeJson.arrOfStrings(command),
                        "timeoutMs" to timeoutMs,
                        "cwd" to projectsStore.activePathData(),
                    ),
                )
                val stderr = BridgeJson.string(result, "stderr") ?: ""
                CodexAdapter.ExecOutcome(
                    exitCode = BridgeJson.int(result, "exitCode") ?: 1,
                    stdout = BridgeJson.string(result, "stdout") ?: "",
                    stderr = stderr,
                )
            }
            val outcome = adapter.turn(executor, text, codexThread, timeoutMs = 55_000)
            if (outcome.events.isEmpty()) {
                onEvent(ChatEvent.ErrorEvent("provider", "codex unavailable (CLI missing or failed to start)", retryable = false))
                return
            }
            codexThread = outcome.threadId ?: codexThread
            outcome.events.forEach { onEvent(it) }
        } finally {
            transport.close()
        }
    }

    /** M8 adapter turn: streamed /v1/chat/completions against a configured
     * OpenAI-compatible endpoint. The HTTP transport has no session object,
     * so this engine maintains its own alternating user/assistant history
     * (the shared reducer intentionally renders only assistant turns).
     * Errors surface as ChatEvent. */
    private val compatHistory = mutableListOf<Pair<String, String>>()

    private suspend fun openaiTurn(text: String) {
        val prefs = compatSettings.settings().first()
        if (!prefs.configured) {
            onEvent(ChatEvent.ErrorEvent("provider", "compat_not_configured", retryable = false))
            return
        }
        val turnId = "oai-${System.currentTimeMillis()}"
        compatHistory.add("user" to text)
        val client = OpenAiCompatClient(prefs.baseUrl, prefs.apiKey.takeIf { it.isNotBlank() })
        var assistantText = ""
        val result = client.streamChat(
            model = prefs.model,
            history = compatHistory.toList(),
            turnId = turnId,
        ) { event ->
            if (event is ChatEvent.TextDelta && event.turnId == turnId) assistantText += event.text
            onEvent(event)
        }
        if (result.isSuccess) {
            if (assistantText.isNotBlank()) compatHistory.add("assistant" to assistantText)
        } else {
            compatHistory.removeAt(compatHistory.lastIndex) // failed user turn is not history
            onEvent(
                ChatEvent.ErrorEvent(
                    "provider",
                    result.exceptionOrNull()?.message ?: "compat request failed",
                    retryable = true,
                ),
            )
        }
    }

    fun saveAndReconnect(port: Int, token: String) {
        scope.launch {
            settings.save(port, token)
            connection?.close()
            connection = null
            session = null
            state = ChatReducer.State()
            _ui.value = ChatUiState()
            start()
        }
    }

    fun dispose() {
        val conn = connection
        connection = null
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { conn?.close() }
    }
}
