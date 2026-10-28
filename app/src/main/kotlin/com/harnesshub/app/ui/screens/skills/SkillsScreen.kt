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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
