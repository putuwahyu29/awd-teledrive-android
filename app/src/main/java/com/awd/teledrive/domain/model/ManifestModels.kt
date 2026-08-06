package com.awd.teledrive.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TeleDriveManifest(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
    val virtualFolders: Map<String, VirtualFolder> = emptyMap(),
    val fileMappings: Map<String, String> = emptyMap(), // messageId -> virtualFolderId
    val secureFolderChatIds: Set<Long> = emptySet(),
    val splitFileMasters: Map<String, SplitFileMaster> = emptyMap(), // groupId -> MasterRecord
    val folderMetadataIds: Map<String, Long> = emptyMap(), // virtualId -> messageId
    val fileMetadataIds: Map<String, Long> = emptyMap(), // telegramMessageId -> metadataMessageId
    val deletedFolderIds: Map<String, Long> = emptyMap() // virtualId -> deletedAt timestamp
)

@Serializable
data class SplitFileMaster(
    val groupId: String,
    val originalName: String,
    val totalSize: Long,
    val mimeType: String,
    val totalParts: Int,
    val virtualFolderId: String? = "0",
    val isEncrypted: Boolean = false,
    val partMessageIds: List<Long> = emptyList(),
    val metadataMessageId: Long = 0
)

@Serializable
data class FileMetadata(
    val messageId: Long,
    val fileName: String,
    val virtualFolderId: String? = "0",
    val isEncrypted: Boolean = false,
    val splitGroupId: String? = null,
    val metadataMessageId: Long = 0
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
