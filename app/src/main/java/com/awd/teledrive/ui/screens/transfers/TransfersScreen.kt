package com.awd.teledrive.ui.screens.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.ui.theme.TeledriveTheme
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    viewModel: TransfersViewModel = hiltViewModel()
) {
    val transfers by viewModel.transfers.collectAsState()

    TransfersContent(
        transfers = transfers.values.toList(),
        onBack = onBack,
        onCancelTransfer = viewModel::cancelTransfer,
        onClearCompleted = viewModel::clearCompleted
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersContent(
    transfers: List<TransferInfo>,
    onBack: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onClearCompleted: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfers)) },
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = onClearCompleted) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.clear_selection))
                    }
                }
            )
        }
    ) { padding ->
        if (transfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(stringResource(R.string.no_results))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(transfers) { transfer ->
                    ListItem(
                        headlineContent = { Text(transfer.fileName) },
                        supportingContent = {
                            Column {
                                val sizeText = if (transfer.totalSize > 0) {
                                    "${formatSize(transfer.downloadedSize)} / ${formatSize(transfer.totalSize)}"
                                } else {
                                    formatSize(transfer.downloadedSize)
                                }
                                val statusText = when(transfer.status) {
                                    "Mengunduh" -> stringResource(R.string.downloading_file, "")
                                    "Mengunggah" -> stringResource(R.string.uploading_file, "")
                                    "Selesai" -> stringResource(R.string.completed)
                                    else -> transfer.status
                                }
                                Text("$statusText - $sizeText (${(transfer.progress * 100).toInt()}%)")
                                LinearProgressIndicator(
                                    progress = { transfer.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (transfer.isDownload) Icons.Default.Download else Icons.Default.Upload,
                                contentDescription = null
                            )
                        },
                        trailingContent = {
                            if (transfer.status == "Mengunduh" || transfer.status == "Mengunggah") {
                                IconButton(onClick = { onCancelTransfer(transfer.remoteUniqueId) }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransfersPreview() {
    TeledriveTheme {
        TransfersContent(
            transfers = listOf(
                TransferInfo(1, "unique_id_1", "Large_Movie.mkv", 0.45f, true, "Transferring")
            ),
            onBack = {},
            onCancelTransfer = {},
            onClearCompleted = {}
        )
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

