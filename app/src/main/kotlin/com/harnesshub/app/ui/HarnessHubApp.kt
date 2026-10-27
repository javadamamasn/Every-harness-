package com.harnesshub.app.ui

import androidx.compose.runtime.Composable
import com.harnesshub.app.ui.theme.HarnessHubTheme

/** Root composable — navigation shell for the whole app. */
@Composable
fun HarnessHubApp() {
    HarnessHubTheme {
        HarnessHubNavHost()
    }
}
