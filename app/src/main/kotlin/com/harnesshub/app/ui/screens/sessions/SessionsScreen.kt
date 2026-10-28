package com.harnesshub.app.ui.screens.sessions

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R
import com.harnesshub.app.chat.SessionOpen
import com.harnesshub.app.chat.SessionStore

/** Sessions list (Appendix: list / resume). Tapping resumes that engine + session. */
@Composable
fun SessionsScreen(onOpenChat: () -> Unit = {}) {
    val context = LocalContext.current
    val viewModel = remember { SessionsViewModel(context.applicationContext as android.app.Application) }
    val records by viewModel.records.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.nav_sessions), style = MaterialTheme.typography.headlineMedium)
        }
        if (records.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(records, key = { it.engine + it.sessionId }) { record ->
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(record.title.ifBlank { record.engine }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${record.engine} · ${record.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        viewModel.open(record.engine, record.sessionId, record.title)
                        onOpenChat()
                    }) { Text(stringResource(R.string.sessions_open)) }
                    TextButton(onClick = {
                        viewModel.delete(record.engine, record.sessionId)
                    }) { Text(stringResource(R.string.sessions_delete)) }
                }
            }
        }
    }
}
