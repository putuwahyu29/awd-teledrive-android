package com.awd.teledrive.ui.screens.preview

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.domain.model.DriveItem
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val decryptedPaths by viewModel.decryptedPaths.collectAsState()
    val isDecrypting by viewModel.isDecrypting.collectAsState()
    val isOpening by viewModel.isOpening.collectAsState()
    
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val initialPage = remember { viewModel.getInitialIndex() }
        val pagerState = rememberPagerState(initialPage = initialPage) { items.size }

        // Sync pager with items list to prevent jumping when list updates
        LaunchedEffect(items) {
            if (items.isNotEmpty()) {
                val currentFileId = items.getOrNull(pagerState.currentPage)?.id
                if (currentFileId != null) {
                    val newIndex = items.indexOfFirst { it.id == currentFileId }
                    if (newIndex != -1 && newIndex != pagerState.currentPage) {
                        pagerState.scrollToPage(newIndex)
                    }
                }
            }
        }

        PreviewPager(
            items = items,
            folders = folders,
            transfers = transfers,
            decryptedPaths = decryptedPaths,
            isDecrypting = isDecrypting,
            isOpening = isOpening,
            pagerState = pagerState,
            onBack = onBack,
            onOpenPlayer = onOpenPlayer,
            onOpenPdf = onOpenPdf,
            onOpenText = onOpenText,
            onToggleStarred = viewModel::toggleStarred,
            onSaveToDevice = viewModel::saveToDevice,
            onAutoDownload = viewModel::autoDownloadForPreview,
            onLoadFile = viewModel::downloadForPreview,
            onDeleteItem = { file ->
                viewModel.deleteItem(file)
                onBack()
            },
            onMoveItem = viewModel::moveItem
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewPager(
    items: List<DriveItem.File>,
    folders: List<DriveItem.Folder>,
    transfers: Map<String, TransferInfo>,
    decryptedPaths: Map<Long, String>,
    isDecrypting: Map<Long, Boolean>,
    isOpening: Map<Long, Boolean>,
    pagerState: PagerState,
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    onToggleStarred: (DriveItem.File) -> Unit,
    onSaveToDevice: (DriveItem.File) -> Unit,
    onAutoDownload: (DriveItem.File) -> Unit,
    onLoadFile: (DriveItem.File) -> Unit,
    onDeleteItem: (DriveItem.File) -> Unit,
    onMoveItem: (DriveItem.File, Long) -> Unit
) {
    val context = LocalContext.current
    var isZoomEnabled by remember { mutableStateOf(false) }
    
    if (items.isEmpty() || pagerState.currentPage >= items.size) return
    
    val currentFile = items[pagerState.currentPage]

    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, items.size) {
        if (items.isNotEmpty() && pagerState.currentPage < items.size) {
            if (!pagerState.isScrollInProgress) {
                onAutoDownload(items[pagerState.currentPage])
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteItem(currentFile)
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.root_storage)) },
                        leadingContent = { Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            onMoveItem(currentFile, 0L)
                            showMoveDialog = false
                        }
                    )
                    HorizontalDivider()

                    if (folders.isEmpty()) {
                        Text(stringResource(R.string.no_folders_found), modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(folders) { folder ->
                                if (folder.telegramChatId != currentFile.parentChatId) {
                                    ListItem(
                                        headlineContent = { Text(folder.name) },
                                        leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                        modifier = Modifier.clickable {
                                            onMoveItem(currentFile, folder.telegramChatId)
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
            TopAppBar(
                title = { 
                    Column {
                        Text(currentFile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${pagerState.currentPage + 1} / ${items.size}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (currentFile.mimeType.startsWith("image/")) {
                        IconButton(onClick = { isZoomEnabled = !isZoomEnabled }) {
                            Icon(
                                imageVector = if (isZoomEnabled) Icons.Default.ZoomInMap else Icons.Default.ZoomOutMap,
                                contentDescription = if (isZoomEnabled) "Disable Zoom" else "Enable Zoom",
                                tint = if (isZoomEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (currentFile.localPath != null) {
                        IconButton(onClick = { 
                            onSaveToDevice(currentFile)
                            Toast.makeText(context, context.getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.DownloadForOffline, contentDescription = stringResource(R.string.save_success))
                        }
                        IconButton(onClick = { 
                            FileSharingHelper.shareFile(context, currentFile.localPath!!, currentFile.mimeType)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                        }
                    }
                    
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (currentFile.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                            onClick = { onToggleStarred(currentFile); showMenu = false },
                            leadingIcon = { 
                                Icon(
                                    if (currentFile.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline, 
                                    null,
                                    tint = if (currentFile.isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                ) 
                            }
                        )
                        if (currentFile.localPath != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_with_other)) },
                                onClick = { 
                                    FileSharingHelper.openFileExternally(context, currentFile.localPath!!, currentFile.mimeType)
                                    showMenu = false 
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.info)) },
                            onClick = { showInfoDialog = true; showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { showDeleteConfirm = true; showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            userScrollEnabled = true,
            beyondViewportPageCount = 1
        ) { pageIndex ->
            val file = items[pageIndex]
            val transfer = transfers[file.remoteUniqueId] ?: transfers.values.find { it.fileId == file.telegramFileId }
            
            // CRITICAL FIX: If file is encrypted, NEVER use localPath (it's scrambled bytes). 
            // Wait until decryptedPaths has the clean file.
            val displayPath = if (file.isEncrypted) decryptedPaths[file.id] else file.localPath
            
            val decrypting = isDecrypting[file.id] ?: false
            val opening = isOpening[file.id] ?: false

            if (file.isSplit) {
                SplitFilePreviewPlaceholder(file)
            } else {
                PreviewContent(
                    file = file,
                    displayPath = displayPath,
                    transfer = transfer,
                    isDecrypting = decrypting,
                    isOpening = opening,
                    onOpenPlayer = onOpenPlayer,
                    onOpenPdf = onOpenPdf,
                    onOpenText = onOpenText,
                    isZoomEnabled = isZoomEnabled,
                    onLoadFile = { onLoadFile(file) }
                )
            }
        }
    }
    
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.file_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.name_label, currentFile.name), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.size_label, formatSize(currentFile.size)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.type_label, currentFile.mimeType), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.status_label,
                            if (currentFile.localPath != null) stringResource(R.string.available_offline) else stringResource(R.string.cloud_only)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
fun PreviewContent(
    file: DriveItem.File,
    displayPath: String?,
    transfer: TransferInfo?,
    isDecrypting: Boolean,
    isOpening: Boolean,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    isZoomEnabled: Boolean,
    onLoadFile: () -> Unit
) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    
    // Explicitly track if the media is currently being decoded/rendered by Coil
    var isMediaRendering by remember(displayPath) { mutableStateOf(false) }
    
    // Check for waiting-for-decryption state to avoid blank screen
    val isWaitingForDecryption = file.isEncrypted && file.localPath != null && displayPath == null
    
    val isTransferring = transfer != null && (transfer.status == com.awd.teledrive.data.repository.TransferRepository.Status.DOWNLOADING || transfer.status == com.awd.teledrive.data.repository.TransferRepository.Status.UPLOADING)
    
    // isLoading is true if we are transferring, decrypting, or if the image loader is still working
    val isLoading = isTransferring || isDecrypting || isOpening || isMediaRendering || isWaitingForDecryption
    
    var scale by remember(file.id, isZoomEnabled) { mutableStateOf(1f) }
    var offset by remember(file.id, isZoomEnabled) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black), // Background black for focus
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (isImage && isZoomEnabled) {
                Modifier.fillMaxSize()
                    .pointerInput(file.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
            } else Modifier.padding(24.dp)
        ) {
            val (icon, color) = getFileIconAndColor(file)
            Box(
                contentAlignment = Alignment.Center, 
                modifier = if (isImage) {
                    Modifier.weight(1f).fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                } else {
                    Modifier.size(280.dp)
                        .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
                }
            ) {
                androidx.compose.animation.Crossfade(targetState = displayPath, label = "PreviewCrossfade") { path ->
                    if (path != null) {
                        AsyncImage(
                            model = path,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            onLoading = { isMediaRendering = true },
                            onSuccess = { isMediaRendering = false },
                            onError = { isMediaRendering = false }
                        )
                    } else {
                        val thumbnailModel = file.thumbnailPath
                        if (thumbnailModel != null) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isImage) 120.dp else 160.dp),
                                    tint = color.copy(alpha = 0.3f)
                                )
                                AsyncImage(
                                    model = thumbnailModel,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(if (isImage) 120.dp else 160.dp),
                                tint = color.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
                
                // Centered Loading Overlay
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { 
                                    if (isDecrypting || isOpening || isMediaRendering || isWaitingForDecryption) 1f else (transfer?.progress ?: 0f)
                                },
                                modifier = Modifier.size(if (isImage) 80.dp else 120.dp),
                                strokeWidth = 6.dp,
                                color = if (isDecrypting || isWaitingForDecryption) MaterialTheme.colorScheme.secondary 
                                        else if (isOpening || isMediaRendering) MaterialTheme.colorScheme.tertiary 
                                        else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            val statusText = when {
                                isDecrypting || isWaitingForDecryption -> stringResource(R.string.decrypting)
                                isOpening || isMediaRendering -> stringResource(R.string.preparing_media)
                                isTransferring -> stringResource(R.string.loading_percentage, (transfer?.progress?.times(100))?.toInt() ?: 0)
                                else -> stringResource(R.string.loading)
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            
            if (!isImage) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                if (displayPath == null && isLoading) {
                    Text(
                        text = if (isDecrypting) stringResource(R.string.decrypting) 
                               else if (isOpening) stringResource(R.string.preparing_media)
                               else stringResource(R.string.loading_percentage, (transfer?.progress?.times(100))?.toInt() ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (displayPath == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (file.isEncrypted && file.localPath != null) stringResource(R.string.decrypt_to_view) else stringResource(R.string.not_loaded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = onLoadFile,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            val buttonIcon = if (file.isEncrypted && file.localPath != null) Icons.Default.Lock else Icons.Default.CloudDownload
                            val buttonText = if (file.isEncrypted && file.localPath != null) stringResource(R.string.decrypt) else stringResource(R.string.load_file)
                            
                            Icon(buttonIcon, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(buttonText)
                        }
                    }
                } else {
                    Text(
                        text = file.mimeType,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(48.dp))
                
                if (displayPath != null) {
                    val isInternalSupported = file.mimeType.startsWith("video/") || 
                                              file.mimeType.startsWith("audio/") || 
                                              file.mimeType == "application/pdf" ||
                                              file.mimeType == "text/plain"
                    
                    if (isInternalSupported) {
                        Button(
                            onClick = {
                                when {
                                    file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/") -> {
                                        onOpenPlayer(displayPath)
                                    }
                                    file.mimeType == "application/pdf" -> {
                                        onOpenPdf(displayPath)
                                    }
                                    file.mimeType == "text/plain" -> {
                                        onOpenText(displayPath)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.open_media))
                        }
                    } else {
                        Button(
                            onClick = {
                                try {
                                    FileSharingHelper.openFileExternally(context, displayPath, file.mimeType)
                                } catch (e: Exception) {
                                    Toast.makeText(context, context.getString(R.string.err_open_file), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.open_with_other))
                        }
                    }
                }
            } else if (displayPath == null) {
                if (isLoading) {
                    // Show floating progress for image
                    Text(
                        text = if (isDecrypting) stringResource(R.string.decrypting) 
                               else if (isOpening) stringResource(R.string.preparing_media)
                               else "${(transfer?.progress?.times(100))?.toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    OutlinedButton(
                        onClick = onLoadFile,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        val buttonIcon = if (file.isEncrypted && file.localPath != null) Icons.Default.Lock else Icons.Default.CloudDownload
                        val buttonText = if (file.isEncrypted && file.localPath != null) stringResource(R.string.decrypt) else stringResource(R.string.load_image)

                        Icon(buttonIcon, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(buttonText)
                    }
                }
            }
        }

        if (file.isEncrypted && displayPath == null && !isLoading && file.localPath == null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.decrypt_to_view), color = Color.White)
                }
            }
        }
    }
}

@Composable
fun SplitFilePreviewPlaceholder(file: DriveItem.File) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.file_too_large),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.split_file_preview_desc, file.totalParts),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.download_to_view_full),
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

@Composable
private fun getFileIconAndColor(file: DriveItem.File): Pair<ImageVector, Color> {
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
