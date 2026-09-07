package com.tiquasar.podsteroid.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tiquasar.podsteroid.ui.screens.home.HomeScreen
import com.tiquasar.podsteroid.ui.screens.settings.SettingsScreen
import com.tiquasar.podsteroid.ui.screens.setup.SetupScreen
import com.tiquasar.podsteroid.ui.screens.terminal.TerminalScreen
import com.tiquasar.podsteroid.ui.screens.terminal.TerminalViewModel
import com.tiquasar.podsteroid.ui.screens.backup.ContainerBackupScreen
import com.tiquasar.podsteroid.ui.screens.cluster.ClusterScreen
import com.tiquasar.podsteroid.ui.screens.observability.ObservabilityScreen
import com.tiquasar.podsteroid.ui.screens.servers.ServersScreen
import com.tiquasar.podsteroid.ui.screens.fleet.FleetScreen
import com.tiquasar.podsteroid.ui.screens.status.StatusScreen
import com.tiquasar.podsteroid.ui.screens.x11.X11Screen
import com.tiquasar.podsteroid.ui.components.BiometricGate
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

object Routes {
    const val SETUP         = "setup"
    const val HOME          = "home"
    const val TERMINAL      = "terminal"
    const val TERMINAL_X11  = "terminal/x11"
    const val SETTINGS      = "settings"
    const val STATUS        = "status"
    const val CONTAINER_BACKUP = "container_backup"
    const val CLUSTER       = "cluster"
    const val OBSERVABILITY = "observability"
    const val SERVERS       = "servers"
    const val FLEET         = "fleet"
}

@Composable
fun PodsteroidNavGraph(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController = rememberNavController(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    // Read isSetupDone from a Hilt-scoped helper so MainActivity doesn't need
    // a field-injected SettingsRepository just to drive the start destination.
    val isSetupDone by hiltViewModel<NavGraphViewModel>()
        .isSetupDone
        .collectAsStateWithLifecycle(initialValue = null)

    // Scoped to PodsteroidNavGraph composable — survives all navigation including popUpTo(0)
    val terminalViewModel: TerminalViewModel = hiltViewModel()

    val startDestination = when (isSetupDone) {
        true  -> Routes.HOME
        false -> Routes.SETUP
        null  -> return
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(contentPadding),
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                windowSizeClass = windowSizeClass,
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                windowSizeClass = windowSizeClass,
                onNavigateToTerminal = {
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onNavigateToStatus = {
                    navController.navigate(Routes.STATUS) { launchSingleTop = true }
                },
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
                onNavigateToCluster = {
                    navController.navigate(Routes.CLUSTER) { launchSingleTop = true }
                },
                onNavigateToObservability = {
                    navController.navigate(Routes.OBSERVABILITY) { launchSingleTop = true }
                },
                onNavigateToServers = {
                    navController.navigate(Routes.SERVERS) { launchSingleTop = true }
                },
                onNavigateToFleet = {
                    navController.navigate(Routes.FLEET) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.STATUS) {
            StatusScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.STATUS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.TERMINAL) {
            BiometricGate {
                TerminalScreen(
                    windowSizeClass = windowSizeClass,
                    viewModel = terminalViewModel,
                    onNavigateBack = {
                        // Only pop if we're not already at HOME to avoid the warning
                        if (navController.currentDestination?.route == Routes.TERMINAL) {
                            navController.popBackStack()
                        } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    onNavigateToX11 = {
                        navController.navigate(Routes.TERMINAL_X11) { launchSingleTop = true }
                    },
                )
            }
        }

        composable(Routes.TERMINAL_X11) {
            BiometricGate {
                X11Screen(
                    onNavigateBack = {
                        if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                            navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                        }
                    },
                    onNavigateToTerminal = {
                        if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                            navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                        }
                    },
                )
            }
        }

        composable(Routes.CONTAINER_BACKUP) {
            ContainerBackupScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.CONTAINER_BACKUP) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.CLUSTER) {
            ClusterScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.CLUSTER) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.OBSERVABILITY) {
            ObservabilityScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.OBSERVABILITY) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.FLEET) {
            FleetScreen(
                viewModel = hiltViewModel(),
                windowSizeClass = windowSizeClass,
                onBack = {
                    if (navController.currentDestination?.route == Routes.FLEET) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            val activity = LocalActivity.current
            val onLanguageChanged = remember(activity) {
                { activity?.recreate() ?: Unit }
            }
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onLanguageChanged = onLanguageChanged,
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.SERVERS) {
            ServersScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.SERVERS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}

/** Top-level destinations shown in the bottom navigation (compact) / rail
 *  (expanded). Everything else is reached from these or via in-screen actions. */
private val TOP_NAV = mapOf(
    Routes.HOME to TopNavItem("Home", Icons.Default.Home),
    Routes.SERVERS to TopNavItem("Servers", Icons.Default.DesktopWindows),
    Routes.FLEET to TopNavItem("Fleet", Icons.Default.Cloud),
    Routes.STATUS to TopNavItem("Status", Icons.Default.MonitorHeart),
    Routes.SETTINGS to TopNavItem("Settings", Icons.Default.Settings),
)

private data class TopNavItem(val label: String, val icon: ImageVector)

private val TOP_ROUTES = listOf(Routes.HOME, Routes.SERVERS, Routes.FLEET, Routes.STATUS, Routes.SETTINGS)

/** Routes that own the full screen (setup wizard, terminal, cluster dialog).
 *  The bottom bar / navigation rail is hidden there so it never overlaps content. */
private val FULLSCREEN_ROUTES = setOf(Routes.SETUP, Routes.TERMINAL, Routes.TERMINAL_X11, Routes.CLUSTER)

/**
 * Modern app shell: a bottom navigation bar on compact widths and a navigation
 * rail on expanded widths, wrapping the existing [PodsteroidNavGraph]. The bar
 * is shown on every normal screen and only hidden on the immersive
 * [FULLSCREEN_ROUTES] (setup, terminal, cluster, …).
 */
@Composable
fun PodsteroidApp(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val route = navBackStackEntry?.destination?.route
    val expanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val showBar = route != null && route !in FULLSCREEN_ROUTES

    Row(Modifier.fillMaxSize()) {
        if (expanded && showBar) {
            Column(Modifier.fillMaxHeight()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(PodsteroidTokens.brandBrush()),
                )
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    TOP_ROUTES.forEach { r ->
                        val item = TOP_NAV[r] ?: return@forEach
                        NavigationRailItem(
                            selected = route == r,
                            onClick = { navigateTop(navController, r) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!expanded && showBar) {
                    Column {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(PodsteroidTokens.brandBrush()),
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            TOP_ROUTES.forEach { r ->
                                val item = TOP_NAV[r] ?: return@forEach
                                NavigationBarItem(
                                    selected = route == r,
                                    onClick = { navigateTop(navController, r) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            PodsteroidNavGraph(
                windowSizeClass = windowSizeClass,
                navController = navController,
                contentPadding = innerPadding,
            )
        }
    }
}

private fun navigateTop(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
