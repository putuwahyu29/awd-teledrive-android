package com.awd.teledrive.ui.screens.preview

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.awd.teledrive.ui.common.FileUiUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme

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
    
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val initialPage = remember { viewModel.getInitialIndex() }
        val pagerState = rememberPagerState(initialPage = initialPage) { items.size }

        PreviewPager(
            items = items,
            folders = folders,
            transfers = transfers,
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
            title = { Text("Hapus Item") },
            text = { Text("Apakah Anda yakin ingin menghapus item ini secara permanen?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteItem(currentFile)
                    showDeleteConfirm = false
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") }
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
                        headlineContent = { Text("Penyimpanan Utama (Root)") },
                        leadingContent = { Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            onMoveItem(currentFile, 0L)
                            showMoveDialog = false
                        }
                    )
                    HorizontalDivider()

                    if (folders.isEmpty()) {
                        Text("Tidak ada folder lain.", modifier = Modifier.padding(16.dp))
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
                            Toast.makeText(context, "Disimpan ke Unduhan", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.DownloadForOffline, contentDescription = "Simpan ke Perangkat")
                        }
                        IconButton(onClick = { 
                            FileSharingHelper.shareFile(context, currentFile.localPath!!, currentFile.mimeType)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Bagikan")
                        }
                    }
                    
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (currentFile.isStarred) "Hapus Bintang" else "Berbintang") },
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
                                text = { Text("Buka dengan Aplikasi Lain") },
                                onClick = { 
                                    FileSharingHelper.openFileExternally(context, currentFile.localPath!!, currentFile.mimeType)
                                    showMenu = false 
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Info") },
                            onClick = { showInfoDialog = true; showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus") },
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
            
            PreviewContent(
                file = file,
                transfer = transfer,
                onOpenPlayer = onOpenPlayer,
                onOpenPdf = onOpenPdf,
                onOpenText = onOpenText,
                isZoomEnabled = isZoomEnabled,
                onLoadFile = { onLoadFile(file) }
            )
        }
    }
    
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Informasi File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nama: ${currentFile.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("Ukuran: ${formatSize(currentFile.size)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Tipe: ${currentFile.mimeType}", style = MaterialTheme.typography.bodyMedium)
                    Text("Status: ${if (currentFile.localPath != null) "Tersedia Offline" else "Hanya Cloud"}", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Tutup") }
            }
        )
    }
}

@Composable
fun PreviewContent(
    file: DriveItem.File,
    transfer: TransferInfo?,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    isZoomEnabled: Boolean,
    onLoadFile: () -> Unit
) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    val isTransferring = transfer != null && (transfer.status == "Mengunduh" || transfer.status == "Mengunggah")
    
    var scale by remember(file.id, isZoomEnabled) { mutableStateOf(1f) }
    var offset by remember(file.id, isZoomEnabled) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier.fillMaxSize(),
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
            val (icon, color) = FileUiUtils.getFileIconAndColor(file)
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
                val thumbnailModel = file.localPath ?: file.thumbnailPath
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
                
                if (file.localPath == null && isTransferring) {
                    CircularProgressIndicator(
                        progress = { transfer?.progress ?: 0f },
                        modifier = Modifier.size(if (isImage) 100.dp else 160.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                
                if (file.localPath == null && isTransferring) {
                    Text(
                        text = "${(transfer?.progress?.times(100))?.toInt()}% Memuat...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (file.localPath == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Belum dimuat",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = onLoadFile,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Muat File")
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
                
                if (file.localPath != null) {
                    val isInternalSupported = file.mimeType.startsWith("video/") || 
                                              file.mimeType.startsWith("audio/") || 
                                              file.mimeType == "application/pdf" ||
                                              file.mimeType.startsWith("text/") ||
                                              file.mimeType == "application/json"
                    
                    if (isInternalSupported) {
                        Button(
                            onClick = {
                                when {
                                    file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/") -> {
                                        onOpenPlayer(file.localPath)
                                    }
                                    file.mimeType == "application/pdf" -> {
                                        onOpenPdf(file.localPath)
                                    }
                                    file.mimeType.startsWith("text/") || file.mimeType == "application/json" -> {
                                        onOpenText(file.localPath)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buka Media")
                        }
                    } else {
                        Button(
                            onClick = {
                                FileSharingHelper.openFileExternally(context, file.localPath, file.mimeType)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buka dengan Aplikasi Lain")
                        }
                    }
                }
            } else if (file.localPath == null) {
                if (isTransferring) {
                    // Show floating progress for image
                    Text(
                        text = "${(transfer?.progress?.times(100))?.toInt()}%",
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
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Muat Gambar")
                    }
                }
            }
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (kotlin.math.log10(size.toDouble()) / kotlin.math.log10(1024.0)).toInt()
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
