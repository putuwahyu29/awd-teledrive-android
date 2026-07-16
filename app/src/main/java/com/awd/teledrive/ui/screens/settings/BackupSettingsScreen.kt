package com.awd.teledrive.ui.screens.settings

import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.awd.teledrive.R
import java.io.File

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val selectedFolders by viewModel.backupFolders.collectAsState()
    val isAutoBackupEnabled by viewModel.isAutoBackupEnabled.collectAsState()
    val availableFolders = remember { mutableStateListOf<String>() }
    
    val workInfoList by viewModel.backupWorkInfo.collectAsState(initial = emptyList())
    val activeWorkInfo = workInfoList.find { it.state == androidx.work.WorkInfo.State.RUNNING } ?: workInfoList.firstOrNull()
    
    val backupStartedMsg = stringResource(R.string.backup_started)

    LaunchedEffect(Unit) {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIndex)
                File(path).parent?.let { folders.add(it) }
            }
        }
        availableFolders.clear()
        availableFolders.addAll(folders.sorted())
    }

    BackupSettingsContent(
        availableFolders = availableFolders,
        selectedFolders = selectedFolders,
        isAutoBackupEnabled = isAutoBackupEnabled,
        workInfo = activeWorkInfo,
        onBack = onBack,
        onToggleFolder = viewModel::toggleBackupFolder,
        onToggleAutoBackup = viewModel::setAutoBackupEnabled,
        onTriggerBackup = {
            viewModel.triggerManualBackup()
            Toast.makeText(context, backupStartedMsg, Toast.LENGTH_SHORT).show()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsContent(
    availableFolders: List<String>,
    selectedFolders: List<String>,
    isAutoBackupEnabled: Boolean,
    workInfo: androidx.work.WorkInfo?,
    onBack: () -> Unit,
    onToggleFolder: (String) -> Unit,
    onToggleAutoBackup: (Boolean) -> Unit,
    onTriggerBackup: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_backup)) },
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.auto_backup)) },
                supportingContent = { Text(stringResource(R.string.backup_description)) },
                trailingContent = {
                    Switch(
                        checked = isAutoBackupEnabled,
                        onCheckedChange = onToggleAutoBackup
                    )
                }
            )
            
            if (isAutoBackupEnabled) {
                workInfo?.let { info ->
                    if (info.state == androidx.work.WorkInfo.State.RUNNING) {
                        val progress = info.progress.getFloat("progress", 0f)
                        val fileName = info.progress.getString("fileName") ?: ""
                        
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.refreshing), style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                            if (fileName.isNotEmpty()) {
                                Text(fileName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.backup_folders_desc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(availableFolders) { folderPath ->
                    val isSelected = selectedFolders.contains(folderPath)
                    val folderName = folderPath.substringAfterLast('/')
                    
                    ListItem(
                        headlineContent = { Text(folderName) },
                        supportingContent = { Text(folderPath, maxLines = 1) },
                        leadingContent = { Icon(Icons.Default.Folder, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                        trailingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleFolder(folderPath) }
                            )
                        },
                        modifier = Modifier.clickable { onToggleFolder(folderPath) }
                    )
                    HorizontalDivider()
                }
            }
            
            if (isAutoBackupEnabled && selectedFolders.isNotEmpty()) {
                Button(
                    onClick = onTriggerBackup,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.backup_now))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BackupSettingsPreview() {
    TeledriveTheme {
        BackupSettingsContent(
            availableFolders = listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Pictures"),
            selectedFolders = listOf("/storage/emulated/0/DCIM"),
            isAutoBackupEnabled = true,
            workInfo = null,
            onBack = {},
            onToggleFolder = {},
            onToggleAutoBackup = {},
            onTriggerBackup = {}
        )
    }
}
