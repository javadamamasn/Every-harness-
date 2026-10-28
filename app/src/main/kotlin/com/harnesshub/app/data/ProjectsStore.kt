package com.harnesshub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.projectsStore by preferencesDataStore(name = "projects")

/**
 * Working directories (projects) the user registered — section 17 "project /
 * working-directory selection". Paths stay on-device; nothing is uploaded.
 * Applied where the underlying CLI honors a cwd (Claude through `exec.run`;
 * marked partial for Hermes/OpenCode until their APIs expose it).
 */
class ProjectsStore(private val context: Context) {

    @Serializable
    data class Project(
        val path: String,
        val addedAt: Long,
    )

    private val projectsKey = stringPreferencesKey("projects_json")
    private val activeKey = stringPreferencesKey("active_project")
    private val json = Json { ignoreUnknownKeys = true }

    fun projects(): Flow<List<Project>> = context.projectsStore.data.map { prefs ->
        parse(prefs[projectsKey] ?: "[]")
    }

    fun activePath(): Flow<String?> = context.projectsStore.data.map { prefs ->
        prefs[activeKey]?.takeIf { it.isNotBlank() }
    }

    /** One-shot read (suspend) for turn-time cwd lookup. */
    suspend fun activePathData(): String? =
        context.projectsStore.data.map { prefs ->
            prefs[activeKey]?.takeIf { it.isNotBlank() }
        }.first()

    private fun parse(raw: String): List<Project> = runCatching {
        json.decodeFromString<List<Project>>(raw)
    }.getOrDefault(emptyList())

    suspend fun add(path: String) {
        val clean = path.trim().removeSuffix("/")
        if (clean.isBlank()) return
        context.projectsStore.edit { prefs ->
            val current = parse(prefs[projectsKey] ?: "[]").toMutableList()
            if (current.none { it.path == clean }) {
                current.add(Project(clean, System.currentTimeMillis()))
            }
            prefs[projectsKey] = Json.encodeToString<List<Project>>(current)
            prefs[activeKey] = clean // newly added becomes active
        }
    }

    suspend fun setActive(path: String) {
        context.projectsStore.edit { prefs -> prefs[activeKey] = path }
    }
}
