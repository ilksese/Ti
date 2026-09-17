package app.ti.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.ti.AppContainer

@Composable
fun TiApp(container: AppContainer) {
    MaterialTheme(colorScheme = TiColors) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val roots = listOf(
            Root("chat", "Chat", Icons.Default.Chat),
            Root("repos", "Repositories", Icons.Default.Folder),
            Root("providers", "Providers", Icons.Default.Cloud),
            Root("settings", "Settings", Icons.Default.Settings),
        )
        Scaffold(
            bottomBar = {
                if (route in roots.map { it.route }) {
                    NavigationBar {
                        roots.forEach { root ->
                            NavigationBarItem(
                                selected = route == root.route,
                                onClick = {
                                    nav.navigate(root.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(root.icon, contentDescription = root.label) },
                                label = { Text(root.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val fade = tween<Float>(durationMillis = 120)
            NavHost(
                nav,
                startDestination = "repos",
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(fade) },
                exitTransition = { fadeOut(fade) },
                popEnterTransition = { fadeIn(fade) },
                popExitTransition = { fadeOut(fade) },
            ) {
                composable("chat") { ChatScreen(container, nav) }
                composable("repos") { RepositoriesScreen(container, nav) }
                composable("repo/{id}") { backStack ->
                    RepositoryScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("session/{id}") { backStack ->
                    SessionScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("providers") { ProvidersScreen(container, nav) }
                composable("provider/{id}") { backStack ->
                    ProviderScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("settings") { SettingsScreen(nav) }
                composable("settings/ai") { AiSettingsScreen(container, nav) }
                composable("settings/git") { GitSettingsScreen(container, nav) }
            }
        }
    }
}

private data class Root(
    val route: String,
    val label: String,
    val icon: ImageVector,
)
