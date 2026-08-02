package com.awd.teledrive.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.DriveItemEntity
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.CloudBackup
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.domain.model.FileMetadata
import com.awd.teledrive.domain.model.SplitFileMaster
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
import java.io.File
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
    private val VFOLDER_META_PREFIX = "#TELEDRIVE_VFOLDER_DATA"
    private val FILE_META_PREFIX = "#TELEDRIVE_FILE_DATA"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentManifest = TeleDriveManifest()
    private var manifestMessageId: Long = 0

    fun fetchCloudManifest(onComplete: () -> Unit = {}) {
        if (savedMessagesChatId == 0L) {
            onComplete()
            return
        }
        
        val allMessages = mutableListOf<TdApi.Message>()
        var searchDone = false
        var historyDone = false

        fun tryFinish() {
            if (searchDone && historyDone) {
                processManifestMessages(allMessages.distinctBy { it.id })
                onComplete()
            }
        }

        // 1. Search for manifests (indexed search)
        val searchRequest = TdApi.SearchChatMessages()
        searchRequest.chatId = savedMessagesChatId
        searchRequest.query = MANIFEST_PREFIX
        searchRequest.limit = 100
        
        telegramClient.send(searchRequest) { result ->
            if (result is TdApi.Messages) {
                synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
            }
            searchDone = true
            tryFinish()
        }

        // 2. Direct history scan (mirrors Desktop version but deeper)
        telegramClient.send(TdApi.GetChatHistory(savedMessagesChatId, 0, 0, 500, false)) { result ->
            if (result is TdApi.Messages) {
                synchronized(allMessages) { allMessages.addAll(result.messages.toList()) }
            }
            historyDone = true
            tryFinish()
        }
    }

    private fun processManifestMessages(messages: List<TdApi.Message>) {
        val possibleManifests = messages.filter { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                // Relaxed check: can contain keywords or start with prefixes
                text.contains(MANIFEST_PREFIX) || 
                text.contains(MANIFEST_BACKUP_PREFIX) || 
                text.contains("\"virtualFolders\"")
            } else false
        }
        
        if (possibleManifests.isEmpty()) return
        
        // Pick the newest prefix-based manifest as primary for ID tracking
        manifestMessageId = possibleManifests
            .filter { (it.content as? TdApi.MessageText)?.text?.text?.contains(MANIFEST_PREFIX) == true && !(it.content as TdApi.MessageText).text.text.contains(MANIFEST_BACKUP_PREFIX) }
            .maxByOrNull { it.date }?.id ?: 0L
        
        var mergedManifest = TeleDriveManifest()
        // Sort by date ASC to ensure newer data overwrites older data correctly
        possibleManifests.sortedBy { it.date }.forEach { msg ->
            val content = msg.content
            if (content is TdApi.MessageText) {
                val text = content.text.text
                val jsonStartIndex = text.indexOf('{')
                if (jsonStartIndex != -1) {
                    try {
                        val m = json.decodeFromString<TeleDriveManifest>(text.substring(jsonStartIndex).trim())
                        mergedManifest = mergeManifests(mergedManifest, m)
                    } catch (e: Exception) { 
                        Log.e("DriveRepo", "Error decoding manifest: ${e.message}")
                    }
                }
            }
        }
        
        currentManifest = mergedManifest
        syncVirtualFoldersToDb()
        currentManifest.secureFolderChatIds.forEach { chatId -> loadAllDriveItems(chatId) }
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.metadata_sync_toast, currentManifest.virtualFolders.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun mergeManifests(base: TeleDriveManifest, other: TeleDriveManifest): TeleDriveManifest {
        val mergedFolders = base.virtualFolders.toMutableMap()
        mergedFolders.putAll(other.virtualFolders)
        
        val mergedMappings = base.fileMappings.toMutableMap()
        mergedMappings.putAll(other.fileMappings)
        
        val mergedSecureIds = base.secureFolderChatIds.toMutableSet()
        mergedSecureIds.addAll(other.secureFolderChatIds)
        
        val mergedSplitMasters = base.splitFileMasters.toMutableMap()
        mergedSplitMasters.putAll(other.splitFileMasters)

        val mergedFolderMetaIds = base.folderMetadataIds.toMutableMap()
        mergedFolderMetaIds.putAll(other.folderMetadataIds)

        val mergedFileMetaIds = base.fileMetadataIds.toMutableMap()
        mergedFileMetaIds.putAll(other.fileMetadataIds)
        
        return base.copy(
            updatedAt = maxOf(base.updatedAt, other.updatedAt),
            virtualFolders = mergedFolders,
            fileMappings = mergedMappings,
            secureFolderChatIds = mergedSecureIds,
            splitFileMasters = mergedSplitMasters,
            folderMetadataIds = mergedFolderMetaIds,
            fileMetadataIds = mergedFileMetaIds
        )
    }

    fun exportManifestToJson(): String = json.encodeToString(currentManifest)

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            val imported = json.decodeFromString<TeleDriveManifest>(jsonStr)
            currentManifest = imported
            saveCloudManifest()
            syncVirtualFoldersToDb()
            // Reset file parents and re-sync to apply imported mappings
            scope.launch {
                driveDao.resetAllFileVirtualParents()
                fetchFiles()
            }
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
                            scope.launch {
                                driveDao.resetAllFileVirtualParents()
                                fetchFiles()
                            }
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
                        // If the virtual folder is directly inside a physical chat, its virtualParentId should be "0"
                        // so it appears in the root of that chat view.
                        break
                    }
                    depth++
                }
                
                // Final fix: If the parent is a physical chat ID, set virtualParentId to "0"
                val finalVirtualParentId = if (vf.parentId.startsWith("vf_")) vf.parentId else "0"
                
                // Deterministic Long ID based on virtualId string
                val deterministicId = vf.id.replace("vf_", "").filter { it.isDigit() }.toLongOrNull() 
                    ?: (vf.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                DriveItemEntity(existing?.id ?: deterministicId, vf.name, 0, "virtual_folder", 0, targetChat, true, isVirtual = true, virtualId = vf.id, virtualParentId = finalVirtualParentId, createdAt = vf.createdAt * 1000, isStarred = existing?.isStarred ?: false, isSecure = vf.isSecure)
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
        
        // Send distributed metadata message and track its ID
        if (savedMessagesChatId != 0L) {
            val metaJson = json.encodeToString(folder)
            val text = "$VFOLDER_META_PREFIX\n\n$metaJson"
            telegramClient.send(TdApi.SendMessage(savedMessagesChatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
                if (result is TdApi.Message) {
                    val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
                    updatedMetaIds[id] = result.id
                    currentManifest = currentManifest.copy(folderMetadataIds = updatedMetaIds)
                    // No need to save manifest again immediately, it will be saved on next change
                }
            }
        }
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
            Toast.makeText(context, context.getString(R.string.folder_sync_toast), Toast.LENGTH_SHORT).show()
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

                // Ambil batch berikutnya (limit 15000 item per chat untuk pemulihan mendalam)
                if (allFetchedIds.size < 15000) {
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
        val content = message.content
        
        // Handle Distributed Metadata Text Messages
        if (content is TdApi.MessageText && chatId == savedMessagesChatId) {
            val text = content.text.text
            if (text.startsWith(VFOLDER_META_PREFIX)) {
                try {
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val vf = json.decodeFromString<VirtualFolder>(text.substring(jsonStartIndex))
                        val updatedFolders = currentManifest.virtualFolders.toMutableMap()
                        val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
                        
                        updatedFolders[vf.id] = vf
                        updatedMetaIds[vf.id] = message.id
                        
                        currentManifest = currentManifest.copy(
                            virtualFolders = updatedFolders,
                            folderMetadataIds = updatedMetaIds
                        )
                        syncVirtualFoldersToDb()
                    }
                } catch (e: Exception) { }
            } else if (text.startsWith(FILE_META_PREFIX)) {
                try {
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val jsonStr = text.substring(jsonStartIndex)
                        if (jsonStr.contains("partMessageIds")) {
                            // It's a SplitFileMaster
                            val master = json.decodeFromString<SplitFileMaster>(jsonStr)
                            val updatedMasters = currentManifest.splitFileMasters.toMutableMap()
                            updatedMasters[master.groupId] = master.copy(metadataMessageId = message.id)
                            currentManifest = currentManifest.copy(splitFileMasters = updatedMasters)
                        } else {
                            // It's a regular FileMetadata
                            val meta = json.decodeFromString<FileMetadata>(jsonStr)
                            val updatedMappings = currentManifest.fileMappings.toMutableMap()
                            val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
                            
                            updatedMappings[meta.messageId.toString()] = meta.virtualFolderId ?: "0"
                            updatedFileMetaIds[meta.messageId.toString()] = message.id
                            
                            currentManifest = currentManifest.copy(
                                fileMappings = updatedMappings,
                                fileMetadataIds = updatedFileMetaIds
                            )
                            scope.launch {
                                driveDao.updateVirtualParent(meta.messageId, meta.virtualFolderId ?: "0")
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        return when (content) {
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
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
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
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
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
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
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
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
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
                    virtualParentId = getAndEnsureVirtualFolder(message.id, caption),
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

    private fun getAndEnsureVirtualFolder(messageId: Long, caption: String): String {
        val mappedId = currentManifest.fileMappings[messageId.toString()]
        val tagId = parseVirtualFolderTag(caption)
        val finalId = mappedId ?: tagId ?: "0"
        
        if (finalId != "0" && !currentManifest.virtualFolders.containsKey(finalId)) {
            // Recovered Folder Logic: Automatically create a placeholder if it's missing in manifest
            val recoveredName = "Recovered_${finalId.takeLast(6)}"
            val newFolder = VirtualFolder(
                id = finalId,
                name = recoveredName,
                parentId = "0", // Move to root for visibility
                createdAt = System.currentTimeMillis() / 1000
            )
            val updatedFolders = currentManifest.virtualFolders.toMutableMap()
            updatedFolders[finalId] = newFolder
            
            // Also update file mapping if it came from a tag
            val updatedMappings = currentManifest.fileMappings.toMutableMap()
            if (tagId != null) {
                updatedMappings[messageId.toString()] = tagId
            }
            
            currentManifest = currentManifest.copy(
                virtualFolders = updatedFolders,
                fileMappings = updatedMappings
            )
            syncVirtualFoldersToDb()
            
            // Save to cloud so other devices (Desktop) can see this recovered folder
            saveCloudManifest()
            
            Log.d("DriveRepo", "Recovered orphaned folder: $finalId ($recoveredName)")
        }
        
        return finalId
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
                    val isSecure = isKnownSecure || (isPrivateGroup && isCreator && currentManifest.secureFolderChatIds.contains(chat.id))
                    
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
        val sourceFile = File(filePath)
        if (sourceFile.length() > FileUtils.MAX_FILE_SIZE) {
            if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) return
            startTransferService()
            scope.launch {
                val placeholderId = "prep_${System.currentTimeMillis()}"
                transferRepository.addTransfer(0, placeholderId, originalFileName, false, sourceFile.length(), status = context.getString(R.string.splitting_file, ""))
                try {
                    val parts = FileUtils.splitFile(context, sourceFile) { progress ->
                        transferRepository.updateTransferManual(placeholderId, progress, context.getString(R.string.splitting_file_perc, (progress * 100).toInt()))
                    }
                    transferRepository.removeTransfer(placeholderId)
                    val groupId = FileUtils.generateSplitGroupId()
                    val partMessageIds = mutableListOf<Long>()
                    
                    parts.forEachIndexed { index, part ->
                        val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$originalFileName]$vfTag"
                        uploadSinglePart(part.absolutePath, originalFileName, targetChatId, caption, virtualFolderId, deleteSourceAfter = true) { messageId ->
                            if (messageId != 0L) partMessageIds.add(messageId)
                            
                            // If this was the last part, send the Master Metadata
                            if (index == parts.size - 1) {
                                sendSplitMasterMetadata(groupId, originalFileName, sourceFile.length(), FileUtils.getMimeType(originalFileName), parts.size, virtualFolderId, isChatSecure(targetChatId), partMessageIds)
                            }
                        }
                    }
                } catch (e: Exception) {
                    transferRepository.updateTransferManual(placeholderId, 0f, context.getString(R.string.split_fail))
                }
            }
        } else {
            uploadSinglePart(filePath, originalFileName, targetChatId, if (vfTag.isNotEmpty()) "$originalFileName $vfTag" else null, virtualFolderId)
        }
    }

    private fun uploadSinglePart(filePath: String, originalFileName: String, targetChatId: Long, caption: String? = null, virtualFolderId: String? = null, deleteSourceAfter: Boolean = false, onMessageSent: (Long) -> Unit = {}) {
        startTransferService()
        val isSecure = isChatSecure(targetChatId) || (virtualFolderId != null && isVirtualFolderSecure(virtualFolderId))
        val password = secureSessionManager.decryptedPassword.value
        if (isSecure && password == null) {
            scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.unlock_to_upload), Toast.LENGTH_LONG).show() }
            return
        }

        // Create a unique key for the queue
        val tempId = "up_${System.nanoTime()}"
        
        transferRepository.enqueueTransfer(
            fileId = 0,
            remoteUniqueId = tempId,
            fileName = originalFileName,
            isDownload = false,
            totalSize = File(filePath).length(),
            localPath = if (isSecure) null else filePath // Track by path if not encrypting
        ) {
            var finalPath = filePath
            var finalCaption = caption ?: originalFileName
            var tempEncFile: java.io.File? = null
            
            // Set initial status to waiting/preparing
            transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.status_waiting))
            
            if (isSecure && password != null) {
                try {
                    val originalFile = java.io.File(filePath)
                    tempEncFile = java.io.File(context.cacheDir, "enc_${System.nanoTime()}_${originalFile.name}")
                    
                    // Update status in UI with throttling enabled
                    encryptionManager.encryptFile(originalFile, tempEncFile, password) { progress ->
                        transferRepository.updateTransferManual(
                            tempId, 
                            progress, 
                            context.getString(R.string.encrypting_perc, (progress * 100).toInt()),
                            throttled = true
                        )
                    }
                    finalPath = tempEncFile.absolutePath
                    finalCaption = if (caption != null) "$caption [ENC]" else "$originalFileName [ENC]"
                    
                    // Update the transfer record with the new path for tracking
                    transferRepository.updateRemoteUniqueId(tempId, tempId, 0, finalPath)
                } catch (e: Exception) {
                    transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.failed_with_error, "Encryption failed"))
                    return@enqueueTransfer 
                }
            }

            // Step 2: Send message to Telegram (this starts the upload)
            telegramClient.send(TdApi.SendMessage(targetChatId, null, null, null, null, TdApi.InputMessageDocument(TdApi.InputFileLocal(finalPath), null, false, TdApi.FormattedText(finalCaption, emptyArray())))) { result ->
                if (result is TdApi.Message) {
                    onMessageSent(result.id)
                    val content = result.content
                    val file = when (content) {
                        is TdApi.MessageDocument -> content.document.document
                        is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo
                        is TdApi.MessageVideo -> content.video.video
                        is TdApi.MessageAudio -> content.audio.audio
                        else -> null
                    }
                    
                    if (file != null) {
                        // Migrate the temporary queue ID to the real TDLib ID, including the local path for better matching
                        transferRepository.updateRemoteUniqueId(tempId, file.remote.uniqueId, file.id, finalPath)
                        
                        // Force complete if Telegram says it's already done
                        if (file.remote.isUploadingCompleted) {
                            transferRepository.updateTransferManual(file.remote.uniqueId, 1.0f, TransferRepository.Status.COMPLETED)
                        }

                        // Send distributed metadata for regular files
                        if (savedMessagesChatId != 0L && !finalCaption.contains("[TD_SPLIT|")) {
                            sendFileMetadata(result.id, originalFileName, virtualFolderId, isSecure)
                        }
                    } else {
                        // Complete it if we can't find a file to track
                        transferRepository.updateTransferManual(tempId, 1.0f, TransferRepository.Status.COMPLETED)
                    }
                } else {
                    transferRepository.updateTransferManual(tempId, 0f, context.getString(R.string.failed_with_error, "SendMessage failed"))
                }
            }
            
            // Wait for completion (terminal state: Completed, Cancelled, or Failed)
            transferRepository.waitForCompletion(tempId)
            
            // CRITICAL: Cleanup temp encrypted file AFTER completion
            try {
                tempEncFile?.let { 
                    if (it.exists()) {
                        it.delete()
                        Log.d("DriveRepo", "Cleaned up temp encrypted file: ${it.absolutePath}")
                    }
                }
                // Cleanup split part if requested
                if (deleteSourceAfter) {
                    val sourceFile = java.io.File(filePath)
                    if (sourceFile.exists()) {
                        sourceFile.delete()
                        Log.d("DriveRepo", "Cleaned up source part: $filePath")
                    }
                }
            } catch (e: Exception) {
                Log.e("DriveRepo", "Failed to cleanup temp files", e)
            }
            
            // After completion, fetch files once to update the list
            fetchFiles(targetChatId)
        }
    }

    private fun sendFileMetadata(messageId: Long, fileName: String, virtualFolderId: String?, isEncrypted: Boolean) {
        val meta = FileMetadata(messageId, fileName, virtualFolderId, isEncrypted)
        val text = "$FILE_META_PREFIX\n\n${json.encodeToString(meta)}"
        telegramClient.send(TdApi.SendMessage(savedMessagesChatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
            if (result is TdApi.Message) {
                val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
                updatedFileMetaIds[messageId.toString()] = result.id
                currentManifest = currentManifest.copy(fileMetadataIds = updatedFileMetaIds)
            }
        }
    }

    private fun sendSplitMasterMetadata(groupId: String, name: String, size: Long, mime: String, parts: Int, vfId: String?, enc: Boolean, ids: List<Long>) {
        val master = SplitFileMaster(groupId, name, size, mime, parts, vfId, enc, ids)
        val text = "$FILE_META_PREFIX\n\n${json.encodeToString(master)}"
        telegramClient.send(TdApi.SendMessage(savedMessagesChatId, null, null, null, null, TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true))) { result ->
            val finalMaster = if (result is TdApi.Message) master.copy(metadataMessageId = result.id) else master
            val updatedMasters = currentManifest.splitFileMasters.toMutableMap()
            updatedMasters[groupId] = finalMaster
            currentManifest = currentManifest.copy(splitFileMasters = updatedMasters)
            saveCloudManifest()
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
                
                val remoteUniqueId = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.remote.uniqueId
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.remote?.uniqueId ?: ""
                    is TdApi.MessageVideo -> content.video.video.remote.uniqueId
                    is TdApi.MessageAudio -> content.audio.audio.remote.uniqueId
                    else -> ""
                }

                val expectedSize = when (val content = result.content) {
                    is TdApi.MessageDocument -> content.document.document.expectedSize
                    is TdApi.MessagePhoto -> content.photo.sizes.lastOrNull()?.photo?.expectedSize ?: 0L
                    is TdApi.MessageVideo -> content.video.video.expectedSize
                    is TdApi.MessageAudio -> content.audio.audio.expectedSize
                    else -> 0L
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
                        scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.unlock_to_download), Toast.LENGTH_LONG).show() }
                        return@send
                    }
                    
                    transferRepository.enqueueTransfer(
                        fileId,
                        remoteUniqueId,
                        fileName,
                        true,
                        expectedSize,
                        localPath = null // Downloads don't have a reliable local path until they finish
                    ) {
                        telegramClient.send(TdApi.DownloadFile(fileId, 1, 0, 0, false))
                    }
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
                    if (password != null) {
                        encryptionManager.decryptFile(source, dest, password) { progress ->
                            // Optional: Could track this via transferRepository if it's a large file
                            // For now, let's just do it
                        }
                    }
                } else {
                    source.copyTo(dest, overwrite = true)
                }
                scope.launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.save_success), Toast.LENGTH_SHORT).show() }
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
        
        // 1. Delete actual files from Telegram
        if (fileIds.isNotEmpty()) {
            telegramClient.send(TdApi.DeleteMessages(chatId, fileIds.toLongArray(), true)) {}
        }
        
        // 2. Collect metadata messages to delete
        val metadataIdsToDelete = mutableListOf<Long>()
        currentManifest.folderMetadataIds[virtualId]?.let { metadataIdsToDelete.add(it) }
        
        fileIds.forEach { fid ->
            currentManifest.fileMetadataIds[fid.toString()]?.let { metadataIdsToDelete.add(it) }
            children.find { it.id == fid }?.splitGroupId?.let { gid ->
                currentManifest.splitFileMasters[gid]?.metadataMessageId?.let { metadataIdsToDelete.add(it) }
            }
        }
        
        if (metadataIdsToDelete.isNotEmpty()) {
            telegramClient.send(TdApi.DeleteMessages(savedMessagesChatId, metadataIdsToDelete.toLongArray(), true)) {}
        }

        // 3. Recurse into subfolders
        subFolders.forEach { sf -> sf.virtualId?.let { deleteVirtualFolderRecursive(chatId, it, manifestMap) } }
        
        // 4. Update manifest in-memory (atomically update local maps first)
        manifestMap.remove(virtualId)
        
        synchronized(currentManifest) {
            val updatedMetaIds = currentManifest.folderMetadataIds.toMutableMap()
            updatedMetaIds.remove(virtualId)
            
            val updatedFileMetaIds = currentManifest.fileMetadataIds.toMutableMap()
            val updatedFileMappings = currentManifest.fileMappings.toMutableMap()
            val updatedSplitMasters = currentManifest.splitFileMasters.toMutableMap()
            
            fileIds.forEach { fid ->
                updatedFileMetaIds.remove(fid.toString())
                updatedFileMappings.remove(fid.toString())
                children.find { it.id == fid }?.splitGroupId?.let { updatedSplitMasters.remove(it) }
            }

            currentManifest = currentManifest.copy(
                folderMetadataIds = updatedMetaIds,
                fileMetadataIds = updatedFileMetaIds,
                fileMappings = updatedFileMappings,
                splitFileMasters = updatedSplitMasters
            )
        }

        // 5. DB Cleanup
        val entity = driveDao.getVirtualFolderById(virtualId)
        if (entity != null) driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
        fileIds.forEach { driveDao.deleteItemCompletely(it, chatId) }
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

    fun getInternalCacheSize(): Flow<Long> = kotlinx.coroutines.flow.flow {
        // Initial emit
        emit(calculateTotalStorage())
        while (true) {
            kotlinx.coroutines.delay(10000)
            emit(calculateTotalStorage())
        }
    }

    private fun calculateTotalStorage(): Long {
        return calculateDirectorySize(context.cacheDir) + 
               calculateDirectorySize(context.filesDir) +
               (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L) +
               (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                   calculateDirectorySize(context.noBackupFilesDir) + calculateDirectorySize(context.codeCacheDir)
               } else 0L)
    }

    private fun calculateDirectorySize(directory: File?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size: Long = 0
        try {
            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
            }
        } catch (e: Exception) { }
        return size
    }

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
    
    fun getTotalStorageUsed(): Flow<Long> = driveDao.getAllFiles().map { files ->
        var totalSize = 0L
        val processedGroups = mutableSetOf<String>()
        
        files.forEach { file ->
            if (file.splitGroupId == null) {
                totalSize += file.size
            } else if (!processedGroups.contains(file.splitGroupId)) {
                // Use logical total size from manifest for split files
                val master = currentManifest.splitFileMasters[file.splitGroupId]
                totalSize += master?.totalSize ?: (file.size * (file.totalParts)) // Heuristic fallback
                processedGroups.add(file.splitGroupId)
            }
        }
        totalSize
    }

    fun isChatSecure(chatId: Long): Boolean = currentManifest.secureFolderChatIds.contains(chatId)
    fun isVirtualFolderSecure(virtualId: String): Boolean = currentManifest.virtualFolders[virtualId]?.isSecure == true
    fun getParentVirtualId(virtualId: String): String = currentManifest.virtualFolders[virtualId]?.parentId ?: "0"
    fun getSavedMessagesChatId(): Long = savedMessagesChatId

    private fun startTransferService() {
        val intent = Intent(context, com.awd.teledrive.data.service.TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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
