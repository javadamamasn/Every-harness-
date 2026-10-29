package com.harnesshub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.compatStore by preferencesDataStore(name = "compat_settings")

/**
 * OpenAI-compatible provider adapter (M8): loopback/remote base URL, model
 * id, API key. The key is sealed through the Vault like every other secret —
 * never logged, never committed. No other settings ride here.
 */
class CompatSettingsStore(private val context: Context) {

    data class Settings(
        val baseUrl: String = "",
        val model: String = "",
        val apiKey: String = "",
    ) {
        val configured: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank()
    }

    private val urlKey = stringPreferencesKey("compat_base_url")
    private val modelKey = stringPreferencesKey("compat_model")
    private val keyKey = stringPreferencesKey("compat_api_key")

    fun settings(): Flow<Settings> = context.compatStore.data.map { prefs ->
        val sealed = prefs[keyKey] ?: ""
        Settings(
            baseUrl = (prefs[urlKey] ?: "").trimEnd('/'),
            model = prefs[modelKey] ?: "",
            apiKey = sealed.takeIf { it.isNotBlank() }?.let { Vault.decrypt(it) } ?: "",
        )
    }

    suspend fun save(baseUrl: String, model: String, apiKey: String) {
        context.compatStore.edit { prefs ->
            prefs[urlKey] = baseUrl.trimEnd('/')
            prefs[modelKey] = model
            prefs[keyKey] = apiKey.takeIf { it.isNotBlank() }?.let { Vault.encrypt(it.toCharArray()) } ?: ""
        }
    }
}
