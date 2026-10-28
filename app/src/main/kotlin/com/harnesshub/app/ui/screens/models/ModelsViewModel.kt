package com.harnesshub.app.ui.screens.models

import android.app.Application
import com.harnesshub.opencode.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * M7: skills run the ModelsScreen — providers/models for the configured
 * direct OpenCode server (`/config/providers`, verified live v1.19.3).
 * Hermes/OpenCode model switching rides the turn APIs at M7b (honest partial).
 */
class ModelsViewModel(private val application: Application) {

    private val opencodeSettings = OpenCodeSettingsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class UiState(
        val providers: List<OpenCodeClient.Provider> = emptyList(),
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
            val prefs = opencodeSettings.settings().first()
            if (!prefs.configured) {
                _state.value = UiState(loaded = true, error = "opencode_not_configured")
                return@launch
            }
            try {
                val client = OpenCodeClient(port = prefs.port, password = prefs.password.ifBlank { null })
                val providers = client.listProviders()
                _state.value = UiState(providers = providers, loaded = true, error = null)
            } catch (e: Exception) {
                _state.value = UiState(loaded = true, error = e.message ?: "providers fetch failed")
            }
        }
    }
}
