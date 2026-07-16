package com.awd.teledrive.data.model

data class TransferInfo(
    val fileId: Int,
    val remoteUniqueId: String,
    val fileName: String,
    val progress: Float, // 0.0 to 1.0
    val isDownload: Boolean,
    val status: String, // "Transferring", "Completed", "Error", "Cancelled"
    val totalSize: Long = 0,
    val downloadedSize: Long = 0
)
