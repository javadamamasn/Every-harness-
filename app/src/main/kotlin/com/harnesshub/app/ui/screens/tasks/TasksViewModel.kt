package com.harnesshub.app.ui.screens.tasks

import android.app.Application
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket

/** Tasks desk (Appendix A process.* + system.resources): launch specs + live rows. */
class TasksViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ParsedRow(val id: String, val name: String, val pid: Int?, val exit: Int?)
    data class Spec(val name: String, val command: String)

    data class UiState(
        val processes: List<ParsedRow> = emptyList(),
        val specs: List<Spec> = emptyList(),
        val harnessMem: Map<String, Long> = emptyMap(),
        val error: String? = null,
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
                _state.value = UiState(error = "bridge_not_configured")
                return@launch
            }
            val transport = makeTransport(prefs.port)
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) {
                    _state.value = UiState(error = "bridge not ready")
                    return@launch
                }
                val list = transport.request("process.list")
                _state.value = UiState(
                    processes = parseProcesses(list),
                    specs = parseSpecs(list),
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "tasks refresh failed")
            } finally {
                transport.close()
            }
        }
    }

    /** START: `process.start` for one allow-listed launch spec name. */
    fun start(name: String) = mutate("process.start") { obj("name" to name) }

    /** STOP by process id. */
    fun stop(id: String) = mutate("process.stop") { obj("id" to id) }

    private fun mutate(method: String, params: () -> JsonElement) {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) return@launch
            val transport = makeTransport(prefs.port)
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady(8_000) is BridgeTransport.State.Ready) {
                    transport.request(method, params())
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                transport.close()
            }
            refresh()
        }
    }

    private fun makeTransport(port: Int): BridgeTransport = BridgeTransport(
        clientFactory = { listener: okhttp3.WebSocketListener ->
            OkHttpClient().newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port").build(),
                listener,
            )
        },
    )

    private fun parseProcesses(result: JsonElement?): List<ParsedRow> {
        val arr = BridgeJson.array(result, "processes") ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ParsedRow(
                id = BridgeJson.string(obj, "id") ?: return@mapNotNull null,
                name = BridgeJson.string(obj, "name") ?: "",
                pid = BridgeJson.int(obj, "pid"),
                exit = BridgeJson.int(obj, "exit"),
            )
        }
    }

    private fun parseSpecs(result: JsonElement?): List<Spec> {
        val arr = BridgeJson.array(result, "allowlist") ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = BridgeJson.string(obj, "name") ?: return@mapNotNull null
            Spec(name, BridgeJson.string(obj, "command") ?: "")
        }
    }
}

private fun obj(vararg pairs: Pair<String, Any?>): kotlinx.serialization.json.JsonElement =
    BridgeJson.obj(*pairs)
