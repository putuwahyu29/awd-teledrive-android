package com.awd.teledrive.data.local

import androidx.room.Entity

@Entity(
    tableName = "drive_items",
    primaryKeys = ["id", "parentChatId"]
)
data class DriveItemEntity(
    val id: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val telegramFileId: Int,
    val parentChatId: Long,
    val isFolder: Boolean,
    val thumbnailPath: String? = null,
    val localPath: String? = null,
    val isStarred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnailFileId: Int? = null,
    val remoteUniqueId: String? = null,
    val thumbnailRemoteUniqueId: String? = null,
    val splitGroupId: String? = null,
    val partIndex: Int = 0,
    val totalParts: Int = 1
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @androidx.room.PrimaryKey val remoteUniqueId: String,
    val fileId: Int,
    val fileName: String,
    val progress: Float,
    val isDownload: Boolean,
    val status: String,
    val totalSize: Long,
    val downloadedSize: Long,
    val createdAt: Long = System.currentTimeMillis()
)
