package com.awd.teledrive.core

import android.webkit.MimeTypeMap

object MimeTypeHelper {
    
    /**
     * Resolves the mime type based on the file name extension if the original mime type is 
     * missing or generic.
     */
    fun resolveMimeType(fileName: String, originalMimeType: String?): String {
        // If it's already a specific mime type, keep it, unless it's generic
        if (!originalMimeType.isNullOrBlank() && 
            originalMimeType != "application/octet-stream" && 
            originalMimeType != "*/*") {
            return originalMimeType
        }

        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return originalMimeType ?: "application/octet-stream"

        // Use MimeTypeMap for common extensions
        val mimeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if (mimeFromExtension != null) return mimeFromExtension

        // Fallback for common types that might be missing or need specific mapping
        return when (extension) {
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4", "mkv", "mov", "avi" -> "video/x-matroska" // generic video
            "mp3", "wav", "ogg", "m4a" -> "audio/mpeg" // generic audio
            "json" -> "application/json"
            "txt", "log", "md" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "apk" -> "application/vnd.android.package-archive"
            else -> originalMimeType ?: "application/octet-stream"
        }
    }
}
