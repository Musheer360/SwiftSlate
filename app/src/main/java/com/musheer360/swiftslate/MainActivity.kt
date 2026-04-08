package com.musheer360.swiftslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musheer360.swiftslate.ui.CommandsScreen
import com.musheer360.swiftslate.ui.DashboardScreen
import com.musheer360.swiftslate.ui.KeysScreen
import com.musheer360.swiftslate.ui.SettingsScreen
import com.musheer360.swiftslate.ui.theme.SwiftSlateTheme

private val TAB_ORDER = mapOf("dashboard" to 0, "keys" to 1, "commands" to 2, "settings" to 3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwiftSlateTheme {
                SwiftSlateMainScreen()
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Keys : Screen("keys", "Keys", Icons.Default.Lock)
    object Commands : Screen("commands", "Commands", Icons.AutoMirrored.Filled.List)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun SwiftSlateMainScreen(vm: SwiftSlateViewModel = viewModel()) {
    val navController = rememberNavController()
    val items = listOf(Screen.Dashboard, Screen.Keys, Screen.Commands, Screen.Settings)
    val haptic = LocalHapticFeedback.current
    var lastNavTime by remember { mutableLongStateOf(0L) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title
                            )
                        },
                        label = null,
                        selected = currentRoute == screen.route,
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - lastNavTime > 150) {
                                lastNavTime = now
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                val from = TAB_ORDER[initialState.destination.route] ?: 0
                val to = TAB_ORDER[targetState.destination.route] ?: 0
                slideIntoContainer(
                    if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250, easing = FastOutSlowInEasing)
                )
            },
            exitTransition = {
                val from = TAB_ORDER[initialState.destination.route] ?: 0
                val to = TAB_ORDER[targetState.destination.route] ?: 0
                slideOutOfContainer(
                    if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250, easing = FastOutSlowInEasing)
                )
            },
            popEnterTransition = {
                val from = TAB_ORDER[initialState.destination.route] ?: 0
                val to = TAB_ORDER[targetState.destination.route] ?: 0
                slideIntoContainer(
                    if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250, easing = FastOutSlowInEasing)
                )
            },
            popExitTransition = {
                val from = TAB_ORDER[initialState.destination.route] ?: 0
                val to = TAB_ORDER[targetState.destination.route] ?: 0
                slideOutOfContainer(
                    if (to > from) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250, easing = FastOutSlowInEasing)
                )
            }
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(vm.keyManager, vm.commandManager) }
            composable(Screen.Keys.route) { KeysScreen(vm.keyManager, vm.prefs) }
            composable(Screen.Commands.route) { CommandsScreen(vm.commandManager) }
            composable(Screen.Settings.route) { SettingsScreen(vm.commandManager, vm.prefs) }
        }
    }
}
