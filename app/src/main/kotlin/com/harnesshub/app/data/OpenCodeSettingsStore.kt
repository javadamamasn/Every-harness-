package com.harnesshub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.opencodeStore by preferencesDataStore(name = "opencode_settings")

/**
 * Direct OpenCode server connection (Tier 1, `opencode serve`): loopback port
 * and optional HTTP-Basic password. The password is sealed through the Vault
 * exactly like the Hermes/bridge tokens — never logged.
 */
class OpenCodeSettingsStore(private val context: Context) {

    data class Settings(
        val port: Int = 4096,
        val password: String = "",
        val modelProvider: String = "",
        val modelId: String = "",
    ) {
        val configured: Boolean get() = port > 0 // password optional
        val preferredModel: Pair<String, String>?
            get() = modelProvider.takeIf { it.isNotBlank() && modelId.isNotBlank() }?.let { it to modelId }
    }

    private val portKey = intPreferencesKey("opencode_port")
    private val passwordKey = stringPreferencesKey("opencode_password")
    private val modelProviderKey = stringPreferencesKey("opencode_model_provider")
    private val modelIdKey = stringPreferencesKey("opencode_model_id")

    fun settings(): Flow<Settings> = context.opencodeStore.data.map { prefs ->
        val stored = prefs[passwordKey] ?: ""
        Settings(prefs[portKey] ?: 4096,
            password = stored.ifBlank { "" },
            modelProvider = prefs[modelProviderKey] ?: "",
            modelId = prefs[modelIdKey] ?: "",
        )
    }

    suspend fun save(port: Int, password: String) {
        context.opencodeStore.edit { prefs ->
            prefs[portKey] = port
            prefs[passwordKey] = if (password.isBlank()) "" else Vault.encrypt(password.toCharArray())
        }
    }

    /** Preferred model is a plain preference (provider-scoped id), not a secret. */
    suspend fun savePreferredModel(providerId: String, modelId: String) {
        context.opencodeStore.edit { prefs ->
            prefs[modelProviderKey] = providerId
            prefs[modelIdKey] = modelId
        }
    }
}
