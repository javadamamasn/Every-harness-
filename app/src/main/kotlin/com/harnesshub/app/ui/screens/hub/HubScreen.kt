package com.harnesshub.app.ui.screens.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harnesshub.app.R
import com.harnesshub.app.hub.HubViewModel
import com.harnesshub.model.HarnessInstance

/**
 * Hub (home): one live card per discovered harness instance, driven by the M3
 * discovery engine over the local bridge. The setup sheet appears until a
 * bridge port + token exist in the settings store.
 */
@Composable
fun HubScreen() {
    val context = LocalContext.current
    val viewModel = remember { HubViewModel(context.applicationContext as android.app.Application) }
    val state by viewModel.state.collectAsState()
    var showSetup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.hub_title), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.rescan() }) {
                Text(stringResource(R.string.hub_rescan))
            }
            TextButton(onClick = { showSetup = true }) {
                Text(stringResource(R.string.hub_bridge_settings))
            }
        }

        when (val s = state) {
            HubViewModel.State.Idle, HubViewModel.State.BridgeNotConfigured -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hub_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.hub_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HubViewModel.State.Scanning -> Text(stringResource(R.string.hub_scanning))
            is HubViewModel.State.Error -> {
                Text(
                    stringResource(R.string.hub_error_prefix, s.message),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is HubViewModel.State.Ready -> {
                if (s.instances.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.hub_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.hub_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.instances, key = { it.id.value }) { instance -> HubCard(instance) }
                    }
                }
            }
        }
    }

    if (showSetup) {
        BridgeSetupDialog(
            onDismiss = { showSetup = false },
            onSave = { port, token ->
                showSetup = false
                viewModel.saveBridge(port, token)
            },
        )
    }
}

@Composable
private fun HubCard(instance: HarnessInstance) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(instance.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${instance.status} · ${instance.location} · ${(instance.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            instance.executablePath?.let { path ->
                Text(path, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BridgeSetupDialog(
    onDismiss: () -> Unit,
    onSave: (Int, String) -> Unit,
) {
    var portText by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_bridge_setup)) },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 0
                    if (port > 0 && tokenText.isNotBlank()) onSave(port, tokenText)
                },
            ) { Text(stringResource(R.string.hub_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text(stringResource(R.string.hub_port_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text(stringResource(R.string.hub_token_label)) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.hub_bridge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
