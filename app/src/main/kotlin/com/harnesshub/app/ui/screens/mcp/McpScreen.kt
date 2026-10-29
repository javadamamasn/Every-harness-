package com.harnesshub.app.ui.screens.mcp

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
 * MCP servers (section 13): list/add/remove Claude-Code stdio servers in
 * ~/.claude.json. Every edit rewrites the whole file via fs.write (backup
 * kept); a size guard refuses rewriting configs larger than the transport
 * window. Test-connection and per-harness config land later (partial note).
 */
@Composable
fun McpScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        McpViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var deleteConfirmName by remember { mutableStateOf<String?>(null) }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.mcp_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.mcp_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.mcp_field_command)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(stringResource(R.string.mcp_field_args)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAdd = false
                    viewModel.add(name, command, argsText)
                    name = ""
                    command = ""
                    argsText = ""
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            },
        )
    }

    deleteConfirmName?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmName = null },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(stringResource(R.string.mcp_delete_body, target)) },
            confirmButton = {
                TextButton(onClick = {
                    val victim = target
                    deleteConfirmName = null
                    viewModel.remove(victim)
                }) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmName = null }) {
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
                Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { showAdd = true }) {
                    Text(stringResource(R.string.env_add))
                }
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
                    stringResource(R.string.mcp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.rows, key = { r -> r.name }) { row ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { deleteConfirmName = row.name }) {
                        Text(stringResource(R.string.sessions_delete))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.mcp_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
