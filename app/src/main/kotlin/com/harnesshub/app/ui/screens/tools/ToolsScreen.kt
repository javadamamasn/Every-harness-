package com.harnesshub.app.ui.screens.tools

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
import androidx.compose.material3.Switch
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
 * Tools & permissions (section 13): bridge exec.run flag switch, Claude Code
 * permission rules (allow/deny) and defaultMode display. Approvals inbox
 * stays in chat; per-session toggles and mode-switch UI land with the
 * approvals engine.
 */
@Composable
fun ToolsScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        ToolsViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var addList by remember { mutableStateOf("allow") }
    var ruleText by remember { mutableStateOf("") }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tools_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { addList = "allow" }) {
                            Text(
                                stringResource(R.string.tools_list_allow),
                                color = if (addList == "allow") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = { addList = "deny" }) {
                            Text(
                                stringResource(R.string.tools_list_deny),
                                color = if (addList == "deny") {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = ruleText,
                        onValueChange = { ruleText = it },
                        label = { Text(stringResource(R.string.tools_field_rule)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAdd = false
                    viewModel.addRule(addList, ruleText)
                    ruleText = ""
                }) { Text(stringResource(R.string.hub_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
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
                Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.headlineMedium)
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
        item {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.tools_exec_toggle), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.tools_exec_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.execFlag == true,
                        onCheckedChange = { viewModel.toggleExec(it) },
                        enabled = state.execFlag != null,
                    )
                }
            }
        }
        item {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        state.defaultMode?.let { stringResource(R.string.tools_default_mode, it) }
                            ?: stringResource(R.string.tools_default_mode_unset),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tools_rules_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    addList = "allow"
                    showAdd = true
                }) { Text(stringResource(R.string.env_add)) }
            }
        }
        state.allowRules.forEach { rule ->
            item(key = "allow:$rule") {
                ruleRow(rule, stringResource(R.string.tools_list_allow), MaterialTheme.colorScheme.primary) {
                    viewModel.removeRule("allow", rule)
                }
            }
        }
        state.denyRules.forEach { rule ->
            item(key = "deny:$rule") {
                ruleRow(rule, stringResource(R.string.tools_list_deny), MaterialTheme.colorScheme.tertiary) {
                    viewModel.removeRule("deny", rule)
                }
            }
        }
        item {
            Text(
                stringResource(R.string.tools_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ruleRow(rule: String, listLabel: String, labelColor: androidx.compose.ui.graphics.Color, onRemove: () -> Unit) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule, style = MaterialTheme.typography.titleSmall)
                Text(
                    listLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.sessions_delete)) }
        }
    }
}
