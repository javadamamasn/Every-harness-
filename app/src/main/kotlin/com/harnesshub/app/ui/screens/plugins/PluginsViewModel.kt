package com.harnesshub.app.ui.screens.plugins

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

import com.harnesshub.transport.PluginJson

/**
 * Plugins (section 13 row): catalogue over Claude Code's
 * installed_plugins.json and known_marketplaces.json (verified on-device
 * file shapes, 2026-10-29) plus enable/disable toggles in settings.json
 * enabledPlugins through pure PluginJson transforms. Install/uninstall land
 * later; nothing else is mutated here.
 */
class PluginsViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class PluginRow(
        val name: String,
        val marketplace: String,
        val version: String,
        val pluginId: String,
        val enabled: Boolean,
    )

    data class MarketplaceRow(
        val name: String,
        val repo: String,
    )

    data class UiState(
        val plugins: List<PluginRow> = emptyList(),
        val marketplaces: List<MarketplaceRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun setEnabled(pluginId: String, on: Boolean) {
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
                val next = PluginJson.setPluginEnabled(original, pluginId, on)
                bridgeRequest("fs.write", BridgeJson.obj("path" to settingsPath(), "content" to next))
            }.onSuccess {
                _state.value = _state.value.copy(error = null)
                refresh()
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "plugin toggle failed")
            }
        }
    }

    private suspend fun settingsPath(): String =
        homePath()?.plus("/.claude/settings.json") ?: throw IllegalStateException("no bridge home")

    private suspend fun homePath(): String =
        BridgeJson.string(bridgeRequest("system.info"), "home")
            ?: throw IllegalStateException("no bridge home")

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
                val installed = transport.request(
                    "fs.read",
                    BridgeJson.obj("path" to "$home/.claude/plugins/installed_plugins.json"),
                )
                val market = transport.request(
                    "fs.read",
                    BridgeJson.obj("path" to "$home/.claude/plugins/known_marketplaces.json"),
                )
                val cfgRes = transport.request(
                    "fs.read",
                    BridgeJson.obj("path" to "$home/.claude/settings.json", "length" to 2_000_000),
                )
                val cfgText = BridgeJson.string(cfgRes, "content") ?: ""
                val pluginRows = mutableListOf<PluginRow>()
                val pluginsObj = (installed as? kotlinx.serialization.json.JsonObject)
                    ?.get("plugins") as? kotlinx.serialization.json.JsonObject
                    ?: kotlinx.serialization.json.JsonObject(emptyMap())
                for ((key, value) in pluginsObj) {
                    val entry = when (val v = value) {
                        is kotlinx.serialization.json.JsonObject -> v
                        is kotlinx.serialization.json.JsonArray ->
                            v.firstOrNull() as? kotlinx.serialization.json.JsonObject
                        else -> null
                    } ?: continue
                    pluginRows.add(
                        PluginRow(
                            name = key.substringBefore("@"),
                            marketplace = key.substringAfter("@", ""),
                            version = BridgeJson.string(entry, "version") ?: "",
                            pluginId = key,
                            enabled = PluginJson.pluginEnabled(cfgText, key) ?: true,
                        ),
                    )
                }
                val marketRows = (market as? kotlinx.serialization.json.JsonObject)
                    ?.entries.orEmpty()
                    .filter { it.value is kotlinx.serialization.json.JsonObject }
                    .map { (name, value) ->
                        MarketplaceRow(
                            name = name,
                            repo = (value as? kotlinx.serialization.json.JsonObject)
                                ?.get("source")?.let { s ->
                                    (s as? kotlinx.serialization.json.JsonObject)
                                        ?.get("repo") as? JsonPrimitive
                                }?.content ?: "",
                        )
                    }
                _state.value = UiState(
                    plugins = pluginRows,
                    marketplaces = marketRows,
                    loaded = true,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "plugins fetch failed")
            } finally {
                transport.close()
            }
        }
    }

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
