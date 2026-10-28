package com.harnesshub.app.ui.screens.projects

import android.app.Application
import com.harnesshub.app.data.ProjectsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<ProjectsStore.Project> = emptyList(),
    val active: String? = null,
)

class ProjectsViewModel(application: Application) {

    private val store = ProjectsStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: Flow<ProjectsUiState> = combine(store.projects(), store.activePath()) { list, active ->
        ProjectsUiState(list, active)
    }

    fun add(path: String) {
        scope.launch { store.add(path) }
    }

    fun setActive(path: String) {
        scope.launch { store.setActive(path) }
    }
}
