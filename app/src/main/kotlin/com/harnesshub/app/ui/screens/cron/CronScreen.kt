package com.harnesshub.app.ui.screens.cron

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
 * Schedules (section 21): list/create/run-now/delete over the bridge cron
 * methods. Jobs resolve through the bridge allow-list (argument arrays, no
 * shell strings); the scheduler fires from the bridge while it is alive.
 */
@Composable
fun CronScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        CronViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.cron_create_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = spec,
                        onValueChange = { spec = it },
                        label = { Text(stringResource(R.string.cron_field_spec)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { spec = "0 * * * *" }) {
                            Text(stringResource(R.string.cron_preset_hourly))
                        }
                        TextButton(onClick = { spec = "0 9 * * *" }) {
                            Text(stringResource(R.string.cron_preset_daily))
                        }
                        TextButton(onClick = { spec = "*/15 * * * *" }) {
                            Text(stringResource(R.string.cron_preset_every15))
                        }
                        TextButton(onClick = { spec = "0 9 * * 0" }) {
                            Text(stringResource(R.string.cron_preset_weekly))
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.cron_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(stringResource(R.string.cron_field_args)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    viewModel.create(spec, name, argsText)
                    spec = ""
                    name = ""
                    argsText = ""
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            },
        )
    }

    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text(stringResource(R.string.cron_delete_title)) },
            text = { Text(stringResource(R.string.cron_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = id
                    deleteConfirmId = null
                    viewModel.delete(target)
                }) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) {
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
                Text(stringResource(R.string.cron_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { showCreate = true }) {
                    Text(stringResource(R.string.cron_create))
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
        if (state.loaded && state.jobs.isEmpty() && state.error == null) {
            item {
                Text(
                    stringResource(R.string.cron_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.jobs, key = { j -> j.id }) { job ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        job.spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.args.isNotEmpty()) {
                        Text(
                            job.args.joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        job.nextRunAt?.let {
                            stringResource(R.string.cron_next_run, it)
                        } ?: stringResource(R.string.cron_next_run_never),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        job.last?.let {
                            stringResource(
                                R.string.cron_last_run,
                                "${it.at} · ${it.status}" + (it.exit?.let { e -> " · $e" } ?: ""),
                            )
                        } ?: stringResource(R.string.cron_never_run),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.runNow(job.id) }) {
                            Text(stringResource(R.string.cron_run_now))
                        }
                        TextButton(onClick = { deleteConfirmId = job.id }) {
                            Text(stringResource(R.string.sessions_delete))
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.cron_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
