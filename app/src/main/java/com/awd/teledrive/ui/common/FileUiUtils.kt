package com.awd.teledrive.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.awd.teledrive.domain.model.DriveItem

object FileUiUtils {
    
    fun getFileIconAndColor(item: DriveItem): Pair<ImageVector, Color> {
        return if (item is DriveItem.Folder) {
            Icons.Default.Folder to Color(0xFF2196F3) // Material Blue
        } else {
            val file = item as DriveItem.File
            getFileIconAndColorByMime(file.mimeType)
        }
    }

    fun getFileIconAndColorByMime(mimeType: String): Pair<ImageVector, Color> {
        return when {
            mimeType.startsWith("image/") -> Icons.Default.Image to Color(0xFF4CAF50)
            mimeType.startsWith("video/") -> Icons.Default.VideoFile to Color(0xFFE91E63)
            mimeType.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFFFF9800)
            mimeType == "application/pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
            
            // Archives
            mimeType.contains("zip") || mimeType.contains("rar") || 
            mimeType.contains("7z") || mimeType.contains("tar") || 
            mimeType.contains("compressed") -> Icons.Default.FolderZip to Color(0xFF9C27B0)
            
            // Documents
            mimeType.contains("word") || mimeType.contains("officedocument.wordprocessingml") -> 
                Icons.Default.Description to Color(0xFF2196F3)
            mimeType.contains("excel") || mimeType.contains("officedocument.spreadsheetml") || 
            mimeType.contains("sheet") -> Icons.Default.TableChart to Color(0xFF4CAF50)
            mimeType.contains("powerpoint") || mimeType.contains("officedocument.presentationml") || 
            mimeType.contains("presentation") -> Icons.Default.Slideshow to Color(0xFFFF5722)
            
            // Text & Code
            mimeType.startsWith("text/") || mimeType == "application/json" || 
            mimeType == "application/javascript" || mimeType == "application/xml" -> 
                Icons.AutoMirrored.Filled.Article to Color(0xFF607D8B)
            
            // Android APK
            mimeType.contains("android.package-archive") -> Icons.Default.Android to Color(0xFF3DDC84)
            
            // Default
            else -> Icons.Default.InsertDriveFile to Color(0xFF9E9E9E)
        }
    }
}
