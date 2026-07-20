package com.awd.teledrive.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.ui.theme.TeledriveTheme
import java.io.File

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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            
            // Try to extract path (this is a simplified version, works for most primary storage cases)
            val path = it.path?.let { p ->
                if (p.contains("primary:")) {
                    "/storage/emulated/0/${p.substringAfter("primary:")}"
                } else null
            }
            
            if (path != null) {
                viewModel.addCustomBackupFolder(path)
            } else {
                Toast.makeText(context, "Gagal mendapatkan path folder. Silakan gunakan folder standar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
        context.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val dataIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIndex)
                File(path).parent?.let { folders.add(it) }
            }
        }
        
        // Also add existing selected folders that might not be in MediaStore
        folders.addAll(selectedFolders)
        
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
        onTriggerBackup = {
            viewModel.triggerManualBackup()
            Toast.makeText(context, backupStartedMsg, Toast.LENGTH_SHORT).show()
        },
        onPickCustomFolder = { folderPickerLauncher.launch(null) }
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
    onTriggerBackup: () -> Unit,
    onPickCustomFolder: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_folders)) },
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
            if (isAutoBackupEnabled) {
                workInfo?.let { info ->
                    if (info.state == androidx.work.WorkInfo.State.RUNNING) {
                        val progress = info.progress.getFloat("progress", 0f)
                        val fileName = info.progress.getString("fileName") ?: ""
                        val current = info.progress.getInt("current", 0)
                        val total = info.progress.getInt("total", 0)
                        
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (total > 0) "Mencadangkan: $current dari $total" else "Menyiapkan...",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                            if (fileName.isNotEmpty()) {
                                Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.backup_folders_desc),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onPickCustomFolder,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tambah Folder", style = MaterialTheme.typography.labelMedium)
                }
            }
            
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
            onTriggerBackup = {},
            onPickCustomFolder = {}
        )
    }
}
