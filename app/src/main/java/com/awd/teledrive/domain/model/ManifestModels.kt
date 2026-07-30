package com.awd.teledrive.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TeleDriveManifest(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
    val virtualFolders: Map<String, VirtualFolder> = emptyMap(),
    val fileMappings: Map<String, String> = emptyMap(), // messageId -> virtualFolderId
    val secureFolderChatIds: Set<Long> = emptySet()
)

data class CloudBackup(
    val messageId: Long,
    val date: Long, // timestamp
    val folderCount: Int
)

@Serializable
data class VirtualFolder(
    val id: String,
    val name: String,
    val parentId: String = "0",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val type: String = "virtual_folder",
    val isSecure: Boolean = false
)
