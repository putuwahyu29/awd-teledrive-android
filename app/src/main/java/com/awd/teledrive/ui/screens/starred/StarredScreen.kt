package com.awd.teledrive.ui.screens.starred

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.awd.teledrive.R
import com.awd.teledrive.ui.screens.home.DriveListItem

import androidx.compose.ui.tooling.preview.Preview
import com.awd.teledrive.ui.theme.TeledriveTheme
import com.awd.teledrive.domain.model.DriveItem

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.automirrored.filled.ViewList
import com.awd.teledrive.ui.screens.home.DriveGridItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    onBack: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: StarredViewModel = hiltViewModel()
) {
    val items by viewModel.starredItems.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()

    StarredContent(
        items = items,
        isGridView = isGridView,
        selectedItems = selectedItems,
        onSelectionChange = { selectedItems = it },
        onToggleViewMode = viewModel::toggleViewMode,
        onBack = onBack,
        onNavigateToPreview = onNavigateToPreview,
        onToggleStarred = viewModel::toggleStarred,
        onDownloadClick = { id, chatId, name -> viewModel.downloadFile(id, chatId, name) },
        onBulkDownload = {
            val selectedFiles = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>()
            val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
            selectedFiles.forEach { viewModel.downloadFile(it.id, it.parentChatId, it.name) }
            selectedFolders.forEach { viewModel.downloadFolderContents(it.telegramChatId) }
            selectedItems = emptySet()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredContent(
    items: List<DriveItem>,
    isGridView: Boolean,
    selectedItems: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onToggleViewMode: () -> Unit,
    onBack: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onToggleStarred: (DriveItem) -> Unit,
    onDownloadClick: (Long, Long, String) -> Unit,
    onBulkDownload: () -> Unit
) {
    val isSelectionMode = selectedItems.isNotEmpty()

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                    navigationIcon = {
                        IconButton(onClick = { onSelectionChange(emptySet()) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val files = items.filterIsInstance<DriveItem.File>()
                                if (selectedItems.size >= files.size && files.isNotEmpty()) {
                                    onSelectionChange(emptySet())
                                } else {
                                    onSelectionChange(files.map { it.id }.toSet())
                                }
                            }
                        ) {
                            val files = items.filterIsInstance<DriveItem.File>()
                            Icon(
                                if (selectedItems.size >= files.size && files.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = stringResource(R.string.select_all)
                            )
                        }
                        IconButton(onClick = onBulkDownload) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.starred)) },
                    modifier = Modifier.statusBarsPadding(),
                    actions = {
                        IconButton(onClick = onToggleViewMode) {
                            Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_starred_items))
            }
        } else {
            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(items) { item ->
                        val isSelected = selectedItems.contains(item.id)
                        DriveGridItem(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    onSelectionChange(if (isSelected) selectedItems - item.id else selectedItems + item.id)
                                } else {
                                    if (item is DriveItem.File) onNavigateToPreview(item)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) onSelectionChange(setOf(item.id))
                            },
                            onStarClick = { onToggleStarred(item) },
                            onDownloadClick = {
                                if (item is DriveItem.File) onDownloadClick(item.id, item.parentChatId, item.name)
                            }
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(items) { item ->
                        val isSelected = selectedItems.contains(item.id)
                        DriveListItem(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    onSelectionChange(if (isSelected) selectedItems - item.id else selectedItems + item.id)
                                } else {
                                    if (item is DriveItem.File) onNavigateToPreview(item)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) onSelectionChange(setOf(item.id))
                            },
                            onStarClick = { onToggleStarred(item) },
                            onDownloadClick = {
                                if (item is DriveItem.File) onDownloadClick(item.id, item.parentChatId, item.name)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StarredPreview() {
    TeledriveTheme {
        StarredContent(
            items = listOf(
                DriveItem.File(1L, 0L, "Starred File.pdf", 5000, "application/pdf", 1, null, null, true, "")
            ),
            isGridView = false,
            selectedItems = emptySet(),
            onSelectionChange = {},
            onToggleViewMode = {},
            onBack = {},
            onNavigateToPreview = {},
            onToggleStarred = {},
            onDownloadClick = { _, _, _ -> },
            onBulkDownload = {}
        )
    }
}
