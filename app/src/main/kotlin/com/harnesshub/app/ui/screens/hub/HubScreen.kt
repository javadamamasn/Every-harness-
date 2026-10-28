package com.harnesshub.app.ui.screens.hub

import android.os.Build
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.harnesshub.app.R
import com.harnesshub.app.service.BridgeConnectionService
import com.harnesshub.app.hub.HubViewModel
import com.harnesshub.model.HarnessInstance

/**
 * Hub (home): one live card per discovered harness instance, driven by the M3
 * discovery engine over the local bridge. The setup sheet appears until a
 * bridge port + token exist in the settings store.
 */
@Composable
fun HubScreen(onOpenHermes: () -> Unit = {}, onOpenTasks: () -> Unit = {}) {
    val context = LocalContext.current
    val viewModel = remember { HubViewModel(context.applicationContext as android.app.Application) }
    val state by viewModel.state.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showExecConfirm by remember { mutableStateOf(false) }

    val notifyPermissionRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) com.harnesshub.app.service.BridgeConnectionService.start(context)
    }


    LaunchedEffect(Unit) {
        // Tether foreground service keeps the bridge alive on Android 12+ (honest partial: no pinned conn yet)
        if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            com.harnesshub.app.service.BridgeConnectionService.start(context)
        } else {
            notifyPermissionRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            TextButton(onClick = onOpenTasks) {
                Text(stringResource(R.string.nav_tasks))
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.hermes?.let { HermesCard(hermes = it, onOpen = onOpenHermes) }
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
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
        item {
            val tether by BridgeConnectionService.bridgeState.collectAsState()
            val label = when (tether) {
                BridgeConnectionService.BridgeState.Ready -> stringResource(R.string.hub_tether_ready)
                BridgeConnectionService.BridgeState.Connecting,
                BridgeConnectionService.BridgeState.Reconnecting -> stringResource(R.string.hub_tether_connecting)
                BridgeConnectionService.BridgeState.WaitingForSetup -> stringResource(R.string.hub_tether_setup)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

                            items(s.instances, key = { it.id.value }) { instance -> HubCard(instance) }
                            item { ResourceRows(s, viewModel, { showExecConfirm = true }) }
                        }
                    }
                    if (s.instances.isEmpty() && s.hermes == null) {
                        ResourceRows(s, viewModel, { showExecConfirm = true })
                    }
                }
            }
        }
    }

    if (showExecConfirm) {
        AlertDialog(
            onDismissRequest = { showExecConfirm = false },
            title = { Text(stringResource(R.string.hub_exec_confirm_title)) },
            text = { Text(stringResource(R.string.hub_exec_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExecConfirm = false
                    viewModel.setExecFlag(true)
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = { TextButton(onClick = { showExecConfirm = false }) { Text(stringResource(R.string.hub_cancel)) } },
        )
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

@Composable
private fun HermesCard(hermes: HubViewModel.HermesCard, onOpen: () -> Unit) {
    Surface(
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hermes Agent", style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.hub_tier1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(R.string.hub_hermes_status, hermes.port),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpen) { Text(stringResource(R.string.hub_open_hermes)) }
        }
    }
}

@Composable
private fun ResourceRows(s: HubViewModel.State.Ready, viewModel: HubViewModel, showExecConfirm: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        s.resources?.let { res ->
            val usedMiB = (res.memTotalKB - res.memAvailableKB) / 1024
            val totalMiB = res.memTotalKB / 1024
            Text(
                stringResource(R.string.hub_mem_line, usedMiB.toLong(), totalMiB.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            res.harnesses.forEach { h ->
                Text(
                    "• ${h.name}: ${(h.rssKB / 1024)} MiB · ${h.state}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(stringResource(R.string.hub_mode_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("exclusive" to R.string.hub_mode_exclusive, "smart" to R.string.hub_mode_smart, "manual" to R.string.hub_mode_manual)
                .forEach { (mode, label) ->
                    val selected = s.resourceMode == mode
                    TextButton(onClick = { viewModel.setResourceMode(mode) }) {
                        Text(
                            stringResource(label),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hub_exec_flag))
            Switch(
                checked = s.execFlag == true,
                onCheckedChange = { on ->
                    if (on) showExecConfirm() else viewModel.setExecFlag(false)
                },
            )
        }
    }
}
