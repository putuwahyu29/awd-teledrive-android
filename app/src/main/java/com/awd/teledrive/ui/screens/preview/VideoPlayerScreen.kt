package com.awd.teledrive.ui.screens.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.ui.theme.TeledriveTheme
import androidx.annotation.OptIn as AndroidOptIn

@OptIn(ExperimentalMaterial3Api::class)
@AndroidOptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    
    var isLoading by remember { mutableStateOf(true) }

    val exoPlayer = if (isPreview) null else remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == androidx.media3.common.Player.STATE_BUFFERING
                }
                override fun onIsLoadingChanged(loading: Boolean) {
                    if (!loading) isLoading = false
                }
            })
            prepare()
            playWhenReady = true
        }
    }

    VideoPlayerContent(
        player = exoPlayer,
        url = url,
        onBack = onBack,
        isLoading = isLoading
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
    onBack: () -> Unit,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val fileName = url.substringAfterLast('/')
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_with_other)) },
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
            }
            
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
            
            if (player == null && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.video_player_placeholder), color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VideoPlayerPreview() {
    TeledriveTheme {
        VideoPlayerContent(player = null, url = "", onBack = {}, isLoading = false)
    }
}

