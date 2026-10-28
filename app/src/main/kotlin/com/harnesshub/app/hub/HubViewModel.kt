package com.harnesshub.app.hub

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.app.data.HermesSettingsStore
import com.harnesshub.discovery.DiscoveryRanker
import com.harnesshub.model.HarnessInstance
import com.harnesshub.transport.BridgeJson
import com.harnesshub.transport.BridgeException
import com.harnesshub.transport.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket

/**
 * Hub engine: one-shot connect to the bridge, discovery.scan, and the
 * section-19/17 extras — resource stats, the Tier-1 Hermes zero-setup
 * (`hermes.setup`: discovers the running dashboard, vaults its token),
 * resource-mode switching and the exec-flag gate.
 *
 * Kept session-scoped on purpose (connect-scan-close per rescan); the pinned
 * foreground connection arrives with the service milestone.
 */
class HubViewModel(application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val hermesStore = HermesSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    data class HermesCard(val port: Int, val startedByBridge: Boolean, val pid: Int?)
    data class HarnessProc(val name: String, val pid: Int, val alive: Boolean, val rssKB: Int)
    data class Resources(val memTotalKB: Long, val memAvailableKB: Long, val harnesses: List<HarnessProc>)

    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data object BridgeNotConfigured : State
        data class Error(val message: String) : State
        data class Ready(
            val instances: List<HarnessInstance>,
            val hermes: HermesCard?,
            val resources: Resources?,
            val resourceMode: String?,
            val execFlag: Boolean?,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    init {
        scope.launch {
            settings.settings().collect { prefs ->
                if (!prefs.configured && _state.value is State.Idle) {
                    _state.value = State.BridgeNotConfigured
                }
            }
        }
    }

    fun saveBridge(port: Int, token: String) {
        scope.launch {
            settings.save(port, token)
            rescan()
        }
    }

    fun rescan() {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) {
                _state.value = State.BridgeNotConfigured
                return@launch
            }
            _state.value = State.Scanning
            val transport = BridgeTransport(clientFactory = { listener -> newSocket(prefs.port, listener) })
            try {
                transport.connect(prefs.token)
                val ready = transport.awaitReady(timeoutMs = 8_000)
                if (ready is BridgeTransport.State.Failed) {
                    _state.value = State.Error("bridge did not authenticate (is allow-external-apps enabled and bridge running?)")
                    return@launch
                }
                val instances = DiscoveryRanker.rank(transport.discoveryScan())

                // hermes.setup may return {running, startedByBridge, port, pid, token}
                var hermesCard: HermesCard? = null
                runCatching { transport.requestEnvelope("hermes.setup") }.onSuccess { env ->
                    val r = env.result
                    if (r != null) {
                        val port = BridgeJson.int(r, "port") ?: 9119
                        val token = BridgeJson.string(r, "token")
                        val started = BridgeJson.bool(r, "startedByBridge") ?: false
                        val pid = BridgeJson.int(r, "pid")
                        if (BridgeJson.bool(r, "running") == true && !token.isNullOrBlank()) {
                            hermesStore.save(port, token)
                            hermesCard = HermesCard(port, started, pid)
                        } else if (BridgeJson.bool(r, "running") == true) {
                            hermesCard = HermesCard(port, started, pid)
                        }
                    }
                }

                val resources = runCatching { transport.requestEnvelope("system.resources").result }.getOrNull()?.let { r ->
                    Resources(
                        memTotalKB = BridgeJson.long(r, "memTotalKB") ?: 0,
                        memAvailableKB = BridgeJson.long(r, "memAvailableKB") ?: 0,
                        harnesses = parseHarnesses(r),
                    )
                }
                val modeEnv = runCatching { transport.requestEnvelope("bridge.resourceMode").result }
                val mode = modeEnv.getOrNull()?.let { BridgeJson.string(it, "mode") }
                val execFlag = runCatching { transport.requestEnvelope("harness.list").result }
                    .getOrNull()?.let { r -> BridgeJson.bool(r, "execFlag") }
                _state.value = Ready(
                    instances = instances,
                    hermes = hermesCard,
                    resources = resources,
                    resourceMode = mode,
                    execFlag = execFlag,
                )
            } catch (e: BridgeException) {
                _state.value = State.Error(e.error.message)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "scan failed")
            } finally {
                transport.close()
            }
        }
    }

    fun setResourceMode(mode: String) {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) return@launch
            val transport = BridgeTransport(clientFactory = { listener -> newSocket(prefs.port, listener) })
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady() is BridgeTransport.State.Ready) {
                    transport.request(
                        "bridge.resourceMode",
                        BridgeJson.obj("mode" to mode),
                    )
                    rescan()
                }
            } catch (e: BridgeException) {
                _state.value = State.Error(e.error.message)
            } finally {
                transport.close()
            }
        }
    }

    fun setExecFlag(on: Boolean) {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) return@launch
            val transport = BridgeTransport(clientFactory = { listener -> newSocket(prefs.port, listener) })
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady() is BridgeTransport.State.Ready) {
                    transport.request("bridge.execFlag", BridgeJson.obj("on" to on))
                    rescan()
                }
            } catch (e: BridgeException) {
                _state.value = State.Error(e.error.message)
            } finally {
                transport.close()
            }
        }
    }

    private fun newSocket(port: Int, listener: okhttp3.WebSocketListener): WebSocket {
        val request = Request.Builder().url("ws://127.0.0.1:$port").build()
        return httpClient.newWebSocket(request, listener)
    }

    private fun parseHarnesses(element: kotlinx.serialization.json.JsonElement): List<HarnessProc> {
        val arr = BridgeJson.array(element, "harnesses") ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = BridgeJson.string(obj, "name") ?: return@mapNotNull null
            HarnessProc(
                name = name,
                pid = BridgeJson.int(obj, "pid") ?: 0,
                alive = BridgeJson.bool(obj, "alive") ?: false,
                rssKB = BridgeJson.int(obj, "rssKB") ?: 0,
            )
        }
    }
}
