package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.service.TransferService
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val transferRepository: TransferRepository,
    private val driveDao: DriveDao,
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedMessagesChatId: Long = 0
    private val _savedMessagesChatIdFlow = MutableStateFlow(0L)
    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    private val exportOnComplete = mutableMapOf<String, String>()
    private val deleteAfterUpload = mutableMapOf<Int, String>()

    init {
        scope.launch {
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                    // Cek apakah file ini diminta untuk diekspor ke folder Download publik
                    val fileName = exportOnComplete[uniqueId] ?: exportOnComplete["temp_$fileId"]
                    if (fileName != null) {
                        transferRepository.saveToPublicDownloads(file.local.path, fileName)
                        exportOnComplete.remove(uniqueId)
                        exportOnComplete.remove("temp_$fileId")
                    }

                    if (uniqueId.isNotEmpty()) {
                        driveDao.updateLocalPathByUniqueId(uniqueId, file.local.path)
                        
                        // Only use local path as thumbnail if it's an image
                        val entity = driveDao.getItemByUniqueId(uniqueId)
                        if (entity?.mimeType?.startsWith("image/") == true) {
                            driveDao.updateThumbnailPathByUniqueId(uniqueId, file.local.path)
                        }
                    }
                    // Update by fileId as well to be sure
                    driveDao.updateLocalPath(fileId, file.local.path)
                    
                    fetchFiles()
                } else if (file.remote.isUploadingCompleted) {
                    Log.d("DriveRepo", "Upload completed for: ${file.remote.uniqueId}")
                    
                    // Auto-delete local copy if it was a temporary file from cache
                    val pathToDelete = deleteAfterUpload.remove(fileId)
                    if (pathToDelete != null) {
                        try {
                            val localFile = java.io.File(pathToDelete)
                            if (localFile.exists() && pathToDelete.contains(context.cacheDir.absolutePath)) {
                                localFile.delete()
                                Log.d("DriveRepo", "Deleted temporary upload file: $pathToDelete")
                            }
                        } catch (e: Exception) {
                            Log.e("DriveRepo", "Failed to delete temp file: $pathToDelete", e)
                        }
                    }

                    fetchFiles()
                }

                // Log any errors reported by TDLib
                if (file.local.canBeDownloaded.not() && !file.local.isDownloadingCompleted && file.local.isDownloadingActive) {
                   Log.e("DriveRepo", "TDLib Download Error for file ${file.id}: Local path = ${file.local.path}")
                }
            }
        }
    }

    private fun startTransferService() {
        val intent = Intent(context, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    @OptIn(FlowPreview::class)
    fun getItems(chatId: Long?, searchQuery: String = ""): Flow<List<DriveItem>> {
        val targetChatId = chatId ?: savedMessagesChatId
        Log.d("DriveRepo", "getItems called with chatId: $chatId, savedMessagesChatId: $savedMessagesChatId")
        
        val flow = if (searchQuery.isNotEmpty()) {
            driveDao.searchGlobal(searchQuery)
        } else if (targetChatId != 0L) {
            driveDao.getItemsFlow(targetChatId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }

        return flow.map { entities ->
            Log.d("DriveRepo", "Flow emitted ${entities.size} entities for chat $targetChatId")
            entities.sortedByDescending { it.createdAt }.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred)
                } else {
                    DriveItem.File(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.size,
                        entity.mimeType,
                        entity.telegramFileId,
                        entity.thumbnailPath,
                        entity.localPath,
                        entity.isStarred,
                        entity.remoteUniqueId ?: ""
                    )
                }
            }
        }.debounce(500)
    }

    fun fetchFiles(chatId: Long? = null) {
        val targetChatId = chatId ?: savedMessagesChatId
        Log.d("DriveRepo", "fetchFiles called for chatId: $chatId, targetChatId: $targetChatId")
        if (targetChatId == 0L) {
            telegramClient.send(TdApi.GetMe()) { result ->
                if (result is TdApi.User) {
                    savedMessagesChatId = result.id
                    _savedMessagesChatIdFlow.value = result.id
                    Log.d("DriveRepo", "Resolved savedMessagesChatId: $savedMessagesChatId")
                    loadAllDriveItems(savedMessagesChatId)
                } else if (result is TdApi.Error) {
                    Log.e("DriveRepo", "GetMe failed: ${result.message}")
                }
            }
        } else {
            loadAllDriveItems(targetChatId)
        }
    }

    private fun loadAllDriveItems(chatId: Long) {
        Log.d("DriveRepo", "Loading items for chatId: $chatId")
        // Use a limit of 1000 to ensure we catch enough files
        telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 1000, false)) { result ->
            if (result is TdApi.Messages) {
                Log.d("DriveRepo", "Found ${result.messages.size} messages in chat $chatId")
                val entities = result.messages.mapNotNull { message ->
                    if (message.sendingState != null) return@mapNotNull null
                    when (val content = message.content) {
                        is TdApi.MessageDocument -> {
                            val thumb = content.document.thumbnail
                            if (thumb != null && (thumb.file.local.path.isEmpty())) {
                                telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                            }
                            val docFile = content.document.document
                            DriveItemEntity(
                                id = message.id,
                                name = content.document.fileName,
                                size = docFile.expectedSize,
                                mimeType = content.document.mimeType,
                                telegramFileId = docFile.id,
                                parentChatId = chatId,
                                isFolder = false,
                                thumbnailPath = when {
                                    thumb?.file?.local?.path?.isNotEmpty() == true -> thumb.file.local.path
                                    content.document.mimeType.startsWith("image/") && docFile.local.path.isNotEmpty() -> docFile.local.path
                                    else -> null
                                },
                                localPath = docFile.local.path.takeIf { it.isNotEmpty() },
                                isStarred = false,
                                thumbnailFileId = thumb?.file?.id,
                                remoteUniqueId = docFile.remote.uniqueId,
                                thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                                createdAt = message.date.toLong() * 1000
                            )
                        }
                        is TdApi.MessagePhoto -> {
                            val photo = content.photo.sizes.lastOrNull()
                            val thumb = if (content.photo.sizes.size > 1) content.photo.sizes.firstOrNull() else null
                            
                            if (thumb != null && thumb.photo.local.path.isEmpty()) {
                                telegramClient.send(TdApi.DownloadFile(thumb.photo.id, 1, 0, 0, false))
                            }
                            
                            val photoFile = photo?.photo
                            DriveItemEntity(
                                id = message.id,
                                name = "Photo_${message.id}.jpg",
                                size = photoFile?.expectedSize ?: 0L,
                                mimeType = "image/jpeg",
                                telegramFileId = photoFile?.id ?: 0,
                                parentChatId = chatId,
                                isFolder = false,
                                thumbnailPath = thumb?.photo?.local?.path?.takeIf { it.isNotEmpty() } ?: photoFile?.local?.path?.takeIf { it.isNotEmpty() },
                                localPath = photoFile?.local?.path?.takeIf { it.isNotEmpty() },
                                isStarred = false,
                                thumbnailFileId = thumb?.photo?.id,
                                remoteUniqueId = photoFile?.remote?.uniqueId,
                                thumbnailRemoteUniqueId = thumb?.photo?.remote?.uniqueId,
                                createdAt = message.date.toLong() * 1000
                            )
                        }
                        is TdApi.MessageVideo -> {
                            val thumb = content.video.thumbnail
                            if (thumb != null && thumb.file.local.path.isEmpty()) {
                                telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                            }
                            val videoFile = content.video.video
                            DriveItemEntity(
                                id = message.id,
                                name = content.video.fileName,
                                size = videoFile.expectedSize,
                                mimeType = content.video.mimeType,
                                telegramFileId = videoFile.id,
                                parentChatId = chatId,
                                isFolder = false,
                                thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                                localPath = videoFile.local.path.takeIf { it.isNotEmpty() },
                                isStarred = false,
                                thumbnailFileId = thumb?.file?.id,
                                remoteUniqueId = videoFile.remote.uniqueId,
                                thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                                createdAt = message.date.toLong() * 1000
                            )
                        }
                        is TdApi.MessageAudio -> {
                            val audioFile = content.audio.audio
                            DriveItemEntity(
                                id = message.id,
                                name = content.audio.fileName.ifEmpty { "Audio_${message.id}.mp3" },
                                size = audioFile.expectedSize,
                                mimeType = content.audio.mimeType,
                                telegramFileId = audioFile.id,
                                parentChatId = chatId,
                                isFolder = false,
                                localPath = audioFile.local.path.takeIf { it.isNotEmpty() },
                                isStarred = false,
                                remoteUniqueId = audioFile.remote.uniqueId,
                                createdAt = message.date.toLong() * 1000
                            )
                        }
                        else -> null
                    }
                }
                Log.d("DriveRepo", "Mapped ${entities.size} valid entities for chat $chatId")
                scope.launch {
                    driveDao.deletePendingItems()
                    driveDao.refreshChatItems(chatId, entities)
                }
            } else {
                Log.e("DriveRepo", "GetChatHistory failed or returned non-Messages: ${result::class.java.simpleName}")
                if (result is TdApi.Error) {
                    Log.e("DriveRepo", "Error: ${result.code} - ${result.message}")
                }
            }
        }

        if (chatId == savedMessagesChatId && savedMessagesChatId != 0L) {
            telegramClient.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { result ->
                if (result is TdApi.Chats) {
                    result.chatIds.forEach { cid ->
                        telegramClient.send(TdApi.GetChat(cid)) { chatResult ->
                            if (chatResult is TdApi.Chat) {
                                val type = chatResult.type
                                if (type is TdApi.ChatTypeSupergroup && type.isChannel && cid != savedMessagesChatId) {
                                    telegramClient.send(TdApi.GetSupergroup(type.supergroupId)) { sgResult ->
                                        if (sgResult is TdApi.Supergroup) {
                                            val status = sgResult.status
                                            if (status is TdApi.ChatMemberStatusCreator || status is TdApi.ChatMemberStatusAdministrator) {
                                                scope.launch {
                                                    val existing = driveDao.getItemById(chatResult.id, savedMessagesChatId)
                                                    driveDao.insertItems(listOf(
                                                        DriveItemEntity(
                                                            id = chatResult.id,
                                                            name = chatResult.title,
                                                            size = 0,
                                                            mimeType = "folder",
                                                            telegramFileId = 0,
                                                            parentChatId = savedMessagesChatId,
                                                            isFolder = true,
                                                            isStarred = existing?.isStarred ?: false
                                                        )
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun createFolder(name: String) {
        telegramClient.send(TdApi.CreateNewSupergroupChat(name, false, true, "TeleDrive Folder", null, 0, false)) { result ->
            if (result is TdApi.Chat) {
                fetchFiles()
            }
        }
    }

    fun uploadFile(filePath: String, originalFileName: String, chatId: Long? = null) {
        val targetChatId = chatId ?: savedMessagesChatId
        if (targetChatId == 0L) return

        startTransferService()
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(filePath),
            null,
            false,
            TdApi.FormattedText(originalFileName, emptyArray())
        )
        telegramClient.send(TdApi.SendMessage(targetChatId, null, null, null, null, content)) { result ->
            if (result is TdApi.Message) {
                val msgContent = result.content
                if (msgContent is TdApi.MessageDocument) {
                    val doc = msgContent.document.document
                    
                    // Track for auto-deletion if it's in cache
                    if (filePath.contains(context.cacheDir.absolutePath)) {
                        deleteAfterUpload[doc.id] = filePath
                    }

                    transferRepository.addTransfer(
                        doc.id,
                        doc.remote.uniqueId,
                        originalFileName,
                        isDownload = false,
                        totalSize = doc.expectedSize
                    )
                }
            }
            fetchFiles(targetChatId)
        }
    }

    fun getSavedMessagesChatId(): Long = savedMessagesChatId

    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        Log.d("DriveRepo", "Requesting internal preview download for msgId: $messageId")
        telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val file = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo
                    is TdApi.MessageVideo -> content.video.video
                    is TdApi.MessageAudio -> content.audio.audio
                    else -> null
                }
                
                if (file != null) {
                    val msgFileId = file.id
                    val remoteUniqueId = file.remote.uniqueId
                    val expectedSize = file.expectedSize

                    if (!file.local.isDownloadingCompleted) {
                        val trackId = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_$msgFileId"
                        
                        // Penting: Daftarkan ke TransferRepository DAHULU
                        transferRepository.addTransfer(msgFileId, trackId, fileName, isDownload = true, totalSize = expectedSize)
                        
                        // Pastikan Service berjalan untuk memantau update
                        startTransferService()
                        
                        // Kirim perintah download ke TDLib
                        telegramClient.send(TdApi.DownloadFile(msgFileId, 32, 0, 0, false)) { downloadResult ->
                            if (downloadResult is TdApi.File) {
                                Log.d("DriveRepo", "Download started successfully for $trackId")
                            } else if (downloadResult is TdApi.Error) {
                                Log.e("DriveRepo", "DownloadFile error: ${downloadResult.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        Log.d("DriveRepo", "Requesting download: $fileName (msgId: $messageId)")
        
        telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val file = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo
                    is TdApi.MessageVideo -> content.video.video
                    is TdApi.MessageAudio -> content.audio.audio
                    else -> null
                }
                
                if (file == null) {
                    Log.e("DriveRepo", "Could not find file in message content")
                    return@send
                }

                val msgFileId = file.id
                val remoteUniqueId = file.remote.uniqueId
                val expectedSize = file.expectedSize
                
                Log.d("DriveRepo", "Found file info - fileId: $msgFileId, uniqueId: $remoteUniqueId, size: $expectedSize, isCompleted: ${file.local.isDownloadingCompleted}")

                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                    Log.d("DriveRepo", "File already downloaded locally: ${file.local.path}")
                    transferRepository.saveToPublicDownloads(file.local.path, fileName)
                    transferRepository.addTransfer(msgFileId, remoteUniqueId, fileName, isDownload = true, totalSize = expectedSize, isCompleted = true)
                    return@send
                }

                if (remoteUniqueId.isNotEmpty()) {
                    exportOnComplete[remoteUniqueId] = fileName
                    transferRepository.addTransfer(msgFileId, remoteUniqueId, fileName, isDownload = true, totalSize = expectedSize)
                    startTransferService()
                    Log.d("DriveRepo", "Starting DownloadFile for uniqueId: $remoteUniqueId")
                    telegramClient.send(TdApi.DownloadFile(msgFileId, 32, 0, 0, false)) { downloadResult ->
                        if (downloadResult is TdApi.Error) {
                            Log.e("DriveRepo", "DownloadFile failed for $remoteUniqueId: ${downloadResult.message}")
                        }
                    }
                } else if (msgFileId != 0) {
                    val tempId = "temp_$msgFileId"
                    exportOnComplete[tempId] = fileName
                    transferRepository.addTransfer(msgFileId, tempId, fileName, isDownload = true, totalSize = expectedSize)
                    startTransferService()
                    Log.d("DriveRepo", "Starting DownloadFile for tempId: $tempId")
                    telegramClient.send(TdApi.DownloadFile(msgFileId, 32, 0, 0, false)) { downloadResult ->
                        if (downloadResult is TdApi.Error) {
                            Log.e("DriveRepo", "DownloadFile failed for $tempId: ${downloadResult.message}")
                        }
                    }
                }
            } else {
                Log.e("DriveRepo", "GetMessage failed for download: ${result::class.java.simpleName}")
                if (result is TdApi.Error) {
                    Log.e("DriveRepo", "GetMessage Error Details: ${result.code} - ${result.message}")
                }
            }
        }
    }

    fun saveToPublicStorage(file: DriveItem.File) {
        file.localPath?.let { path ->
            transferRepository.saveToPublicDownloads(path, file.name)
        }
    }

    fun getTotalStorageUsed(): Flow<Long> {
        return driveDao.getTotalSize().map { it ?: 0L }
    }

    fun getInternalCacheSize(): Flow<Long> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val size = calculateDirectorySize(context.filesDir)
                emit(size)
                kotlinx.coroutines.delay(10000) // Update every 10s
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun calculateDirectorySize(directory: java.io.File): Long {
        var size: Long = 0
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
        }
        return size
    }

    fun clearInternalCache() {
        scope.launch {
            // Tell TDLib to optimize storage (delete all files)
            // Using the constructor: size, ttl, count, immunityDelay, fileTypes, chatIds, excludeChatIds, returnDeletedFileStatistics, chatLimit
            telegramClient.send(TdApi.OptimizeStorage(
                -1, // size: -1 means no limit (or 0 for clear all)
                0,  // ttl
                0,  // count
                0,  // immunityDelay
                null, // fileTypes (null means all)
                null, // chatIds
                null, // excludeChatIds
                true, // returnDeletedFileStatistics
                0     // chatLimit
            )) {
                fetchFiles() // Refresh to update localPaths to null
            }
        }
    }

    fun toggleStarred(item: DriveItem) {
        val newState = when (item) {
            is DriveItem.File -> !item.isStarred
            is DriveItem.Folder -> !item.isStarred
        }
        scope.launch {
            driveDao.updateStarred(item.id, item.parentChatId, newState)
        }
    }

    @OptIn(FlowPreview::class)
    fun getStarredItems(): Flow<List<DriveItem>> {
        return driveDao.getStarredItems().map { entities ->
            entities.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred)
                } else {
                    DriveItem.File(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.size,
                        entity.mimeType,
                        entity.telegramFileId,
                        entity.thumbnailPath,
                        entity.localPath,
                        entity.isStarred,
                        entity.remoteUniqueId ?: ""
                    )
                }
            }
        }.debounce(500)
    }

    fun getAllFiles(): Flow<List<DriveItem.File>> {
        return driveDao.getAllFiles().map { entities ->
            entities.map { entity ->
                DriveItem.File(
                    entity.id,
                    entity.parentChatId,
                    entity.name,
                    entity.size,
                    entity.mimeType,
                    entity.telegramFileId,
                    entity.thumbnailPath,
                    entity.localPath,
                    entity.isStarred,
                    entity.remoteUniqueId ?: ""
                )
            }
        }
    }

    fun permanentlyDeleteItems(chatId: Long, items: List<DriveItem>) {
        val messageIds = items.asSequence()
            .filterIsInstance<DriveItem.File>()
            .map { it.id }
            .toList()
        val folderIds = items.asSequence()
            .filterIsInstance<DriveItem.Folder>()
            .map { it.telegramChatId }
            .toList()

        if (messageIds.isNotEmpty()) {
            telegramClient.send(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), true)) {
                scope.launch {
                    messageIds.forEach { id ->
                        driveDao.deleteItemCompletely(id, chatId)
                    }
                }
            }
        }

        folderIds.forEach { fid ->
            telegramClient.send(TdApi.DeleteChat(fid)) {
                scope.launch {
                    driveDao.deleteItemsByChat(fid)
                    // If it was a folder in saved messages, delete it from there too
                    driveDao.deleteItemCompletely(fid, savedMessagesChatId)
                }
            }
        }
    }

    fun downloadFolderContents(folderChatId: Long) {
        telegramClient.send(TdApi.GetChatHistory(folderChatId, 0, 0, 1000, false)) { result ->
            if (result is TdApi.Messages) {
                result.messages.forEach { message ->
                    val fileInfo = when (val content = message.content) {
                        is TdApi.MessageDocument -> Pair(content.document.document, content.document.fileName)
                        is TdApi.MessagePhoto -> Pair(content.photo.sizes.lastOrNull()?.photo, "Photo_${message.id}.jpg")
                        is TdApi.MessageVideo -> Pair(content.video.video, content.video.fileName)
                        is TdApi.MessageAudio -> Pair(content.audio.audio, content.audio.fileName)
                        else -> null
                    }
                    
                    fileInfo?.let { (file, fileName) ->
                        downloadFile(message.id, folderChatId, fileName)
                    }
                }
            }
        }
    }

    fun moveFolderContentsAndDelete(fromFolderChatId: Long, toChatId: Long) {
        // Fetch a large number of messages to avoid data loss during move.
        // In a real-world scenario, this should be paginated until all messages are moved.
        telegramClient.send(TdApi.GetChatHistory(fromFolderChatId, 0, 0, 1000, false)) { result ->
            if (result is TdApi.Messages) {
                val messageIds = result.messages.map { it.id }.toLongArray()
                if (messageIds.isNotEmpty()) {
                    val options = TdApi.MessageSendOptions().apply {
                        disableNotification = true
                        fromBackground = true
                    }
                    telegramClient.send(TdApi.ForwardMessages(
                        toChatId,
                        null,
                        fromFolderChatId,
                        messageIds,
                        options,
                        false,
                        false
                    )) { forwardResult ->
                        if (forwardResult is TdApi.Messages) {
                            // After forwarding, delete the original folder (supergroup)
                            telegramClient.send(TdApi.DeleteChat(fromFolderChatId)) {
                                scope.launch {
                                    driveDao.deleteItemsByChat(fromFolderChatId)
                                    driveDao.deleteItemCompletely(fromFolderChatId, savedMessagesChatId)
                                    fetchFiles(toChatId)
                                }
                            }
                        } else if (forwardResult is TdApi.Error) {
                            Log.e("DriveRepo", "Failed to forward messages: ${forwardResult.message}")
                        }
                    }
                } else {
                    // Empty folder, just delete it
                    telegramClient.send(TdApi.DeleteChat(fromFolderChatId)) {
                        scope.launch {
                            driveDao.deleteItemCompletely(fromFolderChatId, savedMessagesChatId)
                            fetchFiles(toChatId)
                        }
                    }
                }
            }
        }
    }

    fun moveItems(fromChatId: Long, toChatId: Long, messageIds: List<Long>) {
        val options = TdApi.MessageSendOptions().apply {
            disableNotification = true
            fromBackground = true
        }
        telegramClient.send(TdApi.ForwardMessages(
            toChatId,
            null,
            fromChatId,
            messageIds.toLongArray(),
            options,
            false,
            false
        )) { result ->
            if (result is TdApi.Messages) {
                // Only delete the messages that were successfully forwarded
                val successfulOriginalIds = mutableListOf<Long>()
                result.messages.forEachIndexed { index, message ->
                    if (message != null && index < messageIds.size) {
                        successfulOriginalIds.add(messageIds[index])
                    }
                }
                
                if (successfulOriginalIds.isNotEmpty()) {
                    telegramClient.send(TdApi.DeleteMessages(fromChatId, successfulOriginalIds.toLongArray(), true)) {
                        fetchFiles(fromChatId)
                        fetchFiles(toChatId)
                    }
                }
            } else if (result is TdApi.Error) {
                Log.e("DriveRepo", "Forward failed: ${result.message}")
            }
        }
    }
}
