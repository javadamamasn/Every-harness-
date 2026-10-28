package com.harnesshub.app.ui.screens.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R

/**
 * Skills (section 17): read-only catalogue over the bridge `skills.list`
 * (frontmatter name + description per SKILL.md). Install/edit land at M7b.
 */
@Composable
fun SkillsScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        SkillsViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var showInstall by remember { mutableStateOf(false) }
    var skillName by remember { mutableStateOf("") }
    var skillDesc by remember { mutableStateOf("") }
    var editPath by remember { mutableStateOf<String?>(null) }
    var editBody by remember { mutableStateOf("") }
    var deleteConfirmPath by remember { mutableStateOf<String?>(null) }

if (showInstall) {
        AlertDialog(
            onDismissRequest = { showInstall = false },
            title = { Text(stringResource(R.string.skills_install_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = skillName, onValueChange = { skillName = it },
                        label = { Text(stringResource(R.string.skills_install_name)) })
                    OutlinedTextField(value = skillDesc, onValueChange = { skillDesc = it },
                        label = { Text(stringResource(R.string.skills_install_desc)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showInstall = false
                    viewModel.install(skillName, skillDesc)
                    skillName = ""
                    skillDesc = ""
                }) { Text(stringResource(R.string.opencode_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showInstall = false }) { Text(stringResource(R.string.sessions_delete)) }
            },
        )
    }


    editPath?.let { path ->
        AlertDialog(
            onDismissRequest = { editPath = null },
            title = { Text(stringResource(R.string.skills_edit_title)) },
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
                }) { Text(stringResource(R.string.opencode_save)) }
            },
            dismissButton = { TextButton(onClick = { editPath = null }) { Text(stringResource(R.string.sessions_delete)) } },
        )
    }
    deleteConfirmPath?.let { dirPath ->
        AlertDialog(
            onDismissRequest = { deleteConfirmPath = null },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(stringResource(R.string.skills_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmPath = null
                    viewModel.deleteSkill(dirPath) { }
                }) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmPath = null }) { Text(stringResource(R.string.hub_cancel)) } },
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
                Text(stringResource(R.string.nav_skills), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { showInstall = true }) {
                    Text(stringResource(R.string.skills_install))
                }
                TextButton(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.tasks_refresh))
                }
            }
        }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.skills, key = { s -> s.path }) { skill ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${skill.source} · ${skill.path}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            editPath = skill.path
                            viewModel.readContent(skill.path) { editBody = it }
                        }) { Text(stringResource(R.string.skills_edit)) }
                        TextButton(onClick = { deleteConfirmPath = skill.path }) {
                            Text(stringResource(R.string.sessions_delete))
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.skills_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
