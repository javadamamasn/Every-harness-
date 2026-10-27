package com.harnesshub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bridgeStore by preferencesDataStore(name = "bridge_settings")

/** Persisted bridge connection settings (port + token), never logged. */
class BridgeSettingsStore(private val context: Context) {

    data class BridgeSettings(
        val port: Int = 0,
        val token: String = "",
    ) {
        val configured: Boolean get() = port > 0 && token.isNotBlank()
    }

    private val portKey = intPreferencesKey("bridge_port")
    private val tokenKey = stringPreferencesKey("bridge_token")

    fun settings(): Flow<BridgeSettings> = context.bridgeStore.data.map { prefs ->
        BridgeSettings(prefs[portKey] ?: 0, prefs[tokenKey] ?: "")
    }

    suspend fun save(port: Int, token: String) {
        context.bridgeStore.edit { prefs ->
            prefs[portKey] = port
            prefs[tokenKey] = token
        }
    }
}
