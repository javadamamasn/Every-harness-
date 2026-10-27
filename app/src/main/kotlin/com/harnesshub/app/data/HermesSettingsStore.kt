package com.harnesshub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hermesStore by preferencesDataStore(name = "hermes_settings")

/** Hermes gateway connection settings (port + dashboard session token). */
class HermesSettingsStore(private val context: Context) {

    data class HermesSettings(
        val port: Int = 9119,
        val token: String = "",
    ) {
        val configured: Boolean get() = port > 0 && token.isNotBlank()
    }

    private val portKey = intPreferencesKey("hermes_port")
    private val tokenKey = stringPreferencesKey("hermes_token")

    fun settings(): Flow<HermesSettings> = context.hermesStore.data.map { prefs ->
        HermesSettings(prefs[portKey] ?: 9119, prefs[tokenKey] ?: "")
    }

    suspend fun save(port: Int, token: String) {
        context.hermesStore.edit { prefs ->
            prefs[portKey] = port
            prefs[tokenKey] = token
        }
    }
}
