package com.awd.teledrive.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.ui.screens.home.HomeViewModel
import com.awd.teledrive.ui.screens.security.SecurityViewModel
import com.awd.teledrive.ui.theme.ThemeViewModel
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme

data class SettingsUiState(
    val isDarkMode: Boolean,
    val themeColor: Color,
    val isSecurityEnabled: Boolean,
    val isBiometricEnabled: Boolean,
    val totalStorageUsed: Long,
    val internalCacheSize: Long,
    val isAutoBackupEnabled: Boolean,
    val lastBackupTime: Long,
    val currentLanguage: String,
    val downloadUri: String?
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    themeViewModel: ThemeViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val isDarkMode by themeViewModel.isDarkMode.collectAsState()
    val themeColor by themeViewModel.themeColor.collectAsState()
    val isSecurityEnabled by securityViewModel.isSecurityEnabled.collectAsState()
    val isBiometricEnabled by securityViewModel.isBiometricEnabled.collectAsState()
    val totalStorageUsed by homeViewModel.totalStorageUsed.collectAsState()
    val internalCacheSize by viewModel.internalCacheSize.collectAsState()
    val isAutoBackupEnabled by backupViewModel.isAutoBackupEnabled.collectAsState()
    val lastBackupTime by backupViewModel.lastBackupTime.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val downloadUri by viewModel.downloadUri.collectAsState()

    val context = LocalContext.current

    val uiState = SettingsUiState(
        isDarkMode = isDarkMode ?: isSystemInDarkTheme(),
        themeColor = themeColor,
        isSecurityEnabled = isSecurityEnabled,
        isBiometricEnabled = isBiometricEnabled,
        totalStorageUsed = totalStorageUsed,
        internalCacheSize = internalCacheSize,
        isAutoBackupEnabled = isAutoBackupEnabled,
        lastBackupTime = lastBackupTime,
        currentLanguage = currentLanguage,
        downloadUri = downloadUri
    )

    SettingsContent(
        uiState = uiState,
        onBack = onBack,
        onLogout = {
            viewModel.logout()
            // Memberikan sedikit waktu bagi TDLib untuk mengirim request logout sebelum restart
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            (context as? android.app.Activity)?.finish()
        },
        onNavigateToBackupFolders = onNavigateToBackupFolders,
        onSetDarkMode = { themeViewModel.setDarkMode(it) },
        onSetThemeColor = { themeViewModel.setThemeColor(it) },
        onSetSecurityEnabled = { securityViewModel.setSecurityEnabled(it) },
        onSetBiometricEnabled = { securityViewModel.setBiometricEnabled(it) },
        onSetAutoBackupEnabled = { backupViewModel.setAutoBackupEnabled(it) },
        onTriggerManualBackup = { backupViewModel.triggerManualBackup() },
        onNavigateToLogs = onNavigateToLogs,
        onClearCache = viewModel::clearCache,
        onSetLanguage = viewModel::setLanguage,
        onSetDownloadUri = viewModel::setDownloadUri
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToBackupFolders: () -> Unit,
    onSetDarkMode: (Boolean) -> Unit,
    onSetThemeColor: (Color) -> Unit,
    onSetSecurityEnabled: (Boolean) -> Unit,
    onSetBiometricEnabled: (Boolean) -> Unit,
    onSetAutoBackupEnabled: (Boolean) -> Unit,
    onTriggerManualBackup: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onClearCache: () -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetDownloadUri: (String?) -> Unit
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSetPasswordConfirm by remember { mutableStateOf(false) }
    var showLanguageRestartConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showThemeRestartConfirm by remember { mutableStateOf(false) }

    val folderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            onSetDownloadUri(it.toString())
        }
    }

    if (showLanguageRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showLanguageRestartConfirm = false },
            title = { Text(stringResource(R.string.language_changed_title)) },
            text = { Text(stringResource(R.string.language_changed_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLanguageRestartConfirm = false
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) { Text(stringResource(R.string.restart_app)) }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageRestartConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showSetPasswordConfirm) {
        AlertDialog(
            onDismissRequest = { showSetPasswordConfirm = false },
            title = { Text(stringResource(R.string.set_password_title)) },
            text = { Text(stringResource(R.string.set_password_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSetPasswordConfirm = false
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showSetPasswordConfirm = false
                    onSetSecurityEnabled(false) 
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.clear_cache_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearCache()
                    showClearCacheConfirm = false
                    Toast.makeText(context, context.getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showThemeRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showThemeRestartConfirm = false },
            title = { Text(stringResource(R.string.theme_changed_title)) },
            text = { Text(stringResource(R.string.theme_changed_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showThemeRestartConfirm = false
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    (context as? android.app.Activity)?.finish()
                }) { Text(stringResource(R.string.restart_app)) }
            },
            dismissButton = {
                TextButton(onClick = { showThemeRestartConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("English") },
                        modifier = Modifier.clickable {
                            onSetLanguage("en")
                            showLanguageDialog = false
                            showLanguageRestartConfirm = true
                        },
                        trailingContent = {
                            if (uiState.currentLanguage == "en") Icon(Icons.Default.Check, null)
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Bahasa Indonesia") },
                        modifier = Modifier.clickable {
                            onSetLanguage("id")
                            showLanguageDialog = false
                            showLanguageRestartConfirm = true
                        },
                        trailingContent = {
                            if (uiState.currentLanguage == "id" || uiState.currentLanguage == "in") Icon(Icons.Default.Check, null)
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            SettingsCategory(title = stringResource(R.string.display))
            SettingsToggleRow(
                icon = Icons.Default.DarkMode,
                title = stringResource(R.string.theme_dark),
                checked = uiState.isDarkMode,
                onCheckedChange = { 
                    onSetDarkMode(it)
                    showThemeRestartConfirm = true
                }
            )
            
            Text(
                text = stringResource(R.string.theme_color),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val colors = listOf(
                    Color(0xFF24A1DE), // Telegram Blue
                    Color(0xFF673AB7), // Deep Purple
                    Color(0xFF3F51B5), // Indigo
                    Color(0xFF2196F3), // Blue
                    Color(0xFF00BCD4), // Cyan
                    Color(0xFF009688), // Teal
                    Color(0xFF4CAF50), // Green
                    Color(0xFF8BC34A), // Light Green
                    Color(0xFFFFC107), // Amber
                    Color(0xFFFF9800), // Orange
                    Color(0xFFFF5722), // Deep Orange
                    Color(0xFFE91E63), // Pink
                    Color(0xFF9C27B0), // Purple
                    Color(0xFF607D8B), // Blue Grey
                )
                items(colors) { color ->
                    Surface(
                        onClick = { 
                            onSetThemeColor(color)
                            showThemeRestartConfirm = true
                        },
                        color = color,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                        border = if (uiState.themeColor == color) 
                            BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                        tonalElevation = 4.dp
                    ) {
                        if (uiState.themeColor == color) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            SettingsClickableRow(
                icon = Icons.Default.Language,
                title = stringResource(R.string.language),
                subtitle = if (uiState.currentLanguage == "in" || uiState.currentLanguage == "id") "Bahasa Indonesia" else "English",
                onClick = { showLanguageDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.security))
            SettingsToggleRow(
                icon = Icons.Default.VpnKey,
                title = stringResource(R.string.master_password),
                subtitle = stringResource(R.string.require_password),
                checked = uiState.isSecurityEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !uiState.isSecurityEnabled) {
                        showSetPasswordConfirm = true
                    }
                    onSetSecurityEnabled(enabled)
                }
            )
            SettingsToggleRow(
                icon = Icons.Default.Fingerprint,
                title = stringResource(R.string.biometric_unlock),
                checked = uiState.isBiometricEnabled,
                enabled = uiState.isSecurityEnabled,
                onCheckedChange = onSetBiometricEnabled
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.backup_settings))
            SettingsClickableRow(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.backup_folders),
                subtitle = stringResource(R.string.backup_folders_desc),
                onClick = onNavigateToBackupFolders
            )
            if (uiState.lastBackupTime > 0) {
                val date = Date(uiState.lastBackupTime)
                val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                SettingsRow(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.last_backup, format.format(date))
                )
            }
            SettingsClickableRow(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.backup_now),
                onClick = onTriggerManualBackup
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.downloads))
            
            val downloadPath = if (uiState.downloadUri.isNullOrEmpty()) {
                stringResource(R.string.download_location_desc)
            } else {
                android.net.Uri.parse(uiState.downloadUri).path?.substringAfterLast(':') ?: uiState.downloadUri
            }
            
            SettingsClickableRow(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.download_location),
                subtitle = downloadPath,
                onClick = { 
                    folderLauncher.launch(null)
                }
            )
            if (!uiState.downloadUri.isNullOrEmpty()) {
                TextButton(
                    onClick = { onSetDownloadUri(null) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text("Reset to Default (/Downloads)")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.system))
            SettingsClickableRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.app_logs),
                subtitle = stringResource(R.string.app_logs_desc),
                onClick = onNavigateToLogs
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.storage))
            SettingsRow(
                icon = Icons.Default.Cloud,
                title = stringResource(R.string.cloud_usage),
                subtitle = stringResource(R.string.cloud_usage_subtitle, formatSize(uiState.totalStorageUsed))
            )
            SettingsClickableRow(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.internal_cache),
                subtitle = stringResource(R.string.internal_cache_subtitle, formatSize(uiState.internalCacheSize)),
                onClick = {
                    showClearCacheConfirm = true
                }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            ListItem(
                headlineContent = { 
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) 
                    }
                },
                modifier = Modifier.clickable { showLogoutConfirm = true }
            )
            
            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreview() {
    TeledriveTheme {
        SettingsContent(
            uiState = SettingsUiState(
                isDarkMode = false,
                themeColor = Color(0xFF24A1DE),
                isSecurityEnabled = true,
                isBiometricEnabled = true,
                totalStorageUsed = 1024 * 1024 * 100,
                internalCacheSize = 1024 * 1024 * 50,
                isAutoBackupEnabled = true,
                lastBackupTime = System.currentTimeMillis(),
                currentLanguage = "en",
                downloadUri = null
            ),
            onBack = {},
            onLogout = {},
            onNavigateToBackupFolders = {},
            onSetDarkMode = {},
            onSetThemeColor = {},
            onSetSecurityEnabled = {},
            onSetBiometricEnabled = {},
            onSetAutoBackupEnabled = {},
            onTriggerManualBackup = {},
            onNavigateToLogs = {},
            onClearCache = {},
            onSetLanguage = {},
            onSetDownloadUri = {}
        )
    }
}

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    )
}

@Composable
fun SettingsClickableRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
