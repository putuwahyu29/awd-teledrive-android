package com.awd.teledrive.ui.screens.media

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: MediaViewModel = hiltViewModel()
) {
    val mediaItems by viewModel.mediaItems.collectAsState()
    val folders by viewModel.folders.collectAsState()

    MediaContent(
        mediaItems = mediaItems,
        folders = folders,
        onNavigateToPreview = onNavigateToPreview,
        onDownload = viewModel::downloadFile,
        onDelete = viewModel::deleteItems,
        onMove = viewModel::moveItems,
        onToggleStar = viewModel::toggleStarred
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaContent(
    mediaItems: List<DriveItem.File>,
    folders: List<DriveItem.Folder>,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onDownload: (DriveItem.File) -> Unit,
    onDelete: (List<DriveItem.File>) -> Unit,
    onMove: (List<Long>, Long) -> Unit,
    onToggleStar: (DriveItem.File) -> Unit
) {
    val context = LocalContext.current
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()

    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val itemsToDelete = mediaItems.filter { it.id in selectedItems }
                    onDelete(itemsToDelete)
                    showDeleteConfirm = false
                    selectedItems = emptySet()
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
                            onMove(selectedItems.toList(), 0L)
                            showMoveDialog = false
                            selectedItems = emptySet()
                        }
                    )
                    HorizontalDivider()
                    if (folders.isEmpty()) {
                        Text(stringResource(R.string.no_folders_found), modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(folders) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name) },
                                    leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clickable {
                                        onMove(selectedItems.toList(), folder.telegramChatId)
                                        showMoveDialog = false
                                        selectedItems = emptySet()
                                    }
                                )
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
                    navigationIcon = {
                        IconButton(onClick = { selectedItems = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedItems.forEach { id ->
                                mediaItems.find { it.id == id }?.let { onToggleStar(it) }
                            }
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Default.Star, contentDescription = stringResource(R.string.add_star))
                        }
                        IconButton(onClick = {
                            val count = selectedItems.size
                            selectedItems.forEach { id ->
                                mediaItems.find { it.id == id }?.let { onDownload(it) }
                            }
                            selectedItems = emptySet()
                            Toast.makeText(context, context.getString(R.string.starting_downloads, count), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                        }
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.move))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.media)) },
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        if (mediaItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Tidak ada media")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                itemsIndexed(mediaItems) { _, item ->
                    val isSelected = selectedItems.contains(item.id)
                    MediaGridItem(
                        item = item,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelectionMode) {
                                selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                            } else {
                                onNavigateToPreview(item)
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                selectedItems = setOf(item.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: DriveItem.File,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.small,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.thumbnailPath ?: item.localPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                )
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Icon overlay for video
            if (item.mimeType.startsWith("video/")) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
            
            if (item.isStarred) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MediaPreview() {
    TeledriveTheme {
        MediaContent(
            mediaItems = listOf(
                DriveItem.File(1L, 0L, "photo.jpg", 1024, "image/jpeg", 1, null, null, false, "")
            ),
            folders = emptyList(),
            onNavigateToPreview = {},
            onDownload = {},
            onDelete = {},
            onMove = { _, _ -> },
            onToggleStar = {}
        )
    }
}

