package com.harnesshub.app.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R

/**
 * Projects (section 17): working-directory registry. Claude turns run with the
 * active directory as `cwd` (verified); partial for Hermes/OpenCode until
 * their fields are exposed. Termux paths only (127.0.0.1 bridge containment).
 */
@Composable
fun ProjectsScreen() {
    val context = LocalContext.current
    val viewModel =
        remember { ProjectsViewModel(context.applicationContext as android.app.Application) }
    val state by viewModel.state.collectAsState(initial = ProjectsUiState())
    var draft by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_projects), style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.projects_add_hint)) },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    viewModel.add(draft)
                    draft = ""
                }) { Text(stringResource(R.string.projects_add)) }
            }
        }
        if (state.projects.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.projects_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.projects, key = { p -> p.path }) { project ->
            Surface(
                tonalElevation = if (state.active == project.path) 4.dp else 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(project.path, style = MaterialTheme.typography.bodyMedium)
                        if (state.active == project.path) {
                            Text(
                                stringResource(R.string.projects_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.setActive(project.path) }) {
                        Text(stringResource(R.string.projects_set_active))
                    }
                }
            }
        }
    }
}
