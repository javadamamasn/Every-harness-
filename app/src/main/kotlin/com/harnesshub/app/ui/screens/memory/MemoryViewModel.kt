package com.harnesshub.app.ui.screens.memory

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
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Memory & identity files (section 13 row: SOUL.md/CLAUDE.md/AGENTS.md and
 * equivalents) over the bridge fs primitives. Candidate paths fixed per the
 * on-device harness layout (verified 2026-10-29: ~/.hermes/SOUL.md,
 * ~/.hermes/memories/{MEMORY,USER}.md, ~/CLAUDE.md, ~/.codex/AGENTS.md);
 * missing files surface honestly as "not present". fs.write keeps a
 * timestamped backup; diff-before-save lands at M10.
 */
class MemoryViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** name, relative-to-home path, present flag, size bytes. */
    data class MemoryRow(
        val name: String,
        val relPath: String,
        val fullPath: String,
        val present: Boolean,
        val size: Int?,
    )

    data class UiState(
        val rows: List<MemoryRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val candidates = listOf(
        "Hermes · SOUL.md" to ".hermes/SOUL.md",
        "Hermes · MEMORY.md" to ".hermes/memories/MEMORY.md",
        "Hermes · USER.md" to ".hermes/memories/USER.md",
        "Claude · CLAUDE.md" to "CLAUDE.md",
        "Claude · CLAUDE.md (global)" to ".claude/CLAUDE.md",
        "Codex · AGENTS.md" to ".codex/AGENTS.md",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun readContent(fullPath: String, onDone: (String) -> Unit) {
        scope.launch {
            runCatching {
                val res = bridgeRequest("fs.read", BridgeJson.obj("path" to fullPath))
                BridgeJson.string(res, "content") ?: ""
            }.onSuccess { onDone(it) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun saveEdit(fullPath: String, content: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                bridgeRequest("fs.write", BridgeJson.obj("path" to fullPath, "content" to content))
                true
            }.getOrDefault(false)
            if (ok) refresh() else _state.value = _state.value.copy(error = "fs.write failed")
            onDone(ok)
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
                val rows = mutableListOf<MemoryRow>()
                for ((name, rel) in candidates) {
                    val fullPath = "$home/$rel"
                    val env = transport.requestEnvelope("fs.stat", BridgeJson.obj("path" to fullPath))
                    if (env.error != null) {
                        // NOT_FOUND (missing file) is an honest, expected state
                        rows.add(MemoryRow(name, rel, fullPath, present = false, size = null))
                    } else {
                        rows.add(
                            MemoryRow(
                                name = name,
                                relPath = rel,
                                fullPath = fullPath,
                                present = true,
                                size = BridgeJson.int(env.result, "size"),
                            ),
                        )
                    }
                }
                _state.value = UiState(rows = rows, loaded = true, error = null)
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "memory fetch failed")
            } finally {
                transport.close()
            }
        }
    }

    private suspend fun bridgeRequest(
        method: String,
        params: JsonElement? = null,
    ): JsonElement {
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
