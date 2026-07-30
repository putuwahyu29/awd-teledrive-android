package com.awd.teledrive.ui.screens.security

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.home.DriveListItem
import com.awd.teledrive.ui.screens.home.NewActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureStorageScreen(
    onBack: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onNavigateToTransfers: () -> Unit,
    viewModel: SecureStorageViewModel = hiltViewModel()
) {
    val items by viewModel.secureItems.collectAsState()
    val currentFolderName by viewModel.currentFolderName.collectAsState()
    val currentFolderId by viewModel.currentFolderId.collectAsState()
    val currentVirtualFolderId by viewModel.currentVirtualFolderId.collectAsState()
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showNewSheet by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    
    var itemToRename by remember { mutableStateOf<DriveItem?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<List<DriveItem>?>(null) }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val fileName = cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        c.moveToFirst()
                        c.getString(nameIndex)
                    } ?: "file_${System.currentTimeMillis()}"

                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, fileName)
                    inputStream?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.uploadFile(tempFile.absolutePath, fileName)
                    }
                }
            }
        }
    }

    if (itemToRename != null) {
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Ubah Nama") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("Nama Baru") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemToRename?.let { viewModel.renameItem(it, renameValue) }
                    itemToRename = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text("Batal") }
            }
        )
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { viewModel.deleteItems(it) }
                    showDeleteConfirm = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName, isVirtual = true, isSecure = true)
                            newFolderName = ""
                            showFolderDialog = false
                        }
                    }
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp, start = 16.dp, end = 16.dp)) {
                Text(stringResource(R.string.create_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    val isAtRoot = currentVirtualFolderId == "0"
                    
                    if (isAtRoot) {
                        NewActionItem(Icons.Default.CreateNewFolder, stringResource(R.string.folder)) {
                            showNewSheet = false
                            showFolderDialog = true
                        }
                    } else {
                        NewActionItem(Icons.Default.FileUpload, stringResource(R.string.upload)) {
                            showNewSheet = false
                            multiFilePickerLauncher.launch("*/*")
                        }
                        // Add Camera action if needed
                        NewActionItem(Icons.Default.CameraAlt, stringResource(R.string.camera)) {
                            showNewSheet = false
                            // Camera trigger could be implemented similar to HomeScreen
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(currentFolderId, currentVirtualFolderId) {
        viewModel.fetchItems()
    }

    BackHandler {
        if (currentFolderId != null || currentVirtualFolderId != "0") {
            viewModel.navigateBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFolderName ?: stringResource(R.string.secure_folder_label)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentFolderId != null || currentVirtualFolderId != "0") {
                            viewModel.navigateBack()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchItems() }) {
                        Icon(Icons.Default.Refresh, null)
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_label)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(8.dp)
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false, // TODO: Observe from ViewModel
            onRefresh = { viewModel.fetchItems() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Belum ada file di Folder Aman", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items) { item ->
                        DriveListItem(
                            item = item,
                            isSelected = false,
                            onClick = {
                                if (item is DriveItem.File) {
                                    onNavigateToPreview(item)
                                } else if (item is DriveItem.Folder) {
                                    viewModel.navigateToFolder(
                                        if (item.isVirtual) currentFolderId else item.telegramChatId,
                                        item.virtualId,
                                        item.name
                                    )
                                }
                            },
                            onLongClick = {},
                            onStarClick = { viewModel.toggleStarred(item) },
                            onShareClick = {},
                            onDownloadClick = {
                                if (item is DriveItem.File) {
                                    viewModel.downloadFile(item.id, item.parentChatId, item.name)
                                }
                            },
                            onRenameClick = {
                                itemToRename = item
                                renameValue = item.name
                            },
                            onDeleteClick = { showDeleteConfirm = listOf(item) }
                        )
                    }
                }
            }
        }
    }
}
