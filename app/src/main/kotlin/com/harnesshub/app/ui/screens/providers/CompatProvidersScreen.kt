package com.harnesshub.app.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * M8 screen: point the OpenAI-compatible adapter at any router (base URL +
 * key), probe /v1/models, pick a model. Config feeds the OPENAI chat engine.
 */
@Composable
fun CompatProvidersScreen() {
    val context = LocalContext.current
    val viewModel = remember {
        CompatProvidersViewModel(context.applicationContext as android.app.Application)
    }
    val state by viewModel.state.collectAsState()
    var keyDraft by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.compat_title), style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { viewModel.edit(baseUrl = it) },
                    label = { Text(stringResource(R.string.compat_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = {
                        keyDraft = it
                        viewModel.edit(apiKey = it)
                    },
                    label = {
                        Text(
                            if (state.apiKeyPresent) stringResource(R.string.compat_key_set)
                            else stringResource(R.string.compat_field_key),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { viewModel.probe() },
                        enabled = !state.busy && state.baseUrl.isNotBlank(),
                    ) { Text(stringResource(R.string.compat_probe)) }
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = state.baseUrl.isNotBlank() && state.model.isNotBlank(),
                    ) { Text(stringResource(R.string.hub_save)) }
                }
            }
        }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.probeDone && state.models.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.compat_model_label, state.model),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.models) { id ->
                Surface(
                    tonalElevation = if (id == state.model) 6.dp else 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.edit(model = id) },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (id == state.model) {
                            Text(
                                stringResource(R.string.compat_active),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.compat_partial_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
