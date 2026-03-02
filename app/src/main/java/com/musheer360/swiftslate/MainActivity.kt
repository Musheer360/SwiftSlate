package com.musheer360.swiftslate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musheer360.swiftslate.ui.CommandsScreen
import com.musheer360.swiftslate.ui.DashboardScreen
import com.musheer360.swiftslate.ui.KeysScreen
import com.musheer360.swiftslate.ui.SettingsScreen
import com.musheer360.swiftslate.ui.theme.SwiftSlateTheme
import com.musheer360.swiftslate.update.UpdateWorker

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoute = routeFromIntent(intent)
        setContent {
            SwiftSlateTheme {
                SwiftSlateMainScreen(startRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = routeFromIntent(intent)
        if (route != null) {
            setContent {
                SwiftSlateTheme {
                    SwiftSlateMainScreen(route)
                }
            }
        }
    }

    private fun routeFromIntent(intent: Intent?): String? {
        val nav = intent?.getStringExtra(UpdateWorker.EXTRA_NAVIGATE_TO)
        return if (nav == UpdateWorker.NAV_SETTINGS) Screen.Settings.route else null
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Keys : Screen("keys", "Keys", Icons.Default.Key)
    object Commands : Screen("commands", "Commands", Icons.AutoMirrored.Filled.List)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun SwiftSlateMainScreen(startRoute: String? = null) {
    val navController = rememberNavController()
    val items = listOf(Screen.Dashboard, Screen.Keys, Screen.Commands, Screen.Settings)
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            navController.navigate(startRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Keys.route) { KeysScreen() }
            composable(Screen.Commands.route) { CommandsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}