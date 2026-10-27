package com.harnesshub.app.ui.screens.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R

/**
 * Hub (home): one card per discovered harness instance. M1 ships the shell and
 * empty state; the discovery engine plugs its live list in at the discovery
 * milestone and this screen grows cards, rescan and start/stop actions.
 */
@Composable
fun HubScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.hub_title), style = MaterialTheme.typography.headlineMedium)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.hub_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.hub_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { /* wired to the discovery engine at the discovery milestone */ }) {
                Text(stringResource(R.string.hub_rescan))
            }
        }
    }
}
