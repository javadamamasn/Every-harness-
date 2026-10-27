package com.harnesshub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * First iteration of the design tokens: a calm, dark-leaning "mission control"
 * feel. Dedicated color roles and a larger sibling set of themes land with the
 * design-system milestone; for now the shell carries the accent + surfaces.
 */
private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF7C6FF0),
    secondary = androidx.compose.ui.graphics.Color(0xFF40A9B0),
    tertiary = androidx.compose.ui.graphics.Color(0xFFE0A356),
)
private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFA79BFF),
    secondary = androidx.compose.ui.graphics.Color(0xFF6FD0D6),
    tertiary = androidx.compose.ui.graphics.Color(0xFFF0BE7B),
)

@Composable
fun HarnessHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
