package com.harnesshub.app.chat

import android.app.Application
import com.harnesshub.app.data.HermesSettingsStore
import com.harnesshub.hermes.HermesAdapter
import com.harnesshub.hermes.HermesConnection
import com.harnesshub.model.InstanceId
import com.harnesshub.model.SessionId
import com.harnesshub.spi.ChatEvent
import com.harnesshub.spi.ChatReducer
import com.harnesshub.spi.HarnessCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * M4 chat engine: funnels one Hermes session's wire events through the shared
 * [ChatReducer] and exposes send/stop. Hermes gateway is the local dashboard
 * (verified M4); credentials live in DataStore — never logged.
 */
class ChatViewModel(application: Application) {

    private val settings = HermesSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reducer = ChatReducer()

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

    private var session: String? = null
    private var connection: HermesConnection? = null
    private var state = ChatReducer.State()

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
                session = conn.createSession()
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
            val conn = connection ?: return@launch
            _ui.value = _ui.value.copy(busy = true)
            try {
                val live = session ?: conn.createSession().also { session = it }
                conn.send(SessionId(live), text)
            } catch (e: Exception) {
                onEvent(ChatEvent.ErrorEvent("send", e.message ?: "send failed", retryable = true))
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun stop() {
        scope.launch {
            val live = session ?: return@launch
            connection?.cancel(SessionId(live))
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
