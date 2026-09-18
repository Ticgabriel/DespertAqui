package com.destino.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.destino.app.feature.alarms.AlarmEditorScreen
import com.destino.app.feature.alarms.AlarmEditorViewModel
import com.destino.app.feature.alarms.AlarmListScreen
import com.destino.app.feature.alarms.AlarmListViewModel
import com.destino.app.feature.history.HistoryScreen
import com.destino.app.feature.history.HistoryViewModel
import com.destino.app.feature.home.HomeScreen
import com.destino.app.feature.home.HomeViewModel
import com.destino.app.feature.journey.AlertDisplayScreen
import com.destino.app.feature.journey.JourneyViewModel
import com.destino.app.feature.journey.JourneyScreen
import com.destino.app.feature.journey.RestModeScreen
import com.destino.app.feature.library.LocationLibraryScreen
import com.destino.app.feature.library.LocationLibraryViewModel
import com.destino.app.feature.settings.SettingsScreen
import com.destino.app.feature.settings.SettingsViewModel
import com.destino.app.feature.settings.audio.AudioSettingsScreen
import com.destino.app.feature.settings.audio.AudioSettingsViewModel
import com.destino.app.feature.settings.monitoring.MonitoringSettingsScreen
import com.destino.app.feature.settings.monitoring.MonitoringSettingsViewModel
import com.destino.app.feature.settings.permissions.PermissionViewModel
import com.destino.app.feature.settings.permissions.PermissionsScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Home : Screen("home", "Início", Icons.Default.Home)
    data object Alarms : Screen("alarms", "Alarmes", Icons.Default.Notifications)
    data object Library : Screen("library", "Locais", Icons.Default.Place)
    data object Settings : Screen("settings", "Ajustes", Icons.Default.Settings)

    data object AlarmEditor : Screen("alarm_editor?alarmId={alarmId}&destinationName={destinationName}&lat={lat}&lon={lon}&radius={radius}", "Editor de Alarme") {
        fun createRoute(
            alarmId: String? = null,
            destinationName: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            radius: Double? = null
        ): String {
            val params = buildList {
                alarmId?.let { add("alarmId=${Uri.encode(it)}") }
                destinationName?.let { add("destinationName=${Uri.encode(it)}") }
                latitude?.let { add("lat=$it") }
                longitude?.let { add("lon=$it") }
                radius?.let { add("radius=$it") }
            }
            return if (params.isEmpty()) "alarm_editor" else "alarm_editor?${params.joinToString("&")}" 
        }
    }
    data object Journey : Screen("journey/{sessionId}", "Acompanhando") {
        fun createRoute(sessionId: String) = "journey/$sessionId"
    }
    data object RestMode : Screen("rest_mode/{sessionId}", "Modo Descanso") {
        fun createRoute(sessionId: String) = "rest_mode/$sessionId"
    }
    data object Alert : Screen("alert?sessionId={sessionId}", "Alerta") {
        fun createRoute(sessionId: String = "") = if (sessionId.isBlank()) "alert" else "alert?sessionId=$sessionId"
    }
    data object AudioSettings : Screen("settings/audio", "Som e Vibração")
    data object PermissionsSettings : Screen("settings/permissions", "Permissões")
    data object MonitoringSettings : Screen("settings/monitoring", "Monitoramento")
    data object HistorySettings : Screen("settings/history", "Histórico")
}

val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Alarms,
    Screen.Library,
    Screen.Settings
)

