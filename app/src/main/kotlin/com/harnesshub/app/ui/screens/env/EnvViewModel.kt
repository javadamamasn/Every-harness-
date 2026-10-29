package com.harnesshub.app.ui.screens.env

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Environment variables (section 13 row) over the bridge `env.list/set/
 * delete` (structured ~/.hermes/.env). Values arrive masked for non-safe keys;
 * the bridge exposes no unmasked read of secret keys (reveal-with-biometric
 * and ~/.bashrc parsing land later).
 */
class EnvViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class EnvRow(
        val key: String,
        val value: String,
        val masked: Boolean,
    )

    data class UiState(
        val rows: List<EnvRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun set(key: String, value: String) {
        scope.launch {
            runCatching {
                bridgeRequest("env.set", BridgeJson.obj("key" to key.trim(), "value" to value))
            }.onSuccess {
                _state.value = _state.value.copy(error = null)
                refresh()
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "env.set failed")
            }
        }
    }

    fun delete(key: String) {
        scope.launch {
            runCatching { bridgeRequest("env.delete", BridgeJson.obj("key" to key)) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
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
                val result = transport.request("env.list")
                val arr = BridgeJson.array(result, "vars")
                    ?: JsonArray(emptyList())
                _state.value = UiState(
                    rows = arr.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val key = BridgeJson.string(obj, "key") ?: return@mapNotNull null
                        val value = BridgeJson.string(obj, "value") ?: ""
                        EnvRow(key, value, masked = value.startsWith("***(hides"))
                    }.sortedBy { it.key },
                    loaded = true,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "env fetch failed")
            } finally {
                transport.close()
            }
        }
    }

    private suspend fun bridgeRequest(method: String, params: kotlinx.serialization.json.JsonElement? = null): kotlinx.serialization.json.JsonElement {
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
            if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) throw IllegalStateException("bridge not ready")
            return transport.request(method, params)
        } finally {
            transport.close()
        }
    }
}
