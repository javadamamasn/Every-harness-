package com.harnesshub.app.ui.screens.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
 * Memory & identity (section 13): view/edit the harness identity and memory
 * files over the bridge fs primitives. Edits keep the bridge's timestamped
 * fs.write backup; diff-before-save lands at M10.
 */
@Composable
fun MemoryScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        MemoryViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var editPath by remember { mutableStateOf<String?>(null) }
    var editBody by remember { mutableStateOf("") }

    editPath?.let { path ->
        AlertDialog(
            onDismissRequest = { editPath = null },
            title = { Text(stringResource(R.string.memory_edit_title)) },
            text = {
                OutlinedTextField(
                    value = editBody,
                    onValueChange = { editBody = it },
                    minLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = path
                    editPath = null
                    viewModel.saveEdit(target, editBody) { }
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editPath = null }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.tasks_refresh))
                }
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.loaded && state.rows.isEmpty() && state.error == null) {
            item {
                Text(
                    stringResource(R.string.memory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.rows, key = { r -> r.relPath }) { row ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.fullPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (row.present) {
                            row.size?.let { stringResource(R.string.memory_size, it) }
                                ?: stringResource(R.string.memory_missing)
                        } else {
                            stringResource(R.string.memory_missing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.present) {
                        TextButton(onClick = {
                            editPath = row.fullPath
                            viewModel.readContent(row.fullPath) { editBody = it }
                        }) { Text(stringResource(R.string.skills_edit)) }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.memory_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
