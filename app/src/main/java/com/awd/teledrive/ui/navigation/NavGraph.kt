package com.awd.teledrive.ui.navigation

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
import com.awd.teledrive.ui.screens.settings.AboutScreen
import com.awd.teledrive.ui.screens.settings.BackupSettingsScreen
import com.awd.teledrive.ui.screens.settings.CacheDetailsScreen
import com.awd.teledrive.ui.screens.settings.CloudAnalysisScreen
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
    object About : Screen("about")
    object CacheDetails : Screen("cache_details")
    object CloudAnalysis : Screen("cloud_analysis")
    object Logs : Screen("logs")
    object BackupSettings : Screen("backup_settings")
    object SecureStorage : Screen("secure_storage")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavGraph(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    val securityViewModel: SecurityViewModel = hiltViewModel()
    val loginViewModel: com.awd.teledrive.ui.screens.auth.LoginViewModel = hiltViewModel()
    val settingsViewModel: com.awd.teledrive.ui.screens.settings.SettingsViewModel = hiltViewModel()
    
    val isLocked by securityViewModel.isLocked.collectAsState()
    val loginUiState by loginViewModel.uiState.collectAsState()
    val isSecureActive by securityViewModel.isSecureModeActive.collectAsState(false)
    val showCacheWarning by settingsViewModel.showCacheWarning.collectAsState()
    
    val savedMessagesId by hiltViewModel<com.awd.teledrive.ui.screens.home.HomeViewModel>().totalStorageUsed.collectAsState() // Just to trigger something? No.
    // Let's just use a better way to check if ready.

    var showUnlockDialog by remember { mutableStateOf(false) }

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
    val shouldShowBottomBar = currentRoute in bottomNavScreens.map { it.route } && currentRoute != Screen.SecureStorage.route

    Scaffold(
        bottomBar = {
            if (shouldShowBottomBar) {
                val context = LocalContext.current
                val activity = context as? FragmentActivity
                val executor = remember { ContextCompat.getMainExecutor(context) }
                
                val biometricPrompt = remember {
                    if (activity != null) {
                        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                if (securityViewModel.unlockWithBiometric()) {
                                    navController.navigate(Screen.SecureStorage.route)
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.password_unsynced_toast), android.widget.Toast.LENGTH_LONG).show()
                                    showUnlockDialog = true
                                }
                            }
                        })
                    } else null
                }

                val promptInfo = remember {
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(context.getString(R.string.unlock_secure_folder))
                        .setSubtitle(context.getString(R.string.biometric_subtitle))
                        .setNegativeButtonText(context.getString(R.string.use_password))
                        .build()
                }

                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        val isHome = screen == Screen.Home
                        NavigationBarItem(
                            icon = { 
                                Box(
                                    modifier = if (isHome) {
                                        Modifier.combinedClickable(
                                            onClick = {
                                                if (currentRoute != screen.route) {
                                                    navController.navigate(screen.route) {
                                                        popUpTo(Screen.Home.route) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                val isPasswordSet = securityViewModel.isPasswordSet.value
                                                if (!isPasswordSet) {
                                                    android.widget.Toast.makeText(navController.context, navController.context.getString(R.string.set_password_first), android.widget.Toast.LENGTH_LONG).show()
                                                    return@combinedClickable
                                                }

                                                if (isSecureActive) {
                                                    securityViewModel.lockSecureMode()
                                                    android.widget.Toast.makeText(navController.context, navController.context.getString(R.string.secure_mode_disabled), android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val isBiometricEnabled = securityViewModel.isBiometricEnabled.value
                                                    if (isBiometricEnabled && biometricPrompt != null) {
                                                        try {
                                                            biometricPrompt.authenticate(promptInfo)
                                                        } catch (e: Exception) {
                                                            showUnlockDialog = true
                                                        }
                                                    } else {
                                                        showUnlockDialog = true
                                                    }
                                                }
                                            }
                                        )
                                    } else Modifier
                                ) {
                                    Icon(screen.icon!!, contentDescription = null) 
                                }
                            },
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
            startDestination = Screen.Login.route, // Default, will be redirected by LaunchedEffects if needed
            modifier = Modifier.padding(
                bottom = if (shouldShowBottomBar) innerPadding.calculateBottomPadding() else 0.dp
            )
        ) {
            composable(Screen.Security.route) {
                MasterPasswordScreen(
                    onSuccess = {
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
                    onNavigateToCacheDetails = { navController.navigate(Screen.CacheDetails.route) },
                    onNavigateToCloudAnalysis = { navController.navigate(Screen.CloudAnalysis.route) },
                    onNavigateToAbout = { navController.navigate(Screen.About.route) }
                )
            }
            composable(Screen.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CacheDetails.route) {
                CacheDetailsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CloudAnalysis.route) {
                CloudAnalysisScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.BackupSettings.route) {
                BackupSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Logs.route) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SecureStorage.route) {
                com.awd.teledrive.ui.screens.security.SecureStorageScreen(
                    onBack = { 
                        navController.popBackStack()
                        securityViewModel.lockSecureMode()
                    },
                    onNavigateToPreview = { file ->
                        navController.navigate(Screen.Preview.createRoute(file.parentChatId, file.id))
                    },
                    onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) }
                )
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

    if (showUnlockDialog) {
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val context = navController.context
        
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            title = { Text(stringResource(R.string.unlock_secure_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.unlock_secure_desc))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text(stringResource(R.string.password)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (securityViewModel.unlockSecureMode(password)) {
                        showUnlockDialog = false
                        navController.navigate(Screen.SecureStorage.route)
                    } else {
                        error = context.getString(R.string.incorrect_password)
                    }
                }) { Text(stringResource(R.string.unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showCacheWarning) {
        AlertDialog(
            onDismissRequest = { settingsViewModel.dismissCacheWarning() },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.cache_limit_reached_message)) },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.clearCache()
                    settingsViewModel.dismissCacheWarning()
                }) { Text(stringResource(R.string.clear_now), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { settingsViewModel.dismissCacheWarning() }) { Text(stringResource(R.string.later)) }
            }
        )
    }
}
