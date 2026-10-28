package com.harnesshub.app.ui.screens.skills

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Skills scanner over the bridge (`skills.list`, live-verified: reads
 * ~/.claude/skills and ~/.opencode/skill SKILL.md frontmatter). Editing and
 * installing arrive with the workspace engine (M7b, honest partial).
 */
class SkillsViewModel(private val application: Application) {

    private val settings = BridgeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class SkillRow(
        val name: String,
        val description: String,
        val path: String,
        val source: String,
    )

    data class UiState(
        val skills: List<SkillRow> = emptyList(),
        val error: String? = null,
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    /**
     * Installs one skill via the bridge (`fs.write` creates parents atomically;
     * roundtrip write→read→delete live-verified 2026-10-28).
     */
    fun install(name: String, description: String) {
        scope.launch {
            val safe = name.trim().replace(" ", "-")
            if (safe.isBlank()) return@launch
            val md = buildString {
                append("---\n")
                append("name: $safe\n")
                append("description: ${description.trim()}\n")
                append("---\n")
            }
            writeToBridge("$safe/SKILL.md", md)
            refresh()
        }
    }

    private suspend fun writeToBridge(relative: String, content: String) {
        val prefs = settings.settings().first()
        if (!prefs.configured) return
        val transport = BridgeTransport(clientFactory = { listener ->
            OkHttpClient().newWebSocket(
                Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(),
                listener,
            )
        })
        try {
            transport.connect(prefs.token)
            if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) return
            val home = BridgeJson.string(transport.request("system.info"), "home")
                ?: throw IllegalStateException("no bridge home")
            val result = transport.request(
                "fs.write",
                BridgeJson.obj(
                    "path" to "$home/.claude/skills/$relative",
                    "content" to content,
                ),
            )
            BridgeJson.string(result, "path") ?: throw IllegalStateException("fs.write failed")
        } finally {
            transport.close()
        }
    }

    fun refresh() {
        scope.launch {
            val prefs = settings.settings().first()
            if (!prefs.configured) {
                _state.value = UiState(loaded = true, error = "bridge_not_configured")
                return@launch
            }
            val transport = BridgeTransport(clientFactory = { listener ->
                OkHttpClient().newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(),
                    listener,
                )
            })
            try {
                transport.connect(prefs.token)
                if (transport.awaitReady(8_000) !is BridgeTransport.State.Ready) {
                    _state.value = UiState(loaded = true, error = "bridge not ready")
                    return@launch
                }
                val result = transport.request("skills.list")
                val arr = BridgeJson.array(result, "skills")
                    ?: kotlinx.serialization.json.JsonArray(emptyList())
                _state.value = UiState(
                    skills = arr.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        SkillRow(
                            name = BridgeJson.string(obj, "name") ?: return@mapNotNull null,
                            description = BridgeJson.string(obj, "description") ?: "",
                            path = BridgeJson.string(obj, "path") ?: "",
                            source = BridgeJson.string(obj, "source") ?: "",
                        )
                    },
                    loaded = true,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "skills fetch failed")
            } finally {
                transport.close()
            }
        }
    }
}
