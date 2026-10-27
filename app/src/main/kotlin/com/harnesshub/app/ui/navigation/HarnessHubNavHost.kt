package com.harnesshub.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.harnesshub.app.R
import com.harnesshub.app.ui.screens.chat.ChatScreen
import com.harnesshub.app.ui.screens.hub.HubScreen
import com.harnesshub.app.ui.screens.models.ModelsScreen
import com.harnesshub.app.ui.screens.sessions.SessionsScreen
import com.harnesshub.app.ui.screens.settings.SettingsScreen
import com.harnesshub.app.ui.screens.skills.SkillsScreen
import com.harnesshub.app.ui.screens.wizard.WizardScreen

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val topDestinations = listOf(
    TopDestination("hub", R.string.nav_hub, Icons.Filled.SpaceDashboard),
    TopDestination("chat", R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    TopDestination("sessions", R.string.nav_sessions, Icons.Filled.Memory),
    TopDestination("skills", R.string.nav_skills, Icons.Filled.Extension),
    TopDestination("models", R.string.nav_models, Icons.Filled.Tune),
    TopDestination("settings", R.string.nav_settings, Icons.Filled.Settings),
)

/** App shell: bottom nav for the phone; list-detail arrives with the adaptive-layout milestone. */
@Composable
fun HarnessHubNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                topDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "hub",
            modifier = Modifier.padding(padding),
        ) {
            composable("hub") { HubScreen() }
            composable("chat") { ChatScreen(onOpenSetup = { navController.navigate("wizard") }) }
            composable("sessions") { SessionsScreen() }
            composable("skills") { SkillsScreen() }
            composable("models") { ModelsScreen() }
            composable("settings") { SettingsScreen(onOpenWizard = { navController.navigate("wizard") }) }
            composable("wizard") { WizardScreen() }
        }
    }
}
