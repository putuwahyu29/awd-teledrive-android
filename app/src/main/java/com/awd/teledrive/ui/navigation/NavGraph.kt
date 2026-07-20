package com.awd.teledrive.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.awd.teledrive.R
import com.awd.teledrive.ui.screens.auth.LoginScreen
import com.awd.teledrive.ui.screens.home.HomeScreen
import com.awd.teledrive.ui.screens.logs.LogsScreen
import com.awd.teledrive.ui.screens.media.MediaScreen
import com.awd.teledrive.ui.screens.preview.PdfViewerScreen
import com.awd.teledrive.ui.screens.preview.PreviewScreen
import com.awd.teledrive.ui.screens.preview.TextViewerScreen
import com.awd.teledrive.ui.screens.preview.VideoPlayerScreen
import com.awd.teledrive.ui.screens.security.MasterPasswordScreen
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.screens.settings.BackupSettingsScreen
import com.awd.teledrive.ui.screens.settings.CacheDetailsScreen
import com.awd.teledrive.ui.screens.settings.SettingsScreen
import com.awd.teledrive.ui.screens.starred.StarredScreen
import com.awd.teledrive.ui.screens.transfers.TransfersScreen
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String, val icon: ImageVector? = null, val labelRes: Int? = null) {
    object Security : Screen("security")
    object Login : Screen("login")
    object Home : Screen("home", Icons.Default.Home, R.string.home)
    object Starred : Screen("starred", Icons.Default.Star, R.string.starred)
    object Media : Screen("media", Icons.Default.PermMedia, R.string.media)
    object Transfers : Screen("transfers", Icons.Default.SwapVert, R.string.transfers)
    object Settings : Screen("settings", Icons.Default.Settings, R.string.settings)
    object CacheDetails : Screen("cache_details")
    object Logs : Screen("logs")
    object BackupSettings : Screen("backup_settings")
    object Preview : Screen("preview/{chatId}/{fileId}?isMediaOnly={isMediaOnly}") {
        fun createRoute(chatId: Long, fileId: Long, isMediaOnly: Boolean = false) = 
            "preview/$chatId/$fileId?isMediaOnly=$isMediaOnly"
    }
    object VideoPlayer : Screen("video_player/{path}") {
        fun createRoute(path: String) = "video_player/${URLEncoder.encode(path, "UTF-8")}"
    }
    object PdfViewer : Screen("pdf_viewer/{path}") {
        fun createRoute(path: String) = "pdf_viewer/${URLEncoder.encode(path, "UTF-8")}"
    }
    object TextViewer : Screen("text_viewer/{path}") {
        fun createRoute(path: String) = "text_viewer/${URLEncoder.encode(path, "UTF-8")}"
    }
}

@Composable
fun NavGraph(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    val securityViewModel: SecurityViewModel = hiltViewModel()
    val loginViewModel: com.awd.teledrive.ui.screens.auth.LoginViewModel = hiltViewModel()
    
    val isSecurityEnabled by securityViewModel.isSecurityEnabled.collectAsState()
    val isLocked by securityViewModel.isLocked.collectAsState()
    val loginUiState by loginViewModel.uiState.collectAsState()

    LaunchedEffect(isLocked) {
        if (isLocked && currentRoute != Screen.Security.route) {
            navController.navigate(Screen.Security.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Auto-navigate to Login if session is lost (Logged Out)
    LaunchedEffect(loginUiState, currentRoute) {
        if (currentRoute != null &&
            loginUiState !is com.awd.teledrive.ui.screens.auth.LoginUiState.Success &&
            loginUiState !is com.awd.teledrive.ui.screens.auth.LoginUiState.Loading &&
            currentRoute != Screen.Login.route && 
            currentRoute != Screen.Security.route) {
            
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val bottomNavScreens = listOf(Screen.Home, Screen.Media, Screen.Starred, Screen.Settings)
    val shouldShowBottomBar = currentRoute in bottomNavScreens.map { it.route }

    Scaffold(
        bottomBar = {
            if (shouldShowBottomBar) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon!!, contentDescription = null) },
                            label = { Text(stringResource(screen.labelRes!!)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
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
            startDestination = if (isSecurityEnabled) Screen.Security.route else Screen.Login.route,
            modifier = Modifier.padding(
                bottom = if (shouldShowBottomBar) innerPadding.calculateBottomPadding() else 0.dp
            )
        ) {
            composable(Screen.Security.route) {
                MasterPasswordScreen(
                    onSuccess = {
                        // Logic to return to Home after unlock
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Security.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToPreview = { file -> 
                        navController.navigate(Screen.Preview.createRoute(file.parentChatId, file.id))
                    }
                )
            }
            composable(Screen.Starred.route) {
                StarredScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToPreview = { file -> 
                        navController.navigate(Screen.Preview.createRoute(file.parentChatId, file.id))
                    }
                )
            }
            composable(Screen.Media.route) {
                MediaScreen(
                    onNavigateToPreview = { file -> 
                        navController.navigate(Screen.Preview.createRoute(file.parentChatId, file.id, isMediaOnly = true))
                    }
                )
            }
            composable(Screen.Transfers.route) {
                TransfersScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToBackupFolders = { navController.navigate(Screen.BackupSettings.route) },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToCacheDetails = { navController.navigate(Screen.CacheDetails.route) }
                )
            }
            composable(Screen.CacheDetails.route) {
                CacheDetailsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.BackupSettings.route) {
                BackupSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Logs.route) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.Preview.route,
                arguments = listOf(
                    navArgument("chatId") { type = NavType.LongType },
                    navArgument("fileId") { type = NavType.LongType },
                    navArgument("isMediaOnly") { type = NavType.BoolType; defaultValue = false }
                )
            ) {
                PreviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { path -> navController.navigate(Screen.VideoPlayer.createRoute(path)) },
                    onOpenPdf = { path -> navController.navigate(Screen.PdfViewer.createRoute(path)) },
                    onOpenText = { path -> navController.navigate(Screen.TextViewer.createRoute(path)) }
                )
            }
            composable(
                route = Screen.VideoPlayer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                VideoPlayerScreen(url = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.PdfViewer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                PdfViewerScreen(filePath = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.TextViewer.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                TextViewerScreen(filePath = path, onBack = { navController.popBackStack() })
            }
        }
    }
}
