package com.awd.teledrive.ui.screens.home

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.theme.TeledriveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val totalStorageUsed by viewModel.totalStorageUsed.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val currentFolderId by viewModel.currentFolderId.collectAsState()
    val currentFolderName by viewModel.currentFolderName.collectAsState()
    val currentVirtualFolderId by viewModel.currentVirtualFolderId.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val connectivityStatus by viewModel.connectivityStatus.collectAsState()

    HomeContent(
        items = items,
        totalStorageUsed = totalStorageUsed,
        searchQuery = searchQuery,
        sortOrder = sortOrder,
        filterType = filterType,
        isRefreshing = isRefreshing,
        isInitialLoading = isInitialLoading,
        currentFolderId = currentFolderId,
        currentVirtualFolderId = currentVirtualFolderId,
        currentFolderName = currentFolderName,
        isGridView = isGridView,
        connectivityStatus = connectivityStatus,
        onNavigateToTransfers = onNavigateToTransfers,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToPreview = onNavigateToPreview,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onToggleViewMode = viewModel::toggleViewMode,
        onSetSortOrder = viewModel::setSortOrder,
        onSetFilterType = viewModel::setFilterType,
        onCreateFolder = viewModel::createFolder,
        isPasswordSet = viewModel.isPasswordSet(),
        onUploadFile = viewModel::uploadFile,
        onDeleteItems = viewModel::deleteItems,
        onMoveItems = viewModel::moveItems,
        onMoveFolderContents = viewModel::moveFolderContentsAndDelete,
        onDownloadFile = viewModel::downloadFile,
        onDownloadFolderContents = viewModel::downloadFolderContents,
        onToggleStarred = viewModel::toggleStarred,
        onNavigateToFolder = { chatId, vId, name -> viewModel.navigateToFolder(chatId, vId, name) },
        onNavigateBack = viewModel::navigateBack,
        onRefresh = viewModel::fetchItems,
        onRenameItem = viewModel::renameItem,
        onGetInviteLink = viewModel::getFolderInviteLink,
        onGetMembers = viewModel::getFolderMembers,
        onShareFile = viewModel::shareFileToPhone,
        onLogout = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    items: List<DriveItem>,
    totalStorageUsed: Long,
    searchQuery: String,
    sortOrder: SortOrder,
    filterType: FilterType,
    isRefreshing: Boolean,
    isInitialLoading: Boolean,
    currentFolderId: Long?,
    currentVirtualFolderId: String,
    currentFolderName: String?,
    isGridView: Boolean,
    connectivityStatus: ConnectivityObserver.Status,
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onSetFilterType: (FilterType) -> Unit,
    onCreateFolder: (String, Boolean, Boolean) -> Unit,
    isPasswordSet: Boolean,
    onUploadFile: (String, String) -> Unit,
    onDeleteItems: (List<DriveItem>) -> Unit,
    onMoveItems: (Set<Long>, Long) -> Unit,
    onMoveFolderContents: (Long, Long) -> Unit,
    onDownloadFile: (Long, Long, String) -> Unit,
    onDownloadFolderContents: (Long) -> Unit,
    onToggleStarred: (DriveItem) -> Unit,
    onNavigateToFolder: (Long?, String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onRenameItem: (DriveItem, String) -> Unit,
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showNewSheet by remember { mutableStateOf(false) }

    val isOffline = connectivityStatus == ConnectivityObserver.Status.Unavailable || 
                    connectivityStatus == ConnectivityObserver.Status.Lost

    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()
    var showFolderDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<List<DriveItem>?>(null) }
    var itemToRename by remember { mutableStateOf<DriveItem?>(null) }
    var renameValue by remember { mutableStateOf("") }

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
                    itemToRename?.let { onRenameItem(it, renameValue) }
                    itemToRename = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text("Batal") }
            }
        )
    }
    var shareItem by remember { mutableStateOf<DriveItem?>(null) }
    var folderToMove by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var folderToDownload by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var largeFileToUpload by remember { mutableStateOf<Pair<File, String>?>(null) }
    var isPreparingFile by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        
        scope.launch {
            isPreparingFile = true
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
                        if (tempFile.length() > 2000 * 1024 * 1024L) {
                            largeFileToUpload = tempFile to fileName
                        } else {
                            onUploadFile(tempFile.absolutePath, fileName)
                        }
                    }
                }
            }
            isPreparingFile = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            scope.launch {
                isPreparingFile = true
                withContext(Dispatchers.IO) {
                    val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    withContext(Dispatchers.Main) {
                        onUploadFile(file.absolutePath, fileName)
                    }
                }
                isPreparingFile = false
            }
        }
    }

    val pressBackMsg = stringResource(R.string.press_back_again)
    var backPressedOnce by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showNewSheet -> showNewSheet = false
            isSearchActive -> isSearchActive = false
            isSelectionMode -> selectedItems = emptySet()
            (currentFolderId != null || currentVirtualFolderId != "0") -> onNavigateBack()
            else -> {
                if (backPressedOnce) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    backPressedOnce = true
                    Toast.makeText(context, pressBackMsg, Toast.LENGTH_SHORT).show()
                    scope.launch {
                        delay(2.seconds)
                        backPressedOnce = false
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { onDeleteItems(it) }
                    showDeleteConfirm = null
                    selectedItems = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (shareItem != null) {
        ShareDialog(
            item = shareItem!!,
            onDismiss = { shareItem = null },
            onGetInviteLink = onGetInviteLink,
            onGetMembers = onGetMembers,
            onShareFile = onShareFile
        )
    }

    if (folderToMove != null) {
        AlertDialog(
            onDismissRequest = { folderToMove = null },
            title = { Text(stringResource(R.string.folder_move_warning_title)) },
            text = { Text(stringResource(R.string.folder_move_warning_message, folderToMove?.name ?: "")) },
            confirmButton = {
                Button(onClick = {
                    folderToMove?.let { folder ->
                        onMoveFolderContents(folder.telegramChatId, currentFolderId ?: 0L)
                    }
                    folderToMove = null
                }) { Text(stringResource(R.string.move_contents)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToMove = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (folderToDownload != null) {
        AlertDialog(
            onDismissRequest = { folderToDownload = null },
            title = { Text(stringResource(R.string.folder_download_warning_title)) },
            text = { Text(stringResource(R.string.folder_download_warning_message, folderToDownload?.name ?: "")) },
            confirmButton = {
                Button(onClick = {
                    folderToDownload?.let { folder ->
                        onDownloadFolderContents(folder.telegramChatId)
                    }
                    folderToDownload = null
                }) { Text(stringResource(R.string.download_contents)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDownload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (largeFileToUpload != null) {
        AlertDialog(
            onDismissRequest = { largeFileToUpload = null },
            title = { Text(stringResource(R.string.large_file_title)) },
            text = { Text(stringResource(R.string.large_file_message, largeFileToUpload?.second ?: "")) },
            confirmButton = {
                Button(onClick = {
                    largeFileToUpload?.let { (file, name) ->
                        onUploadFile(file.absolutePath, name)
                    }
                    largeFileToUpload = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { largeFileToUpload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (isPreparingFile) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Menyiapkan File") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Sedang menyalin file ke memori sementara...")
                }
            },
            confirmButton = {}
        )
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(stringResource(R.string.create_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    val isInsidePhysicalFolder = currentFolderId != null
                    if (!isInsidePhysicalFolder) {
                        NewActionItem(Icons.Default.CreateNewFolder, stringResource(R.string.folder)) {
                            showNewSheet = false
                            showFolderDialog = true
                        }
                    }
                    NewActionItem(Icons.Default.FileUpload, stringResource(R.string.upload)) {
                        showNewSheet = false
                        multiFilePickerLauncher.launch("*/*")
                    }
                    NewActionItem(Icons.Default.CameraAlt, stringResource(R.string.camera)) {
                        showNewSheet = false
                        cameraLauncher.launch(null)
                    }
                }
            }
        }
    }

    var isVirtualFolder by remember { mutableStateOf(true) }
    var isSecureFolder by remember { mutableStateOf(false) }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(R.string.folder_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    val isAtRoot = currentFolderId == null && currentVirtualFolderId == "0"
                    
                    if (isAtRoot) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Secure Folder Option
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Switch(
                                    checked = isSecureFolder,
                                    onCheckedChange = { 
                                        if (isPasswordSet) {
                                            isSecureFolder = it
                                            if (it) isVirtualFolder = false
                                        } else if (it) {
                                            Toast.makeText(context, "Setel Master Password di Pengaturan terlebih dahulu", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    enabled = isPasswordSet
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Folder Aman (Terenkripsi)", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Grup Private terenkripsi. Membutuhkan Master Password.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!isSecureFolder) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Switch(
                                        checked = isVirtualFolder,
                                        onCheckedChange = { isVirtualFolder = it }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(if (isVirtualFolder) "Virtual Folder" else "Channel Folder", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (isVirtualFolder) "Sinkron antar device tanpa membuat channel baru" else "Membuat channel baru di Telegram",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Reset to virtual if not at root
                        isVirtualFolder = true
                        isSecureFolder = false
                        Text(
                            "Membuat folder virtual di dalam " + (currentFolderName ?: "folder ini"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName, isVirtualFolder, isSecureFolder)
                            newFolderName = ""
                            isSecureFolder = false
                            showFolderDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showMoveDialog) {
        val folders = items.filterIsInstance<DriveItem.Folder>()
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.root_storage)) },
                        leadingContent = { Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            val selectedFileIds = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>().map { it.id }
                            val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
                            
                            if (selectedFileIds.isNotEmpty()) {
                                onMoveItems(selectedFileIds.toSet(), 0L)
                            }
                            
                            selectedFolders.forEach { folder ->
                                onMoveFolderContents(folder.telegramChatId, 0L)
                            }
                            
                            selectedItems = emptySet()
                            showMoveDialog = false
                        }
                    )
                    HorizontalDivider()

                    if (folders.isEmpty()) {
                        Text(stringResource(R.string.no_folders_found), modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(folders) { folder ->
                                if (folder.telegramChatId != currentFolderId && !selectedItems.contains(folder.id)) {
                                    ListItem(
                                        headlineContent = { Text(folder.name) },
                                        leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                        modifier = Modifier.clickable {
                                            val selectedFileIds = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>().map { it.id }
                                            val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
                                            
                                            if (selectedFileIds.isNotEmpty()) {
                                                onMoveItems(selectedFileIds.toSet(), folder.telegramChatId)
                                            }
                                            
                                            selectedFolders.forEach { sf ->
                                                onMoveFolderContents(sf.telegramChatId, folder.telegramChatId)
                                            }

                                            selectedItems = emptySet()
                                            showMoveDialog = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = if (isSelectionMode) "selection" else if ((currentFolderId != null || currentVirtualFolderId != "0") && !isSearchActive) "folder" else "main",
                transitionSpec = {
                    (fadeIn() + slideInVertically { -it / 2 }).togetherWith(fadeOut() + slideOutVertically { -it / 2 })
                },
                label = "TopBarTransition"
            ) { state ->
                when (state) {
                    "selection" -> {
                        TopAppBar(
                            title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                            modifier = Modifier.statusBarsPadding(),
                            navigationIcon = {
                                IconButton(onClick = { selectedItems = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val files = items.filterIsInstance<DriveItem.File>()
                                        if (selectedItems.size >= files.size && files.isNotEmpty()) {
                                            selectedItems = emptySet()
                                        } else {
                                            selectedItems = files.map { it.id }.toSet()
                                        }
                                    }
                                ) {
                                    val files = items.filterIsInstance<DriveItem.File>()
                                    Icon(
                                        if (selectedItems.size >= files.size && files.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = stringResource(R.string.select_all)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        val selectedFiles = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>()
                                        val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
                                        
                                        selectedFiles.forEach { onDownloadFile(it.id, it.parentChatId, it.name) }
                                        selectedFolders.forEach { onDownloadFolderContents(it.telegramChatId) }
                                        
                                        selectedItems = emptySet()
                                        Toast.makeText(context, context.getString(R.string.starting_downloads, selectedFiles.size + selectedFolders.size), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                                }
                                val selectedContainsFolder = items.filter { it.id in selectedItems }.any { it is DriveItem.Folder }
                                if (!selectedContainsFolder) {
                                    IconButton(
                                        onClick = {
                                            if (isOffline) {
                                                Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                                return@IconButton
                                            }
                                            showMoveDialog = true
                                        }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.move))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        val itemsToDelete = items.filter { it.id in selectedItems }
                                        showDeleteConfirm = itemsToDelete
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        )
                    }
                    "folder" -> {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = currentFolderName ?: stringResource(R.string.my_drive),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { onNavigateBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { isSearchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_teledrive))
                                    }
                                    IconButton(onClick = { 
                                        onRefresh()
                                        Toast.makeText(context, context.getString(R.string.refreshing), Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    }
                                    IconButton(onClick = { onToggleViewMode() }) {
                                        Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.statusBarsPadding()
                            )
                            FilterSortRow(
                                filterType = filterType,
                                sortOrder = sortOrder,
                                onSetFilterType = onSetFilterType,
                                onSetSortOrder = onSetSortOrder,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                    "main" -> {
                        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable { isSearchActive = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    if (!isSearchActive) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = stringResource(R.string.search_teledrive),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = onNavigateToTransfers) {
                                            Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.transfers))
                                        }
                                        IconButton(onClick = { 
                                            onRefresh()
                                            Toast.makeText(context, context.getString(R.string.refreshing), Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                        }
                                        IconButton(onClick = { onToggleViewMode() }) {
                                            Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                                        }
                                    } else {
                                        IconButton(onClick = { isSearchActive = false }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = onSearchQueryChange,
                                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (searchQuery.isEmpty()) {
                                                Text(stringResource(R.string.search_teledrive), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { onSearchQueryChange("") }) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (!isSearchActive) {
                                Spacer(modifier = Modifier.height(12.dp))

                                FilterSortRow(
                                    filterType = filterType,
                                    sortOrder = sortOrder,
                                    onSetFilterType = onSetFilterType,
                                    onSetSortOrder = onSetSortOrder
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isOffline) {
                ExtendedFloatingActionButton(
                    onClick = { showNewSheet = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.new_label)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isInitialLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    if (isRefreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                
                    if (isOffline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.offline_msg), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (searchQuery.isEmpty()) Icons.Default.CloudQueue else Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchQuery.isEmpty()) stringResource(R.string.drive_empty) else stringResource(R.string.no_results),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Ketuk tombol '+' untuk menambahkan file",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    DriveGridItem(
                                        item = item,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isSelectionMode) {
                                                val hasFiles = items.filter { it.id in selectedItems }.any { it is DriveItem.File }
                                                if (hasFiles && item is DriveItem.Folder) {
                                                    Toast.makeText(context, "Cannot select folders when files are selected", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                                }
                                            } else {
                                                if (item is DriveItem.File) onNavigateToPreview(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(if (item.isVirtual) currentFolderId else item.telegramChatId, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) {
                                                onDownloadFile(item.id, item.parentChatId, item.name)
                                            } else if (item is DriveItem.Folder) {
                                                folderToDownload = item
                                            }
                                        },
                                        onMoveClick = {
                                            selectedItems = setOf(item.id)
                                            showMoveDialog = true
                                        },
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = {
                                            showDeleteConfirm = listOf(item)
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(vertical = 8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    DriveListItem(
                                        item = item,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isSelectionMode) {
                                                val hasFiles = items.filter { it.id in selectedItems }.any { it is DriveItem.File }
                                                if (hasFiles && item is DriveItem.Folder) {
                                                    Toast.makeText(context, "Cannot select folders when files are selected", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                                }
                                            } else {
                                                if (item is DriveItem.File) onNavigateToPreview(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(if (item.isVirtual) currentFolderId else item.telegramChatId, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) {
                                                onDownloadFile(item.id, item.parentChatId, item.name)
                                            } else if (item is DriveItem.Folder) {
                                                folderToDownload = item
                                            }
                                        },
                                        onMoveClick = {
                                            selectedItems = setOf(item.id)
                                            showMoveDialog = true
                                        },
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = {
                                            showDeleteConfirm = listOf(item)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePreview() {
    TeledriveTheme {
        HomeContent(
            items = listOf(
                DriveItem.Folder(id = 1L, parentChatId = 0L, name = "Documents", telegramChatId = 123456L, isStarred = false),
                DriveItem.File(id = 2L, parentChatId = 0L, name = "image.jpg", size = 1024 * 1024, mimeType = "image/jpeg", telegramFileId = 1, thumbnailPath = null, localPath = null, isStarred = false)
            ),
            totalStorageUsed = 1024 * 1024,
            searchQuery = "",
            sortOrder = SortOrder.NAME,
            filterType = FilterType.ALL,
            isRefreshing = false,
            isInitialLoading = false,
            currentFolderId = null,
            currentVirtualFolderId = "0",
            currentFolderName = null,
            isGridView = false,
            connectivityStatus = ConnectivityObserver.Status.Available,
            onNavigateToTransfers = {},
            onNavigateToSettings = {},
            onNavigateToPreview = { _ -> },
            onSearchQueryChange = { _ -> },
            onToggleViewMode = {},
            onSetSortOrder = { _ -> },
            onSetFilterType = { _ -> },
            onCreateFolder = { _, _, _ -> },
            isPasswordSet = true,
            onUploadFile = { _, _ -> },
            onDeleteItems = { _ -> },
            onMoveItems = { _, _ -> },
            onMoveFolderContents = { _, _ -> },
            onDownloadFile = { _, _, _ -> },
            onDownloadFolderContents = { _ -> },
            onToggleStarred = { _ -> },
            onNavigateToFolder = { _, _, _ -> },
            onNavigateBack = {},
            onRefresh = {},
            onRenameItem = { _, _ -> },
            onGetInviteLink = { _, _ -> },
            onGetMembers = { _, _ -> },
            onShareFile = { _, _, _ -> },
            onLogout = {}
        )
    }
}



@Composable
fun NewActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FilterSortRow(
    filterType: FilterType,
    sortOrder: SortOrder,
    onSetFilterType: (FilterType) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            val categories = FilterType.entries
            items(categories) { category ->
                FilterChip(
                    selected = filterType == category,
                    onClick = { onSetFilterType(category) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    label = {
                        Text(
                            when (category) {
                                FilterType.ALL -> stringResource(R.string.filter_all)
                                FilterType.PHOTOS -> stringResource(R.string.filter_photos)
                                FilterType.VIDEOS -> stringResource(R.string.filter_videos)
                                FilterType.AUDIO -> stringResource(R.string.filter_audio)
                                FilterType.DOCUMENTS -> stringResource(R.string.filter_documents)
                            }
                        )
                    },
                    leadingIcon = if (filterType == category) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    } else null
                )
            }
        }

        var showSortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_name)) },
                    onClick = { onSetSortOrder(SortOrder.NAME); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_date)) },
                    onClick = { onSetSortOrder(SortOrder.DATE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_size)) },
                    onClick = { onSetSortOrder(SortOrder.SIZE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.SIZE) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveListItem(
    item: DriveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)

    ListItem(
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isSecure) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        },
        supportingContent = {
            if (item is DriveItem.File) {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.folder), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            val (icon, color) = getFileIconAndColor(item)
            Surface(
                color = if (item is DriveItem.Folder) MaterialTheme.colorScheme.primaryContainer else color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.localPath ?: item.thumbnailPath
                    } else null

                    if (thumbnailModel != null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(24.dp)
                            )
                            AsyncImage(
                                model = thumbnailModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                var showItemMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showItemMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                
                var showInfoDialog by remember { mutableStateOf(false) }
                
                DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Bagikan") },
                        onClick = { 
                            onShareClick()
                            showItemMenu = false 
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )

                    DropdownMenuItem(
                        text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                        onClick = { 
                            onStarClick()
                            showItemMenu = false 
                        },
                        leadingIcon = { 
                            Icon(
                                imageVector = if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = null,
                                tint = if (item.isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                            ) 
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.info)) },
                        onClick = { showInfoDialog = true; showItemMenu = false },
                        leadingIcon = { Icon(Icons.Default.Info, null) }
                    )

                    onRenameClick?.let {
                        DropdownMenuItem(
                            text = { Text("Ubah Nama") },
                            onClick = {
                                it()
                                showItemMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }

                    if (item is DriveItem.File) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download)) },
                            onClick = { 
                                onDownloadClick()
                                showItemMenu = false 
                            },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    } else if (item is DriveItem.Folder) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_contents)) },
                            onClick = { 
                                onDownloadClick()
                                showItemMenu = false 
                            },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    }

                    if (item !is DriveItem.Folder) {
                        onMoveClick?.let {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move)) },
                                onClick = {
                                    it()
                                    showItemMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }
                            )
                        }
                    }

                    onDeleteClick?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                it()
                                showItemMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
                
                if (showInfoDialog) {
                    InfoDialog(item = item, onDismiss = { showInfoDialog = false })
                }
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveGridItem(
    item: DriveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    var showItemMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val (icon, color) = getFileIconAndColor(item)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                        .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                ) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.localPath ?: item.thumbnailPath
                    } else null

                    if (thumbnailModel != null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = color
                            )
                            AsyncImage(
                                model = thumbnailModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().background(Color.Transparent),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = color
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSecure) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp).padding(horizontal = 2.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    Box {
                        IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Bagikan") },
                                onClick = { onShareClick(); showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                                onClick = { onStarClick(); showItemMenu = false },
                                leadingIcon = { 
                                    Icon(
                                        imageVector = if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                                        contentDescription = null,
                                        tint = if (item.isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.info)) },
                                onClick = { showInfoDialog = true; showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            onRenameClick?.let {
                                DropdownMenuItem(
                                    text = { Text("Ubah Nama") },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                            }
                            if (item is DriveItem.File) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download)) },
                                    onClick = { onDownloadClick(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Download, null) }
                                )
                            } else if (item is DriveItem.Folder) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_contents)) },
                                    onClick = { onDownloadClick(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Download, null) }
                                )
                            }
                            if (item !is DriveItem.Folder) {
                                onMoveClick?.let {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move)) },
                                        onClick = { it(); showItemMenu = false },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }
                                    )
                                }
                            }
                            onDeleteClick?.let {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                }
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    }
    
    if (showInfoDialog) {
        InfoDialog(item = item, onDismiss = { showInfoDialog = false })
    }
}

@Composable
fun InfoDialog(item: DriveItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.name_label, item.name), style = MaterialTheme.typography.bodyMedium)
                if (item is DriveItem.File) {
                    Text(stringResource(R.string.size_label, formatSize(item.size)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.type_label, item.mimeType), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.status_label, 
                            if (item.localPath != null) stringResource(R.string.available_offline) else stringResource(R.string.cloud_only)
                        ), 
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val folderTypeStr = if (item is DriveItem.Folder && item.isVirtual) {
                        stringResource(R.string.virtual_folder)
                    } else {
                        stringResource(R.string.channel_folder)
                    }
                    Text(stringResource(R.string.type_label, folderTypeStr), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ShareDialog(
    item: DriveItem,
    onDismiss: () -> Unit,
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var inviteLink by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<TdApi.User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var shareToPhone by remember { mutableStateOf("") }
    var isSharingFile by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        if (item is DriveItem.Folder) {
            onGetInviteLink(item.telegramChatId) { link ->
                inviteLink = link
                onGetMembers(item.telegramChatId) { userList ->
                    members = userList
                    isLoading = false
                }
            }
        } else {
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item is DriveItem.Folder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text("Berbagi ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (item is DriveItem.Folder) {
                    if (isLoading) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Text("Link Berbagi Private:", style = MaterialTheme.typography.labelLarge)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    inviteLink ?: "Gagal membuat link",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    inviteLink?.let {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Link Folder", it))
                                        Toast.makeText(context, "Link disalin", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Copy")
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text("Orang yang memiliki akses:", style = MaterialTheme.typography.labelLarge)
                        if (members.isEmpty()) {
                            Text("Belum ada yang bergabung", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        } else {
                            LazyColumn(Modifier.heightIn(max = 150.dp)) {
                                items(members) { user ->
                                    ListItem(
                                        headlineContent = { Text("${user.firstName} ${user.lastName}") },
                                        supportingContent = { Text(user.phoneNumber.ifEmpty { "@${user.usernames?.activeUsernames?.firstOrNull() ?: "no_username"}" }) },
                                        leadingContent = { Icon(Icons.Default.Person, null) }
                                    )
                                }
                            }
                        }
                    }
                } else if (item is DriveItem.File) {
                    Text("Berbagi ke nomor Telegram:", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = shareToPhone,
                        onValueChange = { shareToPhone = it },
                        placeholder = { Text("+62...") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )
                    
                    if (isSharingFile) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (item is DriveItem.File) {
                Button(
                    onClick = {
                        if (shareToPhone.isNotBlank()) {
                            isSharingFile = true
                            onShareFile(shareToPhone, item.id) { success, error ->
                                isSharingFile = false
                                if (success) {
                                    Toast.makeText(context, "File berhasil dibagikan", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Gagal: $error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isSharingFile && shareToPhone.isNotBlank()
                ) {
                    Text("Kirim")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Selesai") }
        }
    )
}

@Composable
private fun getFileIconAndColor(item: DriveItem): Pair<ImageVector, Color> {
    if (item is DriveItem.Folder) {
        return when {
            item.isSecure -> Icons.Default.Folder to Color(0xFF2196F3) // Blue for secure
            item.isVirtual -> Icons.Default.Folder to MaterialTheme.colorScheme.tertiary
            else -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
        }
    } else {
        val file = item as DriveItem.File
        return when {
            file.mimeType.startsWith("image/") -> Icons.Default.Image to Color(0xFF4CAF50)
            file.mimeType.startsWith("video/") -> Icons.Default.VideoFile to Color(0xFFE91E63)
            file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFFFF9800)
            file.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
            file.mimeType.contains("zip") || file.mimeType.contains("rar") || file.mimeType.contains("7z") -> Icons.Default.FolderZip to Color(0xFF9C27B0)
            file.mimeType.contains("text/") -> Icons.AutoMirrored.Filled.Article to Color(0xFF607D8B)
            file.mimeType.contains("android.package-archive") -> Icons.Default.Android to Color(0xFF3DDC84)
            else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
