package com.harnesshub.app.ui.screens.tools

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeTransport
import com.harnesshub.transport.PluginJson
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
 * Tools & permissions (section 13 row): the bridge exec.run flag via
 * `bridge.execFlag` plus Claude Code permission rules (allow/deny + shown
 * defaultMode) in ~/.claude/settings.json through pure PluginJson transforms.
 * Mode switching UI and live per-session toggles land with the approvals
 * engine (honest partial).
 */
class ToolsViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class UiState(
        val execFlag: Boolean? = null,
        val defaultMode: String? = null,
        val allowRules: List<String> = emptyList(),
        val denyRules: List<String> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun toggleExec(on: Boolean) {
        scope.launch {
            runCatching { bridgeRequest("bridge.execFlag", BridgeJson.obj("on" to on)) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun addRule(list: String, rule: String) = mutateRules(list) { text ->
        PluginJson.addRule(text, list, rule)
    }

    fun removeRule(list: String, rule: String) = mutateRules(list) { text ->
        PluginJson.removeRule(text, list, rule)
    }

    private fun mutateRules(list: String, transform: (String) -> String) {
        scope.launch {
            runCatching {
                val read = bridgeRequest(
                    "fs.read",
                    BridgeJson.obj("path" to settingsPath(), "length" to 2_000_000),
                )
                val original = BridgeJson.string(read, "content") ?: ""
                val size = BridgeJson.int(read, "size") ?: original.length
                if (original.length < size) {
                    throw IllegalStateException("settings larger than window — refusing rewrite")
                }
                val next = transform(original)
                bridgeRequest("fs.write", BridgeJson.obj("path" to settingsPath(), "content" to next))
            }.onSuccess {
                _state.value = _state.value.copy(error = null)
                refresh()
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "permission edit failed")
            }
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
                val info = transport.request("system.info")
                val home = BridgeJson.string(info, "home") ?: throw IllegalStateException("no bridge home")
                val execFlag = BridgeJson.bool(info, "execFlag")
                val read = transport.request(
                    "fs.read",
                    BridgeJson.obj("path" to "$home/.claude/settings.json", "length" to 2_000_000),
                )
                val text = BridgeJson.string(read, "content") ?: ""
                _state.value = UiState(
                    execFlag = execFlag,
                    defaultMode = PluginJson.defaultMode(text),
                    allowRules = PluginJson.rules(text, "allow"),
                    denyRules = PluginJson.rules(text, "deny"),
                    loaded = true,
                    error = if (text.isBlank()) "settings.json unreadable" else null,
                )
            } catch (e: Exception) {
                // Missing settings.json should not hide the exec flag state
                runCatching {
                    val info = bridgeRequest("system.info")
                    _state.value = UiState(
                        execFlag = BridgeJson.bool(info, "execFlag"),
                        loaded = true,
                        error = e.message,
                    )
                }.onFailure {
                    _state.value = UiState(loaded = true, error = e.message ?: "tools fetch failed")
                }
            } finally {
                transport.close()
            }
        }
    }

    private suspend fun settingsPath(): String =
        BridgeJson.string(bridgeRequest("system.info"), "home")
            ?.plus("/.claude/settings.json")
            ?: throw IllegalStateException("no bridge home")

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
