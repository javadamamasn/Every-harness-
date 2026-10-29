package com.harnesshub.app.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harnesshub.app.chat.OpenAiCompatClient
import com.harnesshub.app.data.CompatSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * M8 screen state: form + live probe of /v1/models against a configured
 * OpenAI-compatible endpoint. Key is stored Vault-sealed; values are never
 * logged. Wire shape verified on-device (local router, 2026-10-29).
 */
class CompatProvidersViewModel(application: android.app.Application) : ViewModel() {

    data class UiState(
        val baseUrl: String = "",
        val model: String = "",
        val apiKeyPresent: Boolean = false,
        val models: List<String> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
        val probeDone: Boolean = false,
    )

    private val store = CompatSettingsStore(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val prefs = store.settings().first()
            _state.value = UiState(
                baseUrl = prefs.baseUrl,
                model = prefs.model,
                apiKeyPresent = prefs.apiKey.isNotEmpty(),
            )
        }
    }

    fun edit(baseUrl: String? = null, model: String? = null, apiKey: String? = null) {
        val cur = _state.value
        _state.value = cur.copy(
            baseUrl = baseUrl ?: cur.baseUrl,
            model = model ?: cur.model,
            apiKeyPresent = apiKey?.isNotBlank() ?: cur.apiKeyPresent,
        )
        latestKey = apiKey
    }

    private var latestKey: String? = null

    fun probe() {
        val cur = _state.value
        if (cur.busy) return
        viewModelScope.launch {
            _state.value = cur.copy(busy = true, error = null)
            val prefs = store.settings().first()
            val key = if (latestKey != null) latestKey!! else prefs.apiKey
            val client = OpenAiCompatClient(cur.baseUrl.trim(), key.takeIf { it.isNotBlank() })
            client.listModels()
                .onSuccess { models ->
                    _state.value = _state.value.copy(
                        busy = false,
                        probeDone = true,
                        models = models,
                        model = if (models.contains(_state.value.model)) _state.value.model
                        else models.firstOrNull() ?: _state.value.model,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        probeDone = true,
                        error = e.message ?: "probe failed",
                    )
                }
        }
    }

    fun save() {
        val cur = _state.value
        viewModelScope.launch {
            val key = when {
                latestKey != null -> latestKey!!
                else -> store.settings().first().apiKey
            }
            store.save(cur.baseUrl.trim(), cur.model.trim(), key)
            _state.value = _state.value.copy(error = null)
        }
    }
}
