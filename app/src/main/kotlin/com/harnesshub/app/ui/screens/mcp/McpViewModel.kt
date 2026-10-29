package com.harnesshub.app.ui.screens.mcp

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeTransport
import com.harnesshub.transport.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MCP servers (section 13 row, Claude Code source): edits mcpServers in
 * ~/.claude.json through the bridge fs.read/fs.write. Pure JSON transforms in
 * core:transport McpJson; malformed stores are refused, fs.write keeps a
 * timestamped backup. stdio servers only (url types survive untouched).
 */
class McpViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class McpRow(
        val name: String,
        val summary: String,
    )

    data class UiState(
        val rows: List<McpRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun add(name: String, command: String, argsText: String) {
        scope.launch {
            mutate { text ->
                val args = argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                McpJson.addServer(text, name.trim(), command.trim(), args)
            }
        }
    }

    fun remove(name: String) {
        scope.launch {
            mutate { text -> McpJson.removeServer(text, name) }
        }
    }

    private suspend fun mutate(transform: (String) -> String) {
        runCatching {
            val home = homePath()
            val path = "$home/.claude.json"
            val read = bridgeRequest(
                "fs.read",
                BridgeJson.obj("path" to path, "length" to 2_000_000),
            )
            val original = BridgeJson.string(read, "content") ?: ""
            val size = BridgeJson.int(read, "size") ?: original.length
            if (original.length < size) throw IllegalStateException("config larger than transport window — refusing to rewrite")
            val next = transform(original)
            bridgeRequest("fs.write", BridgeJson.obj("path" to path, "content" to next))
        }.onSuccess {
            _state.value = _state.value.copy(error = null)
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "mcp edit failed")
        }
    }

    fun refresh() {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) {
                _state.value = UiState(loaded = true, error = "bridge_not_configured")
                return@launch
            }
            val transport = BridgeTransport(
                clientFactory = { listener ->
                    OkHttpClient().newWebSocket(
                        Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(),
                        listener,
                    )
                },
            )
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) {
                    _state.value = UiState(loaded = true, error = "bridge not ready")
                    return@launch
                }
                val home = BridgeJson.string(
                    transport.request("system.info"),
                    "home",
                ) ?: throw IllegalStateException("no bridge home")
                val res = transport.request(
                    "fs.read",
                    BridgeJson.obj("path" to "$home/.claude.json", "length" to 2_000_000),
                )
                val text = BridgeJson.string(res, "content") ?: ""
                val rows = McpJson.summaries(text).map { (name, summary) -> McpRow(name, summary) }
                _state.value = UiState(rows = rows, loaded = true, error = null)
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "mcp fetch failed")
            } finally {
                transport.close()
            }
        }
    }

    private suspend fun homePath(): String =
        BridgeJson.string(bridgeRequest("system.info"), "home") ?: throw IllegalStateException("no bridge home")

    private suspend fun bridgeRequest(
        method: String,
        params: kotlinx.serialization.json.JsonElement? = null,
    ): kotlinx.serialization.json.JsonElement {
        val prefs = settings.settings().first()
        if (!prefs.configured) throw IllegalStateException("bridge_not_configured")
        val transport = BridgeTransport(
            clientFactory = { listener ->
                OkHttpClient().newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(),
                    listener,
                )
            },
        )
        try {
            transport.connect(prefs.token)
            if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) {
                throw IllegalStateException("bridge not ready")
            }
            return transport.request(method, params)
        } finally {
            transport.close()
        }
    }
}
