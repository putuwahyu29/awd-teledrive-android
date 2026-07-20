package com.awd.teledrive.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheDetailsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val stats by viewModel.cacheStatistics.collectAsState()
    var showConfirmDialog by remember { mutableStateOf<TdApi.FileType?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshCacheStatistics()
    }

    if (showConfirmDialog != null || showClearAllConfirm) {
        val isMediaAndFiles = showConfirmDialog is TdApi.FileTypeNone && !showClearAllConfirm
        
        val categoryName = when {
            showClearAllConfirm -> stringResource(R.string.clear_all_cache)
            showConfirmDialog is TdApi.FileTypeThumbnail -> stringResource(R.string.category_thumbnails)
            isMediaAndFiles -> stringResource(R.string.category_media_files)
            else -> ""
        }

        AlertDialog(
            onDismissRequest = { 
                showConfirmDialog = null 
                showClearAllConfirm = false
            },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(if (showClearAllConfirm) stringResource(R.string.clear_cache_confirm_message) else stringResource(R.string.clear_category, categoryName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClearing = true
                        if (showClearAllConfirm) {
                            viewModel.clearCacheByType(null)
                        } else if (isMediaAndFiles) {
                            viewModel.clearMediaAndFilesCache()
                        } else {
                            viewModel.clearCacheByType(showConfirmDialog)
                        }
                        showConfirmDialog = null
                        showClearAllConfirm = false
                        
                        scope.launch {
                            delay(2000) // Small buffer for disk IO
                            isClearing = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showConfirmDialog = null 
                    showClearAllConfirm = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.total_cache_size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatSize(stats.totalSize),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider()
            
            Text(
                text = stringResource(R.string.temporary_files),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )

            CacheCategoryItem(
                icon = Icons.Default.PhotoLibrary,
                label = stringResource(R.string.category_thumbnails),
                subtitle = stringResource(R.string.category_thumbnails_desc),
                size = stats.thumbnails,
                onClear = { showConfirmDialog = TdApi.FileTypeThumbnail() }
            )
            
            CacheCategoryItem(
                icon = Icons.Default.VideoFile,
                label = stringResource(R.string.category_media_files),
                subtitle = stringResource(R.string.category_media_files_desc),
                size = stats.mediaAndFiles,
                onClear = { showConfirmDialog = TdApi.FileTypeNone() } // None + logic flag = media & files
            )

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            
            Text(
                text = stringResource(R.string.data_apps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )
            
            CacheCategoryItem(
                icon = Icons.Default.Storage,
                label = stringResource(R.string.category_database),
                subtitle = stringResource(R.string.category_database_desc),
                size = stats.database,
                onClear = null
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            Button(
                onClick = { showClearAllConfirm = true },
                enabled = !isClearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFFEF5350) else Color(0xFFD32F2F),
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clearing_cache))
                } else {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_all_cache))
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun CacheCategoryItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    size: Long,
    onClear: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { 
            Column {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                Text(formatSize(size), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (size > 0 && onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
