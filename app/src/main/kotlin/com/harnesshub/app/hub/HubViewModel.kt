package com.harnesshub.app.hub

import android.app.Application
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.discovery.DiscoveryRanker
import com.harnesshub.model.HarnessInstance
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
 * M3 discovery engine, app side: connects one-shot to the bridge on the saved
 * loopback port, runs discovery.scan, ranks the results into the display model.
 *
 * Kept session-scoped on purpose (connect-scan-close per rescan); the pinned
 * foreground connection arrives with M6. The token lives in DataStore until the
 * Keystore vault lands (M6) — it is never logged and only sent inside the
 * bridge's WebSocket hello frame.
 */
class HubViewModel(application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data class Ready(val instances: List<HarnessInstance>) : State
        data object BridgeNotConfigured : State
        data class Error(val message: String) : State
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
                if (ready !is BridgeTransport.State.Ready) {
                    _state.value = State.Error(
                        (ready as? BridgeTransport.State.Failed)?.error?.message
                            ?: "bridge did not authenticate (is allow-external-apps enabled and bridge running?)",
                    )
                    return@launch
                }
                val results = transport.discoveryScan()
                _state.value = State.Ready(DiscoveryRanker.rank(results))
            } catch (e: BridgeException) {
                _state.value = State.Error(e.error.message)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "scan failed")
            } finally {
                transport.close()
            }
        }
    }

    private fun newSocket(port: Int, listener: okhttp3.WebSocketListener): WebSocket {
        val request = Request.Builder().url("ws://127.0.0.1:$port").build()
        return httpClient.newWebSocket(request, listener)
    }
}
