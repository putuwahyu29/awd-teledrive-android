package com.awd.teledrive.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAnalysisScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val cloudStats by viewModel.cloudStats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_analysis)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            val totalSize = cloudStats.sumOf { it.totalSize }
            val totalCount = cloudStats.sumOf { it.count }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.cloud_usage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatSize(totalSize),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.items_count, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.cache_breakdown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )

            val photoStat = cloudStats.find { it.category == "IMAGE" }
            val videoStat = cloudStats.find { it.category == "VIDEO" }
            val audioStat = cloudStats.find { it.category == "AUDIO" }
            val docStat = cloudStats.find { it.category == "DOCUMENT" }
            val otherStat = cloudStats.find { it.category == "OTHER" }

            AnalysisItem(
                icon = Icons.Default.Image,
                label = stringResource(R.string.category_images),
                count = photoStat?.count ?: 0,
                size = photoStat?.totalSize ?: 0L,
                color = Color(0xFF4CAF50),
                description = stringResource(R.string.category_thumbnails_desc) // or similar
            )
            AnalysisItem(
                icon = Icons.Default.VideoFile,
                label = stringResource(R.string.category_videos),
                count = videoStat?.count ?: 0,
                size = videoStat?.totalSize ?: 0L,
                color = Color(0xFFE91E63),
                description = stringResource(R.string.category_videos_desc)
            )
            AnalysisItem(
                icon = Icons.Default.AudioFile,
                label = stringResource(R.string.category_audio),
                count = audioStat?.count ?: 0,
                size = audioStat?.totalSize ?: 0L,
                color = Color(0xFFFF9800),
                description = stringResource(R.string.category_audio_desc)
            )
            AnalysisItem(
                icon = Icons.Default.Description,
                label = stringResource(R.string.category_documents),
                count = docStat?.count ?: 0,
                size = docStat?.totalSize ?: 0L,
                color = Color(0xFF2196F3),
                description = stringResource(R.string.category_documents_desc)
            )
            AnalysisItem(
                icon = Icons.Default.FolderZip,
                label = stringResource(R.string.category_others),
                count = otherStat?.count ?: 0,
                size = otherStat?.totalSize ?: 0L,
                color = Color(0xFF9C27B0),
                description = stringResource(R.string.category_others_desc)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun AnalysisItem(
    icon: ImageVector,
    label: String,
    count: Int,
    size: Long,
    color: Color,
    description: String
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { 
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.items_count, count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        leadingContent = { 
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color) 
            }
        },
        trailingContent = {
            Text(
                text = formatSize(size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
