package com.awd.teledrive.domain.model

sealed class DriveItem {
    abstract val id: Long
    abstract val parentChatId: Long
    abstract val name: String
    abstract val isStarred: Boolean

    data class File(
        override val id: Long,
        override val parentChatId: Long,
        override val name: String,
        val size: Long,
        val mimeType: String,
        val telegramFileId: Int,
        val thumbnailPath: String? = null,
        val localPath: String? = null,
        override val isStarred: Boolean = false,
        val remoteUniqueId: String = ""
    ) : DriveItem()

    data class Folder(
        override val id: Long,
        override val parentChatId: Long,
        override val name: String,
        val telegramChatId: Long,
        override val isStarred: Boolean = false
    ) : DriveItem()
}
