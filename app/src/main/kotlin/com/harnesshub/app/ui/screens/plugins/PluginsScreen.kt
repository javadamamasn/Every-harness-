package com.harnesshub.app.ui.screens.plugins

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
 * Plugins (section 13): read-only view over Claude Code installed plugins and
 * known marketplaces. Nothing mutates here (honest partial — install/disable
 * land with the marketplace engine).
 */
@Composable
fun PluginsScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        PluginsViewModel(context.applicationContext as android.app.Application)
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
                Text(stringResource(R.string.plugins_title), style = MaterialTheme.typography.headlineMedium)
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
            Text(stringResource(R.string.plugins_installed), style = MaterialTheme.typography.titleMedium)
        }
        if (state.plugins.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.plugins_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.plugins, key = { p -> p.name + p.marketplace }) { plugin ->
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${plugin.name} @ ${plugin.marketplace}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.plugins_version, plugin.version) +
                            (if (plugin.enabled) "" else " · " + stringResource(R.string.plugins_disabled)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (plugin.enabled) R.string.plugins_enabled else R.string.plugins_disabled),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        androidx.compose.material3.Switch(
                            checked = plugin.enabled,
                            onCheckedChange = { viewModel.setEnabled(plugin.pluginId, it) },
                        )
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.plugins_marketplaces),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(state.marketplaces, key = { m -> m.name }) { market ->
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(market.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        market.repo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.plugins_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
