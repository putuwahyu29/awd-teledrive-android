package com.awd.teledrive.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val thumbnailRemoteUniqueId: String? = null
)
