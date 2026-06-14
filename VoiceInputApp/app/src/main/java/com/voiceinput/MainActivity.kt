package com.voiceinput

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voiceinput.data.AppRoutes
import com.voiceinput.ui.screens.HistoryScreen
import com.voiceinput.ui.screens.InputScreen
import com.voiceinput.ui.screens.AiAssistantScreen
import com.voiceinput.ui.screens.FileQrScannerScreen
import com.voiceinput.ui.screens.QrScannerScreen
import com.voiceinput.ui.screens.SettingsScreen
import com.voiceinput.ui.theme.VoiceInputTheme
import com.voiceinput.viewmodel.InputViewModel

class MainActivity : ComponentActivity() {
    private var initialRoute: String = "input"
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialRoute = routeFromIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            VoiceInputTheme {
                MainScreen(
                    initialRoute = initialRoute,
                    pendingRoute = pendingRoute,
                    onPendingRouteConsumed = { pendingRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = routeFromIntent(intent)
    }

    private fun routeFromIntent(intent: Intent?): String {
        return AppRoutes.routeForIntentAction(
            action = intent?.action,
            settingsSection = intent?.getStringExtra(EXTRA_SETTINGS_SECTION)
        )
    }

    companion object {
        const val ACTION_OPEN_SETTINGS = AppRoutes.ACTION_OPEN_SETTINGS
        const val EXTRA_SETTINGS_SECTION = AppRoutes.EXTRA_SETTINGS_SECTION
        const val SETTINGS_SECTION_DEVICES = AppRoutes.SETTINGS_SECTION_DEVICES
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialRoute: String = AppRoutes.INPUT,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val viewModel: InputViewModel = viewModel()

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
        onPendingRouteConsumed()
    }

    NavHost(
        navController = navController,
        startDestination = initialRoute
    ) {
        composable(AppRoutes.INPUT) {
            InputScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
                onNavigateToScanner = { navController.navigate("scanner") },
                onNavigateToFileScanner = { navController.navigate("file_scanner") },
                onNavigateToHistory = { navController.navigate(AppRoutes.HISTORY) },
                onNavigateToNotifications = { navController.navigate(AppRoutes.NOTIFICATIONS) },
                onNavigateToAi = { navController.navigate(AppRoutes.AI_ASSISTANT) }
            )
        }

        composable(AppRoutes.AI_ASSISTANT) {
            AiAssistantScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.HISTORY) {
            HistoryScreen(
                viewModel = viewModel,
                recordMode = "history",
                onBack = { navController.popBackStack() },
                onScanPair = { navController.navigate("scanner") },
                onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                onSwitchDevice = { navController.navigate(AppRoutes.SETTINGS_DEVICES) },
                onCheckServer = { navController.navigate(AppRoutes.SETTINGS) },
                onOpenAiAssistant = { navController.navigate(AppRoutes.AI_ASSISTANT) }
            )
        }

        composable(AppRoutes.NOTIFICATIONS) {
            HistoryScreen(
                viewModel = viewModel,
                recordMode = "notifications",
                onBack = { navController.popBackStack() },
                onScanPair = { navController.navigate("scanner") },
                onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                onSwitchDevice = { navController.navigate(AppRoutes.SETTINGS_DEVICES) },
                onCheckServer = { navController.navigate(AppRoutes.SETTINGS) },
                onOpenAiAssistant = { navController.navigate(AppRoutes.AI_ASSISTANT) }
            )
        }

        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.SETTINGS_DEVICES) {
            SettingsScreen(
                viewModel = viewModel,
                initialSection = AppRoutes.SETTINGS_SECTION_DEVICES,
                onBack = { navController.popBackStack() }
            )
        }

        composable("scanner") {
            QrScannerScreen(
                onScanResult = { serverUrl, deviceId, deviceName, localIp, localPort ->
                    viewModel.handleQrScanResult(serverUrl, deviceId, deviceName, localIp, localPort)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("file_scanner") {
            FileQrScannerScreen(
                onFileScanned = { fileName, mimeType, base64Data, size ->
                    viewModel.sendScannedFilePayload(fileName, mimeType, base64Data, size)
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
