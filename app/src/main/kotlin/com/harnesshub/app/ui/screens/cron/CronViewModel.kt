package com.harnesshub.app.ui.screens.cron

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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cron schedules (section 21) over the bridge: `cron.list/create/delete/
 * run-now` against allow-listed launch specs (argument arrays, no shell).
 * Bridge verification basis 2026-10-29: conformance 44/44 + live minute-tick
 * fire test.
 */
class CronViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class CronRun(
        val at: String,
        val status: String,
        val exit: String?,
    )

    data class CronRow(
        val id: String,
        val spec: String,
        val name: String,
        val args: List<String>,
        val nextRunAt: String?,
        val last: CronRun?,
    )

    data class UiState(
        val jobs: List<CronRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    /** Creates a job via `cron.create`; the bridge validates spec + allow-list. */
    fun create(spec: String, name: String, argsText: String) {
        scope.launch {
            runCatching {
                val args = argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val params = if (args.isEmpty()) {
                    BridgeJson.obj("spec" to spec.trim(), "name" to name.trim())
                } else {
                    BridgeJson.obj(
                        "spec" to spec.trim(),
                        "name" to name.trim(),
                        "args" to BridgeJson.arrOfStrings(args),
                    )
                }
                bridgeRequest("cron.create", params)
            }.onSuccess {
                _state.value = _state.value.copy(error = null)
                refresh()
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "cron.create failed")
            }
        }
    }

    fun runNow(id: String) {
        scope.launch {
            runCatching { bridgeRequest("cron.run-now", BridgeJson.obj("id" to id)) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun delete(id: String) {
        scope.launch {
            runCatching { bridgeRequest("cron.delete", BridgeJson.obj("id" to id)) }
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
                val result = transport.request("cron.list")
                val arr = BridgeJson.array(result, "jobs")
                    ?: JsonArray(emptyList())
                _state.value = UiState(
                    jobs = arr.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        CronRow(
                            id = BridgeJson.string(obj, "id") ?: return@mapNotNull null,
                            spec = BridgeJson.string(obj, "spec") ?: "",
                            name = BridgeJson.string(obj, "name") ?: "",
                            args = (obj["args"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                                ?: emptyList(),
                            nextRunAt = BridgeJson.string(obj, "nextRunAt"),
                            last = (obj["lastRun"] as? JsonObject)?.let { last ->
                                CronRun(
                                    at = BridgeJson.string(last, "at") ?: "",
                                    status = BridgeJson.string(last, "status") ?: "",
                                    exit = (last["exit"] as? JsonObject)?.let { ex ->
                                        BridgeJson.int(ex, "code")?.let { "exit $it" }
                                            ?: BridgeJson.string(ex, "signal")?.let { "killed ($it)" }
                                    },
                                )
                            },
                        )
                    },
                    loaded = true,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "cron fetch failed")
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
