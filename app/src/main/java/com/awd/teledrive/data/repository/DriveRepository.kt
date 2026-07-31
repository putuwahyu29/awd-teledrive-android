package com.awd.teledrive.data.repository

import android.content.Context
import android.widget.Toast
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.CloudBackup
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.domain.model.TeleDriveManifest
import com.awd.teledrive.domain.model.VirtualFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val driveDao: DriveDao,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedMessagesChatId: Long = 0
    private val _savedMessagesChatIdFlow = MutableStateFlow(0L)
    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    init {
        scope.launch {
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                if (file.local.isDownloadingCompleted) {
                    driveDao.updateLocalPathByUniqueId(file.remote.uniqueId, file.local.path)
                    driveDao.updateThumbnailPathByUniqueId(file.remote.uniqueId, file.local.path)
                }
            }
        }
    }

    private val MANIFEST_PREFIX = "#TELEDRIVE_MANIFEST"
    private val MANIFEST_BACKUP_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentManifest = TeleDriveManifest()
    private var manifestMessageId: Long = 0

    fun fetchCloudManifest(onComplete: () -> Unit = {}) {
        if (savedMessagesChatId == 0L) {
            onComplete()
            return
        }
        val searchRequest = TdApi.SearchChatMessages()
        searchRequest.chatId = savedMessagesChatId
        searchRequest.query = MANIFEST_PREFIX
        searchRequest.limit = 10
        
        telegramClient.send(searchRequest) { result ->
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                processManifestMessages(result.messages.toList())
                onComplete()
            } else {
                telegramClient.send(TdApi.GetChatHistory(savedMessagesChatId, 0, 0, 100, false)) { history ->
                    if (history is TdApi.Messages) processManifestMessages(history.messages.toList())
                    onComplete()
                }
            }
        }
    }

    private fun processManifestMessages(messages: List<TdApi.Message>) {
        val manifestMsg = messages.find { msg ->
            val content = msg.content
            content is TdApi.MessageText && content.text.text.startsWith(MANIFEST_PREFIX) && 
                !content.text.text.startsWith(MANIFEST_BACKUP_PREFIX)
        } ?: messages.firstOrNull()
        
        if (manifestMsg == null) return
        manifestMessageId = manifestMsg.id
        val content = manifestMsg.content
        if (content is TdApi.MessageText) {
            val text = content.text.text
            val jsonStartIndex = text.indexOf('{')
            if (jsonStartIndex == -1) return
            val jsonStr = text.substring(jsonStartIndex).trim()
            try {
                currentManifest = json.decodeFromString<TeleDriveManifest>(jsonStr)
                syncVirtualFoldersToDb()
                currentManifest.secureFolderChatIds.forEach { chatId -> loadAllDriveItems(chatId) }
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Metadata sinkron: ${currentManifest.virtualFolders.size} folder", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { }
        }
    }

    fun exportManifestToJson(): String = json.encodeToString(currentManifest)

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            val imported = json.decodeFromString<TeleDriveManifest>(jsonStr)
            currentManifest = imported
            saveCloudManifest()
            syncVirtualFoldersToDb()
            true
        } catch (e: Exception) { false }
    }

    fun fetchCloudBackups(onResult: (List<CloudBackup>) -> Unit) {
        if (savedMessagesChatId == 0L) {
            onResult(emptyList())
            return
        }
        val searchRequest = TdApi.SearchChatMessages()
        searchRequest.chatId = savedMessagesChatId
        searchRequest.query = MANIFEST_BACKUP_PREFIX
        searchRequest.limit = 50
        
        telegramClient.send(searchRequest) { result ->
            if (result is TdApi.Messages) {
                val backups = result.messages.mapNotNull { msg ->
                    val content = msg.content
                    if (content is TdApi.MessageText && content.text.text.startsWith(MANIFEST_BACKUP_PREFIX)) {
                        val text = content.text.text
                        val jsonStartIndex = text.indexOf('{')
                        if (jsonStartIndex != -1) {
                            try {
                                val manifest = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                                CloudBackup(msg.id, msg.date.toLong() * 1000, manifest.virtualFolders.size)
                            } catch (e: Exception) { null }
                        } else null
                    } else null
                }
                onResult(backups)
            } else {
                onResult(emptyList())
            }
        }
    }

    fun restoreManifestFromMessage(messageId: Long, onResult: (Boolean) -> Unit) {
        telegramClient.send(TdApi.GetMessage(savedMessagesChatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val content = result.content
                if (content is TdApi.MessageText) {
                    val text = content.text.text
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        try {
                            val imported = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex))
                            currentManifest = imported
                            saveCloudManifest()
                            syncVirtualFoldersToDb()
                            onResult(true)
                        } catch (e: Exception) { onResult(false) }
                    } else onResult(false)
                } else onResult(false)
            } else onResult(false)
        }
    }

    fun createManualCloudBackup() {
        if (savedMessagesChatId == 0L) return
        val manifestJson = json.encodeToString(currentManifest)
        val text = "$MANIFEST_BACKUP_PREFIX${System.currentTimeMillis()}\n\n$manifestJson"
        val formatted = TdApi.FormattedText(text, emptyArray())
        telegramClient.send(TdApi.SendMessage(savedMessagesChatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true)))
    }

    private fun syncVirtualFoldersToDb() {
        scope.launch {
            val vFolders = currentManifest.virtualFolders.values.toList()
            val entities = vFolders.map { vf ->
                val existing = driveDao.getVirtualFolderById(vf.id)
                var targetChat: Long = savedMessagesChatId
                var currentPid = vf.parentId
                var depth = 0
                while (currentPid != "0" && currentPid.isNotEmpty() && depth < 10) {
                    if (currentPid.startsWith("vf_")) {
                        currentPid = currentManifest.virtualFolders[currentPid]?.parentId ?: "0"
                    } else {
                        targetChat = currentPid.toLongOrNull() ?: savedMessagesChatId
                        break
                    }
                    depth++
                }
                DriveItemEntity(existing?.id ?: vf.id.replace("vf_", "").filter { it.isDigit() }.take(12).toLongOrNull() ?: System.currentTimeMillis(), vf.name, 0, "virtual_folder", 0, targetChat, true, isVirtual = true, virtualId = vf.id, virtualParentId = if (vf.parentId.isEmpty()) "0" else vf.parentId, createdAt = vf.createdAt * 1000, isStarred = existing?.isStarred ?: false, isSecure = vf.isSecure)
            }
            driveDao.insertItems(entities)
        }
    }

    fun createVirtualFolder(name: String, parentId: String = "0", isSecure: Boolean = false) {
        val id = "vf_${System.nanoTime()}"
        val folder = VirtualFolder(id, name, parentId, System.currentTimeMillis() / 1000, "virtual_folder", isSecure)
        val updatedFolders = currentManifest.virtualFolders.toMutableMap()
        updatedFolders[id] = folder
        currentManifest = currentManifest.copy(virtualFolders = updatedFolders)
        saveCloudManifest()
        syncVirtualFoldersToDb()
    }

    private fun saveCloudManifest() {
        if (savedMessagesChatId == 0L) return
        val manifestJson = json.encodeToString(currentManifest)
        val text = "$MANIFEST_PREFIX\n\n$manifestJson"
        val formatted = TdApi.FormattedText(text, emptyArray())
        
        if (manifestMessageId != 0L) {
            telegramClient.send(TdApi.EditMessageText(savedMessagesChatId, manifestMessageId, null, TdApi.InputMessageText(formatted, null, true)))
        } else {
            telegramClient.send(TdApi.SendMessage(savedMessagesChatId, null, null, null, null, TdApi.InputMessageText(formatted, null, true))) { result ->
                if (result is TdApi.Message) manifestMessageId = result.id
            }
        }
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
                    entity.remoteUniqueId ?: "",
                    entity.splitGroupId,
                    entity.totalParts,
                    entity.virtualParentId,
                    entity.isEncrypted
                )
            }
        }
    }

    fun getStarredItems(): Flow<List<DriveItem>> {
        return driveDao.getStarredItems().map { entities ->
            entities.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(
                        entity.id,
                        entity.parentChatId,
                        entity.name,
                        entity.id,
                        entity.isStarred,
                        entity.isVirtual,
                        entity.virtualId,
                        entity.virtualParentId,
                        entity.isSecure
                    )
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
                        entity.remoteUniqueId ?: "",
                        entity.splitGroupId,
                        entity.totalParts,
                        entity.virtualParentId,
                        entity.isEncrypted
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun getItems(chatId: Long?, virtualParentId: String = "0", searchQuery: String = ""): Flow<List<DriveItem>> {
        val targetChatId = chatId ?: savedMessagesChatId
        val flow = if (searchQuery.isNotEmpty()) driveDao.searchGlobal(searchQuery)
        else if (targetChatId != 0L) driveDao.getItemsFlow(targetChatId, virtualParentId)
        else kotlinx.coroutines.flow.flowOf(emptyList())

        return flow.map { entities ->
            entities.sortedByDescending { it.createdAt }.map { entity ->
                if (entity.isFolder) DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, entity.isVirtual, entity.virtualId, entity.virtualParentId, entity.isSecure)
                else DriveItem.File(entity.id, entity.parentChatId, entity.name, entity.size, entity.mimeType, entity.telegramFileId, entity.thumbnailPath, entity.localPath, entity.isStarred, entity.remoteUniqueId ?: "", entity.splitGroupId, entity.totalParts, entity.virtualParentId, entity.isEncrypted)
            }
        }.debounce(500)
    }

    fun fetchFiles(chatId: Long? = null) {
        val targetChatId = chatId ?: savedMessagesChatId
        if (targetChatId == 0L) {
            telegramClient.send(TdApi.GetMe()) { result ->
                if (result is TdApi.User) {
                    savedMessagesChatId = result.id
                    _savedMessagesChatIdFlow.value = result.id
                    fetchCloudManifest { syncFolders { loadAllDriveItems(savedMessagesChatId) } }
                }
            }
        } else {
            fetchCloudManifest { syncFolders { loadAllDriveItems(targetChatId) } }
        }
    }

    private fun syncFolders(onComplete: () -> Unit = {}) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, "Sinkronisasi folder...", Toast.LENGTH_SHORT).show()
        }
        val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        var listsProcessed = 0
        chatLists.forEach { chatList ->
            telegramClient.send(TdApi.GetChats(chatList, 1000)) { result ->
                if (result is TdApi.Chats) {
                    if (result.chatIds.isEmpty()) {
                        listsProcessed++
                        if (listsProcessed == chatLists.size) onComplete()
                        return@send
                    }
                    var chatsProcessed = 0
                    result.chatIds.forEach { cid ->
                        telegramClient.send(TdApi.GetChat(cid)) { chatResult ->
                            if (chatResult is TdApi.Chat) processDiscoveredFolder(chatResult)
                            chatsProcessed++
                            if (chatsProcessed == result.chatIds.size) {
                                listsProcessed++
                                if (listsProcessed == chatLists.size) onComplete()
                            }
                        }
                    }
                } else {
                    listsProcessed++
                    if (listsProcessed == chatLists.size) onComplete()
                }
            }
        }
    }

    private fun loadAllDriveItems(chatId: Long) {
        fetchHistoryRecursively(chatId, 0, mutableListOf())
    }

    private fun fetchHistoryRecursively(chatId: Long, fromMessageId: Long, allFetchedIds: MutableList<Long>) {
        telegramClient.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                if (result.messages.isEmpty()) {
                    // Selesai memuat history chat ini. Hapus item yang sudah tidak ada di Telegram.
                    scope.launch {
                        // Jangan hapus folder atau item virtual saat sinkronisasi file fisik
                        driveDao.deleteFilesByChatNotInList(chatId, allFetchedIds)
                    }
                    return@send
                }

                val entities = result.messages.mapNotNull { message ->
                    if (message.sendingState != null) return@mapNotNull null
                    mapMessageToEntity(chatId, message)
                }
                
                allFetchedIds.addAll(entities.map { it.id })

                scope.launch {
                    // Masukkan secara bertahap agar user bisa melihat file yang muncul
                    driveDao.insertItemsPreservingStarred(entities)
                }

                // Ambil batch berikutnya (limit 5000 item per chat untuk keamanan/performa)
                if (allFetchedIds.size < 5000) {
                    fetchHistoryRecursively(chatId, result.messages.last().id, allFetchedIds)
                } else {
                    scope.launch {
                        driveDao.deleteFilesByChatNotInList(chatId, allFetchedIds)
                    }
                }
            }
        }
    }

    private fun mapMessageToEntity(chatId: Long, message: TdApi.Message): DriveItemEntity? {
        val isSecureFolder = currentManifest.secureFolderChatIds.contains(chatId)
        return when (val content = message.content) {
            is TdApi.MessageDocument -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.document.fileName) else splitInfo?.originalName ?: content.document.fileName
                val mimeType = if (content.document.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.document.mimeType
                
                val thumb = content.document.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }

                DriveItemEntity(
                    message.id, cleanName, content.document.document.expectedSize, mimeType, content.document.document.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.document.document.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.document.document.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = parseVirtualFolderTag(caption) ?: "0",
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessagePhoto -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val sizes = content.photo.sizes
                val thumb = sizes.firstOrNull()
                val full = sizes.lastOrNull()?.photo
                
                if (thumb != null && thumb.photo.local.path.isEmpty() && thumb.photo.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.photo.id, 1, 0, 0, false))
                }

                DriveItemEntity(
                    message.id, "Photo_${message.id}.jpg", full?.expectedSize ?: 0L, "image/jpeg", full?.id ?: 0, chatId, false,
                    thumbnailPath = thumb?.photo?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = full?.local?.path?.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.photo?.id,
                    remoteUniqueId = full?.remote?.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.photo?.remote?.uniqueId,
                    virtualParentId = parseVirtualFolderTag(caption) ?: "0",
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageVideo -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.video.fileName) else splitInfo?.originalName ?: content.video.fileName
                val mimeType = if (content.video.mimeType == "application/octet-stream") FileUtils.getMimeType(cleanName) else content.video.mimeType
                
                val thumb = content.video.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }

                DriveItemEntity(
                    message.id, cleanName, content.video.video.expectedSize, mimeType, content.video.video.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.video.video.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.video.video.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = parseVirtualFolderTag(caption) ?: "0",
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageAudio -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                val splitInfo = parseSplitMetadata(caption)
                val cleanName = if (isEncrypted) cleanEncryptedFileName(splitInfo?.originalName ?: content.audio.fileName) else splitInfo?.originalName ?: content.audio.fileName
                val mimeType = if (content.audio.mimeType == "application/octet-stream" || content.audio.mimeType.isEmpty()) FileUtils.getMimeType(cleanName) else content.audio.mimeType
                DriveItemEntity(
                    message.id, cleanName, content.audio.audio.expectedSize, mimeType, content.audio.audio.id, chatId, false,
                    localPath = content.audio.audio.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    splitGroupId = splitInfo?.groupId,
                    partIndex = splitInfo?.partIndex ?: 0,
                    totalParts = splitInfo?.totalParts ?: 1,
                    virtualParentId = parseVirtualFolderTag(caption) ?: "0",
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted,
                    remoteUniqueId = content.audio.audio.remote.uniqueId
                )
            }
            is TdApi.MessageAnimation -> {
                val caption = content.caption.text
                val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                
                val thumb = content.animation.thumbnail
                if (thumb != null && thumb.file.local.path.isEmpty() && thumb.file.local.canBeDownloaded) {
                    telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                }

                DriveItemEntity(
                    message.id, cleanEncryptedFileName("Animation_${message.id}.mp4"), content.animation.animation.expectedSize, "video/mp4", content.animation.animation.id, chatId, false,
                    thumbnailPath = thumb?.file?.local?.path?.takeIf { it.isNotEmpty() },
                    localPath = content.animation.animation.local.path.takeIf { it.isNotEmpty() },
                    createdAt = message.date.toLong() * 1000,
                    thumbnailFileId = thumb?.file?.id,
                    remoteUniqueId = content.animation.animation.remote.uniqueId,
                    thumbnailRemoteUniqueId = thumb?.file?.remote?.uniqueId,
                    virtualParentId = parseVirtualFolderTag(caption) ?: "0",
                    isSecure = isSecureFolder,
                    isEncrypted = isEncrypted
                )
            }
            is TdApi.MessageVoiceNote -> {
                DriveItemEntity(message.id, "Voice_${message.id}.ogg", content.voiceNote.voice.expectedSize, "audio/ogg", content.voiceNote.voice.id, chatId, false, localPath = content.voiceNote.voice.local.path.takeIf { it.isNotEmpty() }, createdAt = message.date.toLong() * 1000, virtualParentId = "0", isSecure = isSecureFolder, isEncrypted = isSecureFolder, remoteUniqueId = content.voiceNote.voice.remote.uniqueId)
            }
            is TdApi.MessageVideoNote -> {
                DriveItemEntity(message.id, "VideoNote_${message.id}.mp4", content.videoNote.video.expectedSize, "video/mp4", content.videoNote.video.id, chatId, false, localPath = content.videoNote.video.local.path.takeIf { it.isNotEmpty() }, createdAt = message.date.toLong() * 1000, virtualParentId = "0", isSecure = isSecureFolder, isEncrypted = isSecureFolder, remoteUniqueId = content.videoNote.video.remote.uniqueId)
            }
            else -> null
        }
    }

    private fun processDiscoveredFolder(chat: TdApi.Chat) {
        val type = chat.type
        if (type is TdApi.ChatTypeSupergroup) {
            val isKnownSecure = currentManifest.secureFolderChatIds.contains(chat.id)
            val isPrivateGroup = !type.isChannel
            telegramClient.send(TdApi.GetSupergroup(type.supergroupId)) { sgResult ->
                if (sgResult is TdApi.Supergroup) {
                    val status = sgResult.status
                    val isCreator = status is TdApi.ChatMemberStatusCreator
                    val isSecure = isKnownSecure || (isPrivateGroup && isCreator)
                    if (isCreator || status is TdApi.ChatMemberStatusAdministrator) {
                        scope.launch {
                            val existing = driveDao.getItemById(chat.id, savedMessagesChatId)
                            driveDao.insertItems(listOf(DriveItemEntity(chat.id, chat.title, 0, "folder", 0, savedMessagesChatId, true, isStarred = existing?.isStarred ?: false, createdAt = existing?.createdAt ?: System.currentTimeMillis(), isSecure = isSecure)))
                        }
                    }
                }
            }
        } else if (type is TdApi.ChatTypeBasicGroup) {
            val isKnownSecure = currentManifest.secureFolderChatIds.contains(chat.id)
            telegramClient.send(TdApi.GetBasicGroup(type.basicGroupId)) { bgResult ->
                if (bgResult is TdApi.BasicGroup) {
                    val status = bgResult.status
                    val isCreator = status is TdApi.ChatMemberStatusCreator
                    val isSecure = isKnownSecure || isCreator
                    if (isCreator || status is TdApi.ChatMemberStatusAdministrator) {
                        scope.launch {
                            val existing = driveDao.getItemById(chat.id, savedMessagesChatId)
                            driveDao.insertItems(listOf(DriveItemEntity(chat.id, chat.title, 0, "folder", 0, savedMessagesChatId, true, isStarred = existing?.isStarred ?: false, createdAt = existing?.createdAt ?: System.currentTimeMillis(), isSecure = isSecure)))
                        }
                    }
                }
            }
        }
    }

    fun createFolder(name: String) {
        telegramClient.send(TdApi.CreateNewSupergroupChat(name, false, true, "TeleDrive Folder", null, 0, false)) { result ->
            if (result is TdApi.Chat) fetchFiles()
        }
    }

    fun createSecureFolder(name: String) {
        telegramClient.send(TdApi.CreateNewSupergroupChat(name, false, false, "TeleDrive Secure Folder", null, 0, false)) { result ->
            if (result is TdApi.Chat) {
                val updatedSecureIds = currentManifest.secureFolderChatIds.toMutableSet()
                updatedSecureIds.add(result.id)
                currentManifest = currentManifest.copy(secureFolderChatIds = updatedSecureIds, updatedAt = System.currentTimeMillis() / 1000)
                saveCloudManifest()
                fetchFiles()
            }
        }
    }

    fun uploadFile(filePath: String, originalFileName: String, chatId: Long? = null, virtualFolderId: String? = null) {
        val targetChatId = chatId ?: savedMessagesChatId
        if (targetChatId == 0L) return
        val vfTag = if (virtualFolderId != null && virtualFolderId != "0") "[VF:$virtualFolderId]" else ""
        val sourceFile = java.io.File(filePath)
        if (sourceFile.length() > FileUtils.MAX_FILE_SIZE) {
            if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) return
            startTransferService()
            scope.launch {
                val placeholderId = "prep_${System.currentTimeMillis()}"
                transferRepository.addTransfer(0, placeholderId, originalFileName, false, sourceFile.length(), status = "Memecah file...")
                try {
                    val parts = FileUtils.splitFile(context, sourceFile) { progress ->
                        transferRepository.updateTransferManual(placeholderId, progress, "Memecah file... ${(progress * 100).toInt()}%")
                    }
                    transferRepository.removeTransfer(placeholderId)
                    val groupId = FileUtils.generateSplitGroupId()
                    parts.forEachIndexed { index, part ->
                        val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$originalFileName]$vfTag"
                        uploadSinglePart(part.absolutePath, originalFileName, targetChatId, caption)
                    }
                } catch (e: Exception) {
                    transferRepository.updateTransferManual(placeholderId, 0f, "Gagal memecah file")
                }
            }
        } else {
            uploadSinglePart(filePath, originalFileName, targetChatId, if (vfTag.isNotEmpty()) "$originalFileName $vfTag" else null)
        }
    }

    private fun uploadSinglePart(filePath: String, originalFileName: String, targetChatId: Long, caption: String? = null) {
        startTransferService()
        val isSecure = isChatSecure(targetChatId)
        val password = secureSessionManager.decryptedPassword.value
        if (isSecure && password == null) {
            scope.launch(Dispatchers.Main) { Toast.makeText(context, "Buka Mode Aman untuk mengunggah", Toast.LENGTH_LONG).show() }
            return
        }
        scope.launch(Dispatchers.IO) {
            var finalPath = filePath
            var finalCaption = caption ?: originalFileName
            var tempEncFile: java.io.File? = null
            if (isSecure && password != null) {
                try {
                    val originalFile = java.io.File(filePath)
                    tempEncFile = java.io.File(context.cacheDir, "enc_${System.nanoTime()}_${originalFile.name}")
                    encryptionManager.encryptFile(originalFile, tempEncFile, password)
                    finalPath = tempEncFile.absolutePath
                    finalCaption = if (caption != null) "$caption [ENC]" else "$originalFileName [ENC]"
                } catch (e: Exception) { return@launch }
            }
            telegramClient.send(TdApi.SendMessage(targetChatId, null, null, null, null, TdApi.InputMessageDocument(TdApi.InputFileLocal(finalPath), null, false, TdApi.FormattedText(finalCaption, emptyArray())))) { result ->
                tempEncFile?.delete()
                if (result is TdApi.Message) fetchFiles(targetChatId)
            }
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        startTransferService()
        telegramClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val fileId = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.id
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.id ?: 0
                    is TdApi.MessageVideo -> content.video.video.id
                    is TdApi.MessageAudio -> content.audio.audio.id
                    else -> 0
                }
                if (fileId != 0) {
                    val isEncrypted = when (val content = result.content) {
                        is TdApi.MessageDocument -> content.caption.text.contains("[ENC]")
                        is TdApi.MessagePhoto -> content.caption.text.contains("[ENC]")
                        is TdApi.MessageVideo -> content.caption.text.contains("[ENC]")
                        is TdApi.MessageAudio -> content.caption.text.contains("[ENC]")
                        else -> false
                    } || isChatSecure(chatId)
                    if (isEncrypted && secureSessionManager.decryptedPassword.value == null) {
                        scope.launch(Dispatchers.Main) { Toast.makeText(context, "Buka Mode Aman untuk mengunduh", Toast.LENGTH_LONG).show() }
                        return@send
                    }
                    telegramClient.send(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                }
            }
        }
    }

    fun downloadForPreview(messageId: Long, chatId: Long, fileName: String) {
        downloadFile(messageId, chatId, fileName)
    }

    fun saveToPublicStorage(file: DriveItem.File) {
        val source = file.localPath?.let { java.io.File(it) } ?: return
        if (!source.exists()) return
        scope.launch(Dispatchers.IO) {
            try {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dest = java.io.File(downloadDir, file.name)
                if (file.isEncrypted) {
                    val password = secureSessionManager.decryptedPassword.value
                    if (password != null) encryptionManager.decryptFile(source, dest, password)
                } else {
                    source.copyTo(dest, overwrite = true)
                }
                scope.launch(Dispatchers.Main) { Toast.makeText(context, "File disimpan ke Downloads", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { }
        }
    }

    fun toggleStarred(item: DriveItem) {
        scope.launch { driveDao.updateStarred(item.id, item.parentChatId, !item.isStarred) }
    }

    fun renameItem(item: DriveItem, newName: String) {
        scope.launch {
            if (item is DriveItem.Folder) {
                if (item.isVirtual) {
                    val virtualId = item.virtualId ?: return@launch
                    val updatedFolders = currentManifest.virtualFolders.toMutableMap()
                    val folder = updatedFolders[virtualId] ?: return@launch
                    updatedFolders[virtualId] = folder.copy(name = newName)
                    currentManifest = currentManifest.copy(virtualFolders = updatedFolders)
                    saveCloudManifest()
                } else {
                    telegramClient.send(TdApi.SetChatTitle(item.telegramChatId, newName)) {}
                }
            } else if (item is DriveItem.File) {
                telegramClient.send(TdApi.GetMessage(item.parentChatId, item.id)) { result ->
                    if (result is TdApi.Message) {
                        val currentCaption = when (val content = result.content) {
                            is TdApi.MessageDocument -> content.caption.text
                            is TdApi.MessagePhoto -> content.caption.text
                            is TdApi.MessageVideo -> content.caption.text
                            is TdApi.MessageAudio -> content.caption.text
                            else -> ""
                        }
                        val tagRegex = Regex("\\[(ENC|TD_SPLIT|VF:|ID:|PART:|NAME:).*?\\]")
                        val tags = tagRegex.findAll(currentCaption).map { it.value }.toList()
                        val updatedTags = tags.map { tag ->
                            if (tag.startsWith("[TD_SPLIT|") && tag.contains("NAME:")) tag.replace(Regex("NAME:.*?(?=\\]|\\|)"), "NAME:$newName")
                            else tag
                        }
                        val newCaptionText = if (updatedTags.isNotEmpty()) {
                            if (updatedTags.any { it.startsWith("[TD_SPLIT") }) updatedTags.joinToString("")
                            else "$newName ${updatedTags.joinToString("")}"
                        } else newName
                        telegramClient.send(TdApi.EditMessageCaption(item.parentChatId, item.id, null, TdApi.FormattedText(newCaptionText, emptyArray()), false)) { editResult ->
                            if (editResult is TdApi.Message) scope.launch { driveDao.renameItem(item.id, item.parentChatId, newName) }
                        }
                    }
                }
            }
            driveDao.renameItem(item.id, item.parentChatId, newName)
        }
    }

    fun permanentlyDeleteItems(chatId: Long, items: List<DriveItem>) {
        scope.launch {
            val messageIds = items.filterIsInstance<DriveItem.File>().map { it.id }
            val physicalFolderIds = items.filterIsInstance<DriveItem.Folder>().filter { !it.isVirtual }.map { it.telegramChatId }
            val virtualFolderIds = items.filterIsInstance<DriveItem.Folder>().filter { it.isVirtual }.mapNotNull { it.virtualId }
            if (messageIds.isNotEmpty()) {
                telegramClient.send(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), true)) {
                    scope.launch { messageIds.forEach { id -> driveDao.deleteItemCompletely(id, chatId) } }
                }
            }
            physicalFolderIds.forEach { fid ->
                telegramClient.send(TdApi.DeleteChat(fid)) {
                    scope.launch {
                        driveDao.deleteItemsByChat(fid)
                        driveDao.deleteItemCompletely(fid, savedMessagesChatId)
                    }
                }
            }
            if (virtualFolderIds.isNotEmpty()) {
                val updatedFolders = currentManifest.virtualFolders.toMutableMap()
                virtualFolderIds.forEach { vid -> deleteVirtualFolderRecursive(chatId, vid, updatedFolders) }
                currentManifest = currentManifest.copy(virtualFolders = updatedFolders, updatedAt = System.currentTimeMillis() / 1000)
                saveCloudManifest()
            }
        }
    }

    private suspend fun deleteVirtualFolderRecursive(chatId: Long, virtualId: String, manifestMap: MutableMap<String, VirtualFolder>) {
        val children = driveDao.getItemsByVirtualParentSync(virtualId)
        val fileIds = children.filter { !it.isFolder }.map { it.id }
        val subFolders = children.filter { it.isFolder && it.isVirtual }
        if (fileIds.isNotEmpty()) telegramClient.send(TdApi.DeleteMessages(chatId, fileIds.toLongArray(), true)) {}
        subFolders.forEach { sf -> sf.virtualId?.let { deleteVirtualFolderRecursive(chatId, it, manifestMap) } }
        manifestMap.remove(virtualId)
        val entity = driveDao.getVirtualFolderById(virtualId)
        if (entity != null) driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
    }

    fun downloadFolderContents(folderChatId: Long) {
        telegramClient.send(TdApi.GetChatHistory(folderChatId, 0, 0, 1000, false)) { result ->
            if (result is TdApi.Messages) {
                result.messages.forEach { message ->
                    val fileId = when (val content = message.content) {
                        is TdApi.MessageDocument -> content.document.document.id
                        is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.id ?: 0
                        is TdApi.MessageVideo -> content.video.video.id
                        is TdApi.MessageAudio -> content.audio.audio.id
                        else -> 0
                    }
                    if (fileId != 0) telegramClient.send(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                }
            }
        }
    }

    fun moveItems(fromChatId: Long, toChatId: Long, messageIds: List<Long>) {
        val options = TdApi.MessageSendOptions(null, false, false, false, false, 0L, false, null, 0L, 0, false)
        telegramClient.send(TdApi.ForwardMessages(toChatId, null, fromChatId, messageIds.toLongArray(), options, false, false)) { result ->
            if (result is TdApi.Messages) {
                telegramClient.send(TdApi.DeleteMessages(fromChatId, messageIds.toLongArray(), true)) {
                    scope.launch { messageIds.forEach { id -> driveDao.deleteItemCompletely(id, fromChatId) } }
                    fetchFiles(toChatId)
                }
            }
        }
    }

    fun moveFolderContentsAndDelete(fromFolderChatId: Long, toChatId: Long) {
        val options = TdApi.MessageSendOptions(null, false, false, false, false, 0L, false, null, 0L, 0, false)
        telegramClient.send(TdApi.GetChatHistory(fromFolderChatId, 0, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                val messageIds = result.messages.map { it.id }.toLongArray()
                if (messageIds.isNotEmpty()) {
                    telegramClient.send(TdApi.ForwardMessages(toChatId, null, fromFolderChatId, messageIds, options, false, false)) { forwardResult ->
                        if (forwardResult is TdApi.Messages) {
                            telegramClient.send(TdApi.DeleteChat(fromFolderChatId)) {
                                scope.launch {
                                    driveDao.deleteItemsByChat(fromFolderChatId)
                                    driveDao.deleteItemCompletely(fromFolderChatId, savedMessagesChatId)
                                    fetchFiles(toChatId)
                                }
                            }
                        }
                    }
                } else {
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

    fun getInternalCacheSize(): Flow<Long> = MutableStateFlow(0L) // Simplified

    fun clearDatabaseLocalPaths(onlyThumbnails: Boolean = false, onlyFiles: Boolean = false) {
        scope.launch {
            if (onlyThumbnails) driveDao.clearAllThumbnailPaths()
            else if (onlyFiles) driveDao.clearAllLocalPaths()
            else {
                driveDao.clearAllLocalPaths()
                driveDao.clearAllThumbnailPaths()
            }
        }
    }

    fun getCloudFileTypeStats(): Flow<List<DriveDao.FileTypeStat>> = driveDao.getCloudFileTypeStats()
    fun getTotalStorageUsed(): Flow<Long> = driveDao.getTotalSize().map { it ?: 0L }
    fun isChatSecure(chatId: Long): Boolean = currentManifest.secureFolderChatIds.contains(chatId)
    fun isVirtualFolderSecure(virtualId: String): Boolean = currentManifest.virtualFolders[virtualId]?.isSecure == true
    fun getParentVirtualId(virtualId: String): String = currentManifest.virtualFolders[virtualId]?.parentId ?: "0"
    fun getSavedMessagesChatId(): Long = savedMessagesChatId

    private fun startTransferService() {
        // TransferService placeholder
    }

    private fun parseSplitMetadata(caption: String): SplitMetadata? {
        if (!caption.contains("[TD_SPLIT|")) return null
        val id = caption.substringAfter("ID:").substringBefore("|")
        val partInfo = caption.substringAfter("PART:").substringBefore("|")
        val partIndex = partInfo.substringBefore("/").toIntOrNull() ?: 0
        val totalParts = partInfo.substringAfter("/").toIntOrNull() ?: 1
        val originalName = caption.substringAfter("NAME:").substringBefore("]")
        return SplitMetadata(id, partIndex, totalParts, originalName)
    }

    private fun parseVirtualFolderTag(caption: String): String? {
        val startTag = "[VF:"
        val endTag = "]"
        val startIndex = caption.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = caption.indexOf(endTag, startIndex)
        if (endIndex == -1) return null
        return caption.substring(startIndex + startTag.length, endIndex)
    }

    private fun cleanEncryptedFileName(fileName: String): String {
        return fileName.replace(Regex("^enc_\\d+_"), "").replace(Regex("^enc_"), "")
    }

    private data class SplitMetadata(val groupId: String, val partIndex: Int, val totalParts: Int, val originalName: String)
}
