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

/**
 * Plugins (section 13 row): read-only catalogue over Claude Code's
 * installed_plugins.json and known_marketplaces.json (verified on-device
 * file shapes, 2026-10-29). Install/uninstall and enable/disable land later;
 * nothing is mutated here.
 */
class PluginsViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class PluginRow(
        val name: String,
        val marketplace: String,
        val version: String,
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
}
