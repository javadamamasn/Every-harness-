package com.harnesshub.app.ui.screens.models

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
 * Models & providers (section 17): direct OpenCode providers (`/config/providers`,
 * v1.19.3-verified) listed with models. Per-session switching lands with the
 * OpenCode chat wiring at M7b (honest note below).
 */
@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        ModelsViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.nav_models), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.tasks_refresh))
                }
            }
        }
        when {
            state.error == "opencode_not_configured" -> item {
                Text(
                    stringResource(R.string.models_opencode_unconfigured),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error != null -> item {
                Text(state.error ?: "", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.providers, key = { p -> p.id }) { provider ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${provider.name} (${provider.id})", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.models_count, provider.models.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        provider.models.take(6).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.models_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
