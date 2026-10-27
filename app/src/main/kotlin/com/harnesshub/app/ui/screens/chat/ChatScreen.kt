package com.harnesshub.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R
import com.harnesshub.app.chat.ChatViewModel
import com.harnesshub.model.ContentBlock

/**
 * Chat (M4): Hermes adapter via the shared ChatReducer — token streaming,
 * thinking lines, tool cards, approvals and recovery states all render from
 * the unified block model. Message bodies are plain text for now; the
 * purpose-built markdown renderer lands at polish (honest partial).
 */
@Composable
fun ChatScreen(onOpenSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val viewModel = remember { ChatViewModel(context.applicationContext as android.app.Application) }
    val ui by viewModel.ui.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.lastIndex)
    }
    LaunchedEffect(Unit) { viewModel.start() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            ui.error == "hermes_not_configured" -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.chat_unconfigured),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenSetup) {
                        Text(stringResource(R.string.chat_open_setup))
                    }
                }
            }
            ui.error != null -> Text(
                stringResource(R.string.chat_error_prefix, ui.error ?: ""),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
            ui.connectionLost -> Text(
                stringResource(R.string.chat_reconnecting),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {}
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(ui.messages, key = { _, m -> m.id.value }) { _, message ->
                MessageCard(message)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 5,
            )
            if (ui.busy) {
                IconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: com.harnesshub.model.Message) {
    Surface(
        tonalElevation = if (message.role == "assistant") 1.dp else 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            message.blocks.forEach { block -> BlockView(block) }
        }
    }
}

@Composable
private fun BlockView(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> Text(block.text)
        is ContentBlock.Thinking -> Text(
            "… ${block.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ContentBlock.ToolCall -> {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "tool ${block.name} · ${block.status}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (block.argsJson.isNotBlank()) {
                        Text(
                            block.argsJson.take(1200),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    block.result?.let { res ->
                        Text(
                            res.output.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (res.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is ContentBlock.SubAgent -> Text("↳ ${block.task}", style = MaterialTheme.typography.labelMedium)
        is ContentBlock.Image -> Text("🖼 ${block.uri}", style = MaterialTheme.typography.bodySmall)
        is ContentBlock.FileContent, is ContentBlock.Artifact -> {
            val name = when (block) {
                is ContentBlock.FileContent -> block.name
                is ContentBlock.Artifact -> block.name
                else -> ""
            }
            Text("📎 $name", style = MaterialTheme.typography.bodySmall)
        }
        is ContentBlock.Diff -> Text(
            "diff ${block.path}\n${block.hunks.take(1200)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        is ContentBlock.Error -> Text(
            block.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        is ContentBlock.Other -> Text(
            "[raw] ${block.tag}: ${block.json.take(600)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
