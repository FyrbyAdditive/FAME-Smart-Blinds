package com.fyrbyadditive.famesmartblinds.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fyrbyadditive.famesmartblinds.R
import com.fyrbyadditive.famesmartblinds.ui.screens.*
import com.fyrbyadditive.famesmartblinds.viewmodel.CalibrationViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceConfigurationViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceControlViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.DeviceListViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.LogsViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.MQTTConfigurationViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.PasswordConfigurationViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.SettingsViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.SetupViewModel
import com.fyrbyadditive.famesmartblinds.viewmodel.WiFiConfigurationViewModel

sealed class Screen(val route: String) {
    data object DeviceList : Screen("devices")
    data object Setup : Screen("setup")
    data object DeviceControl : Screen("device/{deviceId}") {
        fun createRoute(deviceId: String) = "device/$deviceId"
    }
    data object Calibration : Screen("calibration/{deviceId}") {
        fun createRoute(deviceId: String) = "calibration/$deviceId"
    }
    data object Settings : Screen("settings/{deviceId}") {
        fun createRoute(deviceId: String) = "settings/$deviceId"
    }
    data object Logs : Screen("logs/{deviceId}") {
        fun createRoute(deviceId: String) = "logs/$deviceId"
    }
    data object Configuration : Screen("configuration/{deviceId}") {
        fun createRoute(deviceId: String) = "configuration/$deviceId"
    }
    data object WiFiConfiguration : Screen("wifi-configuration/{deviceId}") {
        fun createRoute(deviceId: String) = "wifi-configuration/$deviceId"
    }
    data object MQTTConfiguration : Screen("mqtt-configuration/{deviceId}") {
        fun createRoute(deviceId: String) = "mqtt-configuration/$deviceId"
    }
    data object PasswordConfiguration : Screen("password-configuration/{deviceId}") {
        fun createRoute(deviceId: String) = "password-configuration/$deviceId"
    }
}

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int
) {
    data object Devices : BottomNavItem(
        route = Screen.DeviceList.route,
        icon = Icons.Outlined.Settings, // We'll use a blinds icon
        labelRes = R.string.nav_devices
    )
    data object Setup : BottomNavItem(
        route = Screen.Setup.route,
        icon = Icons.Default.Add,
        labelRes = R.string.nav_setup
    )
}

@Composable
fun FAMESmartBlindsNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val deviceListViewModel: DeviceListViewModel = hiltViewModel()

    // Start/stop discovery based on lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> deviceListViewModel.startDiscovery()
                Lifecycle.Event.ON_STOP -> deviceListViewModel.stopDiscovery()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val bottomNavItems = listOf(BottomNavItem.Devices, BottomNavItem.Setup)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom nav only on main screens
    val showBottomNav = currentDestination?.route in listOf(
        Screen.DeviceList.route,
        Screen.Setup.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = {
                                if (item == BottomNavItem.Devices) {
                                    // Use a blinds icon
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_blinds),
                                        contentDescription = null
                                    )
                                } else {
                                    Icon(item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(item.labelRes)) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.DeviceList.route,
            modifier = modifier.padding(innerPadding)
        ) {
            composable(Screen.DeviceList.route) {
                DeviceListScreen(
                    viewModel = deviceListViewModel,
                    onDeviceClick = { device ->
                        navController.navigate(Screen.DeviceControl.createRoute(device.deviceId))
                    }
                )
            }

            composable(Screen.Setup.route) {
                val setupViewModel: SetupViewModel = hiltViewModel()
                SetupScreen(
                    viewModel = setupViewModel,
                    onDismiss = {
                        navController.navigate(Screen.DeviceList.route) {
                            popUpTo(Screen.DeviceList.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Screen.DeviceControl.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val controlViewModel: DeviceControlViewModel = hiltViewModel()

                // Force SSE reconnect when returning from settings/calibration
                // The device may have restarted (firmware update, restart from settings)
                val savedStateHandle = backStackEntry.savedStateHandle
                val shouldReconnect = savedStateHandle.get<Boolean>("forceSSEReconnect") ?: false
                if (shouldReconnect) {
                    savedStateHandle["forceSSEReconnect"] = false
                    LaunchedEffect(Unit) {
                        controlViewModel.forceSSEReconnect()
                    }
                }

                DeviceControlScreen(
                    viewModel = controlViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToCalibration = { deviceId ->
                        // Set flag to force SSE reconnect when returning
                        savedStateHandle["forceSSEReconnect"] = true
                        navController.navigate(Screen.Calibration.createRoute(deviceId))
                    },
                    onNavigateToSettings = { deviceId ->
                        // Set flag to force SSE reconnect when returning
                        savedStateHandle["forceSSEReconnect"] = true
                        navController.navigate(Screen.Settings.createRoute(deviceId))
                    }
                )
            }

            composable(
                route = Screen.Calibration.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val calibrationViewModel: CalibrationViewModel = hiltViewModel()
                CalibrationScreen(
                    viewModel = calibrationViewModel,
                    onDismiss = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Settings.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.createRoute(deviceId)) },
                    onNavigateToWiFiConfiguration = { navController.navigate(Screen.WiFiConfiguration.createRoute(deviceId)) },
                    onNavigateToMQTTConfiguration = { navController.navigate(Screen.MQTTConfiguration.createRoute(deviceId)) },
                    onNavigateToPasswordConfiguration = { navController.navigate(Screen.PasswordConfiguration.createRoute(deviceId)) }
                )
            }

            composable(
                route = Screen.Configuration.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val configViewModel: DeviceConfigurationViewModel = hiltViewModel()
                DeviceConfigurationScreen(
                    viewModel = configViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.WiFiConfiguration.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val wifiViewModel: WiFiConfigurationViewModel = hiltViewModel()
                WiFiConfigurationScreen(
                    viewModel = wifiViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.MQTTConfiguration.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val mqttViewModel: MQTTConfigurationViewModel = hiltViewModel()
                MQTTConfigurationScreen(
                    viewModel = mqttViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.PasswordConfiguration.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val passwordViewModel: PasswordConfigurationViewModel = hiltViewModel()
                PasswordConfigurationScreen(
                    viewModel = passwordViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Logs.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) {
                val logsViewModel: LogsViewModel = hiltViewModel()
                LogsScreen(
                    viewModel = logsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