@Composable
fun DestinoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route,
    openAlertRequest: Int = 0,
    openAlertSessionId: String = ""
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val canvasColor = if (currentRoute == Screen.RestMode.route) com.destino.app.ui.theme.DestinoRestBackground else MaterialTheme.colorScheme.background
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = canvasColor.luminance() > 0.5f
                isAppearanceLightNavigationBars = canvasColor.luminance() > 0.5f
            }
        }
    }

    LaunchedEffect(openAlertRequest) {
        if (openAlertRequest > 0) {
            navController.navigate(Screen.Alert.createRoute(openAlertSessionId)) { launchSingleTop = true }
        }
    }

    val showBottomBar = bottomNavScreens.any { it.route == currentRoute }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    bottomNavScreens.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigateToTopLevel(screen)
                            },
                            icon = {
                                screen.icon?.let {
                                    Icon(it, contentDescription = screen.title)
                                }
                            },
                            label = { Text(screen.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = com.destino.app.ui.theme.DestinoSurfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        },
        containerColor = canvasColor
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)
        ) {
            // 1. Início
            composable(Screen.Home.route) {
                val homeViewModel: HomeViewModel = hiltViewModel()
                HomeScreen(
                    viewModel = homeViewModel,
                    onCreateAlarm = { navController.navigate(Screen.AlarmEditor.createRoute()) },
                    onOpenRestMode = { sessionId -> navController.navigate(Screen.Journey.createRoute(sessionId)) },
                    onPermissions = { navController.navigate(Screen.PermissionsSettings.route) },
                    onFavoriteChosen = { favorite ->
                        navController.navigate(Screen.AlarmEditor.createRoute(destinationName = favorite.nickname,
                            latitude = favorite.coordinates.latitude, longitude = favorite.coordinates.longitude, radius = favorite.suggestedRadiusMeters))
                    },
                    onNavigateToFavorites = { navController.navigateToTopLevel(Screen.Library) },
                    onNavigateToAlarms = { navController.navigateToTopLevel(Screen.Alarms) }
                )
            }

            // 2. Alarmes
            composable(Screen.Alarms.route) {
                val alarmListViewModel: AlarmListViewModel = hiltViewModel()
                AlarmListScreen(
                    viewModel = alarmListViewModel,
                    onCreateAlarm = { navController.navigate(Screen.AlarmEditor.createRoute()) },
                    onEditAlarm = { alarmId -> navController.navigate(Screen.AlarmEditor.createRoute(alarmId)) },
                    onNavigateToJourney = { sessionId -> navController.navigate(Screen.Journey.createRoute(sessionId)) },
                    onNavigateToHome = { navController.navigateToTopLevel(Screen.Home) }
                )
            }

            // 3. Locais
            composable(Screen.Library.route) {
                val libraryViewModel: LocationLibraryViewModel = hiltViewModel()
                LocationLibraryScreen(
                    viewModel = libraryViewModel,
                    onCreateRoutineWithDestination = { name, lat, lon, radius ->
                        navController.navigate(
                            Screen.AlarmEditor.createRoute(
                                destinationName = name,
                                latitude = lat,
                                longitude = lon,
                                radius = radius
                            )
                        )
                    },
                    onNavigateToHome = { navController.navigateToTopLevel(Screen.Home) }
                )
            }

            // 4. Ajustes
            composable(Screen.Settings.route) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToAudio = { navController.navigate(Screen.AudioSettings.route) },
                    onNavigateToPermissions = { navController.navigate(Screen.PermissionsSettings.route) },
                    onNavigateToMonitoring = { navController.navigate(Screen.MonitoringSettings.route) },
                    onNavigateToHistory = { navController.navigate(Screen.HistorySettings.route) },
                    onNavigateToHome = { navController.navigateToTopLevel(Screen.Home) }
                )
            }

            // Editor de alarme
            composable(
                route = Screen.AlarmEditor.route,
                arguments = listOf(
                    navArgument("alarmId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("destinationName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("lat") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("lon") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("radius") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                val editorViewModel: AlarmEditorViewModel = hiltViewModel()
                AlarmEditorScreen(
                    viewModel = editorViewModel,
                    onJourneyStarted = { sessionId ->
                        navController.popBackStack()
                        navController.navigate(Screen.Journey.createRoute(sessionId))
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onSaveCompleted = {
                        navController.popBackStack()
                        navController.navigateToTopLevel(Screen.Alarms)
                    }
                )
            }

            composable(Screen.Journey.route, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                val journeyViewModel: JourneyViewModel = hiltViewModel()
                JourneyScreen(journeyViewModel, onNewJourneyRequested = { navController.popBackStack() },
                    onOpenRestMode = { navController.navigate(Screen.RestMode.createRoute(entry.arguments?.getString("sessionId").orEmpty())) })
            }

            // Modo descanso
            composable(
                route = Screen.RestMode.route,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                    }
                )
            ) {
                val journeyViewModel: JourneyViewModel = hiltViewModel()
                RestModeScreen(
                    viewModel = journeyViewModel,
                    onExitRestMode = { navController.popBackStack() }
                )
            }

            // Tela de alerta
            composable(
                route = Screen.Alert.route,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) {
                val journeyViewModel: JourneyViewModel = hiltViewModel()
                AlertDisplayScreen(
                    viewModel = journeyViewModel,
                    onDismiss = { navController.popBackStack() }
                )
            }

            // Subtelas de ajustes
            composable(Screen.AudioSettings.route) {
                val audioViewModel: AudioSettingsViewModel = hiltViewModel()
                AudioSettingsScreen(
                    viewModel = audioViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.PermissionsSettings.route) {
                val permViewModel: PermissionViewModel = hiltViewModel()
                PermissionsScreen(
                    viewModel = permViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.MonitoringSettings.route) {
                val monViewModel: MonitoringSettingsViewModel = hiltViewModel()
                MonitoringSettingsScreen(
                    viewModel = monViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.HistorySettings.route) {
                val histViewModel: HistoryViewModel = hiltViewModel()
                HistoryScreen(
                    viewModel = histViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
