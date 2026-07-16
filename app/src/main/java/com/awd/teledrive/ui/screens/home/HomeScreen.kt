package com.awd.teledrive.ui.screens.home

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import com.awd.teledrive.ui.common.FileUiUtils
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme

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
        onUploadFile = viewModel::uploadFile,
        onDeleteItems = viewModel::deleteItems,
        onMoveItems = viewModel::moveItems,
        onMoveFolderContents = viewModel::moveFolderContentsAndDelete,
        onDownloadFile = viewModel::downloadFile,
        onDownloadFolderContents = viewModel::downloadFolderContents,
        onToggleStarred = viewModel::toggleStarred,
        onNavigateToFolder = viewModel::navigateToFolder,
        onNavigateBack = viewModel::navigateBack,
        onRefresh = viewModel::fetchItems,
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
    currentFolderName: String,
    isGridView: Boolean,
    connectivityStatus: ConnectivityObserver.Status,
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onSetFilterType: (FilterType) -> Unit,
    onCreateFolder: (String) -> Unit,
    onUploadFile: (String, String) -> Unit,
    onDeleteItems: (List<DriveItem>) -> Unit,
    onMoveItems: (Set<Long>, Long) -> Unit,
    onMoveFolderContents: (Long, Long) -> Unit,
    onDownloadFile: (Long, Long, String) -> Unit,
    onDownloadFolderContents: (Long) -> Unit,
    onToggleStarred: (DriveItem) -> Unit,
    onNavigateToFolder: (Long?, String) -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
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
    var folderToMove by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var folderToDownload by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
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
            onUploadFile(tempFile.absolutePath, fileName)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            val fileName = "IMG_${System.currentTimeMillis()}.jpg"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { out ->
                it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
            }
            onUploadFile(file.absolutePath, fileName)
        }
    }

    val pressBackMsg = stringResource(R.string.press_back_again)
    var backPressedOnce by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showNewSheet -> showNewSheet = false
            isSearchActive -> isSearchActive = false
            isSelectionMode -> selectedItems = emptySet()
            currentFolderId != null -> onNavigateBack()
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
                    if (currentFolderId == null) {
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
                            onCreateFolder(newFolderName)
                            newFolderName = ""
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
            if (isSelectionMode) {
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
            } else {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
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
                        
                        // Category Filter Row
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            val categories = FilterType.entries
                            items(categories) { category ->
                                FilterChip(
                                    selected = filterType == category,
                                    onClick = { onSetFilterType(category) },
                                    label = { 
                                        Text(
                                            when(category) {
                                                FilterType.ALL -> stringResource(R.string.filter_all)
                                                FilterType.PHOTOS -> stringResource(R.string.filter_photos)
                                                FilterType.VIDEOS -> stringResource(R.string.filter_videos)
                                                FilterType.AUDIO -> stringResource(R.string.filter_audio)
                                                FilterType.DOCUMENTS -> stringResource(R.string.filter_documents)
                                            }
                                        )
                                    },
                                    leadingIcon = if (filterType == category) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else null
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentFolderId != null) {
                                IconButton(onClick = { onNavigateBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text(
                                    text = currentFolderName,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                if (currentFolderId == null) {
                                    Text(
                                        text = stringResource(R.string.total_used, formatSize(totalStorageUsed)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            
                            var showSortMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
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
                            Text(
                                text = if (searchQuery.isEmpty()) stringResource(R.string.drive_empty) else stringResource(R.string.no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
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
                                                else if (item is DriveItem.Folder) onNavigateToFolder(item.telegramChatId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
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
                                                else if (item is DriveItem.Folder) onNavigateToFolder(item.telegramChatId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
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
            currentFolderName = "My Drive",
            isGridView = false,
            connectivityStatus = ConnectivityObserver.Status.Available,
            onNavigateToTransfers = {},
            onNavigateToSettings = {},
            onNavigateToPreview = { _ -> },
            onSearchQueryChange = { _ -> },
            onToggleViewMode = {},
            onSetSortOrder = { _ -> },
            onSetFilterType = { _ -> },
            onCreateFolder = { _ -> },
            onUploadFile = { _, _ -> },
            onDeleteItems = { _ -> },
            onMoveItems = { _, _ -> },
            onMoveFolderContents = { _, _ -> },
            onDownloadFile = { _, _, _ -> },
            onDownloadFolderContents = { _ -> },
            onToggleStarred = { _ -> },
            onNavigateToFolder = { _, _ -> },
            onNavigateBack = {},
            onRefresh = {},
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveListItem(
    item: DriveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (item is DriveItem.File) {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.folder), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            val (icon, color) = FileUiUtils.getFileIconAndColor(item)
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
    onDownloadClick: () -> Unit,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    var showItemMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

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
                val (icon, color) = FileUiUtils.getFileIconAndColor(item)
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
                    Text(stringResource(R.string.type_label, stringResource(R.string.folder)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
