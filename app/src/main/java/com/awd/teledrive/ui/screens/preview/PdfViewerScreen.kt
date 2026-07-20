package com.awd.teledrive.ui.screens.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.ui.theme.TeledriveTheme
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    
    var pageCount by remember { mutableStateOf(0) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }

    LaunchedEffect(filePath) {
        if (!isPreview) {
            val file = File(filePath)
            if (file.exists()) {
                try {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Ensure resources are closed when leaving
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
        }
    }

    PdfViewerContent(
        pdfRenderer = pdfRenderer,
        pageCount = pageCount,
        filePath = filePath,
        onBack = onBack,
        isLoading = pdfRenderer == null && !isPreview
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerContent(
    pdfRenderer: PdfRenderer?,
    pageCount: Int,
    filePath: String,
    onBack: () -> Unit,
    isLoading: Boolean
) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    val fileName = filePath.substringAfterLast('/')

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
                                FileSharingHelper.openFileExternally(context, filePath, "application/pdf")
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                        )
                    }
                }
            )
        },
        containerColor = Color.DarkGray
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (pdfRenderer == null && !isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Gagal memuat PDF", color = Color.White)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(pageCount) { index ->
                        PdfPageItem(pdfRenderer, index)
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageItem(renderer: PdfRenderer?, index: Int) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    LaunchedEffect(index) {
        if (renderer != null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    synchronized(renderer) {
                        val page = renderer.openPage(index)
                        
                        // Optimized memory: Limit resolution to device screen width
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val scaleFactor = screenWidth.toFloat() / page.width.toFloat()
                        
                        val width = (page.width * scaleFactor).toInt().coerceAtMost(2000)
                        val height = (page.height * scaleFactor).toInt().coerceAtMost(3000)
                        
                        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        newBitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = newBitmap
                        page.close()
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewer", "Error rendering page $index", e)
                }
            }
        }
    }

    // Recycle bitmap when out of composition to save memory
    androidx.compose.runtime.DisposableEffect(index) {
        onDispose {
            // Optional: Keep in memory or recycle. For now let GC handle it as we reuse 'bitmap' state
        }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp), // Placeholder height
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PdfViewerPreview() {
    TeledriveTheme {
        PdfViewerContent(pdfRenderer = null, pageCount = 0, filePath = "Sample.pdf", onBack = {}, isLoading = false)
    }
}
