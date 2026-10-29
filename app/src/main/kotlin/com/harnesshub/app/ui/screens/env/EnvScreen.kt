package com.harnesshub.app.ui.screens.env

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
 * Environment (section 13): masked listing + set/delete over the bridge
 * env methods (structured ~/.hermes/.env). No unmasked reveal on the bridge;
 * biometric reveal lands with the vault milestone.
 */
@Composable
fun EnvScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        EnvViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var deleteConfirmKey by remember { mutableStateOf<String?>(null) }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.env_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        label = { Text(stringResource(R.string.env_field_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it },
                        label = { Text(stringResource(R.string.env_field_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAdd = false
                    viewModel.set(keyText, valueText)
                    keyText = ""
                    valueText = ""
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            },
        )
    }

    deleteConfirmKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteConfirmKey = null },
            title = { Text(stringResource(R.string.env_delete_title)) },
            text = { Text(stringResource(R.string.env_delete_body, key)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = key
                    deleteConfirmKey = null
                    viewModel.delete(target)
                }) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmKey = null }) {
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
                Text(stringResource(R.string.env_title), style = MaterialTheme.typography.headlineMedium)
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
                    stringResource(R.string.env_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.rows, key = { r -> r.key }) { row ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.key, style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.masked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    TextButton(onClick = { deleteConfirmKey = row.key }) {
                        Text(stringResource(R.string.sessions_delete))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.env_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
