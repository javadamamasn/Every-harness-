package com.harnesshub.app.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.sessionStore by preferencesDataStore(name = "sessions")

/**
 * Directory of chat sessions per engine (Appendix: sessions list + resume).
 * One row per created/resumed chat; persisted in DataStore, never secrets.
 */
class SessionStore(private val context: Context) {

    @Serializable
    data class SessionRecord(
        val engine: String,     // HERMES | CLAUDE | OPENCODE
        val sessionId: String,  // engine-native id
        val title: String,
        val updatedAt: Long,
    )

    private val key = stringPreferencesKey("sessions_json")
    private val json = Json { ignoreUnknownKeys = true }

    fun records(): Flow<List<SessionRecord>> = context.sessionStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        parse(raw)
    }

    private fun parse(raw: String): List<SessionRecord> = runCatching {
        json.decodeFromString<List<SessionRecord>>(raw)
    }.getOrDefault(emptyList())

    suspend fun record(engine: String, sessionId: String, title: String) {
        if (sessionId.isBlank()) return
        context.sessionStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").toMutableList()
            current.removeAll { it.engine == engine && it.sessionId == sessionId }
            current.add(SessionRecord(engine, sessionId, title, System.currentTimeMillis()))
            prefs[key] = Json.encodeToString<List<SessionRecord>>(current.sortedBy { it.updatedAt }.takeLast(200))
        }
    }

    suspend fun delete(engine: String, sessionId: String) {
        context.sessionStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").filterNot { it.engine == engine && it.sessionId == sessionId }
            prefs[key] = Json.encodeToString<List<SessionRecord>>(current)
        }
    }

    companion object {
        const val ENGINE_HERMES = "HERMES"
        const val ENGINE_CLAUDE = "CLAUDE"
        const val ENGINE_OPENCODE = "OPENCODE"
    }
}
