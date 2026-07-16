package com.awd.teledrive.ui.screens.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlin.OptIn
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import com.awd.teledrive.core.FileSharingHelper

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalInspectionMode
import com.awd.teledrive.ui.theme.TeledriveTheme

@OptIn(ExperimentalMaterial3Api::class)
@AndroidOptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    
    val exoPlayer = if (isPreview) null else remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    VideoPlayerContent(
        player = exoPlayer,
        url = url,
        onBack = onBack,
    )

    if (exoPlayer != null) {
        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.release()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@AndroidOptIn(UnstableApi::class)
@Composable
fun VideoPlayerContent(
    player: ExoPlayer?,
    url: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fileName = url.substringAfterLast('/')
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Buka dengan Aplikasi Lain") },
                            onClick = {
                                // Guess mime type or use general video/
                                FileSharingHelper.openFileExternally(context, url, "video/*")
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (player != null) {
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            this.player = player
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Video Player Placeholder", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VideoPlayerPreview() {
    TeledriveTheme {
        VideoPlayerContent(player = null, url = "") {}
    }
}

