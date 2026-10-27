package com.harnesshub.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R

/**
 * Chat: the full token-streaming experience arrives with the Hermes adapter
 * milestone; the shell is reachable now so navigation, theming and the empty
 * state are exercised end to end.
 */
@Composable
fun ChatScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.nav_chat), style = MaterialTheme.typography.headlineMedium)
    }
}
