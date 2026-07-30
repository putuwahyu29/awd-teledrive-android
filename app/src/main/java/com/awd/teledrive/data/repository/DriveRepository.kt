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
import com.awd.teledrive.data.service.TransferService
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.qualifiers.ApplicationContext
import com.awd.teledrive.domain.model.CloudBackup
import com.awd.teledrive.domain.model.TeleDriveManifest
import com.awd.teledrive.domain.model.VirtualFolder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    private val driveDao: DriveDao,
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedMessagesChatId: Long = 0
    private val _savedMessagesChatIdFlow = MutableStateFlow(0L)
    fun getSavedMessagesChatIdFlow(): Flow<Long> = _savedMessagesChatIdFlow.asStateFlow()

    private val MANIFEST_PREFIX = "#TELEDRIVE_MANIFEST"
    private val MANIFEST_BACKUP_PREFIX = "#TELEDRIVE_MANIFEST_BACKUP_"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentManifest = TeleDriveManifest()
    private var manifestMessageId: Long = 0

    fun fetchCloudManifest() {
        if (savedMessagesChatId == 0L) return
        Log.d("DriveRepo", "Searching for cloud manifest in Saved Messages...")
        
        val searchRequest = TdApi.SearchChatMessages()
        searchRequest.chatId = savedMessagesChatId
        searchRequest.query = MANIFEST_PREFIX
        searchRequest.limit = 10
        
        telegramClient.send(searchRequest) { result ->
            if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                processManifestMessages(result.messages.toList())
            } else {
                Log.d("DriveRepo", "No manifest found via search, falling back to history...")
                // Fallback to checking history if search fails
                telegramClient.send(TdApi.GetChatHistory(savedMessagesChatId, 0, 0, 100, false)) { historyResult ->
                    if (historyResult is TdApi.Messages) {
                        val manifestMessages = historyResult.messages.filter { msg ->
                            val content = msg.content
                            content is TdApi.MessageText && content.text.text.contains("#TELEDRIVE_MANIFEST", ignoreCase = true)
                        }
                        if (manifestMessages.isNotEmpty()) {
                            processManifestMessages(manifestMessages.toList())
                        } else {
                            Log.d("DriveRepo", "Still no manifest found in last 100 messages")
                        }
                    }
                }
            }
        }
    }

    private fun processManifestMessages(messages: List<TdApi.Message>) {
        // Filter messages to find the one that is the ACTUAL manifest, not a backup
        val manifestMsg = messages.find { msg ->
            val content = msg.content
            content is TdApi.MessageText && content.text.text.startsWith(MANIFEST_PREFIX) && 
                !content.text.text.startsWith(MANIFEST_BACKUP_PREFIX)
        } ?: messages.firstOrNull() // Fallback to first if none match perfectly
        
        if (manifestMsg == null) return
        
        manifestMessageId = manifestMsg.id
        val content = manifestMsg.content
        if (content is TdApi.MessageText) {
            val text = content.text.text
            // Find the first '{' and parse from there to be safe against prefix variations
            val jsonStartIndex = text.indexOf('{')
            if (jsonStartIndex == -1) {
                Log.e("DriveRepo", "No JSON found in manifest message")
                return
            }
            val jsonStr = text.substring(jsonStartIndex).trim()
            Log.d("DriveRepo", "Found manifest message ID: $manifestMessageId")
            Log.d("DriveRepo", "Manifest JSON: $jsonStr")
            try {
                currentManifest = json.decodeFromString<TeleDriveManifest>(jsonStr)
                Log.d("DriveRepo", "Manifest parsed successfully. Folders: ${currentManifest.virtualFolders.size}")
                syncVirtualFoldersToDb()
                
                // Show feedback to user for debugging
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Metadata sinkron: ${currentManifest.virtualFolders.size} folder ditemukan", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DriveRepo", "Failed to parse manifest JSON", e)
            }
        }
    }

    fun exportManifestToJson(): String {
        return json.encodeToString(currentManifest)
    }

    fun importManifestFromJson(jsonStr: String): Boolean {
        return try {
            val imported = json.decodeFromString<TeleDriveManifest>(jsonStr)
            currentManifest = imported
            saveCloudManifest()
            syncVirtualFoldersToDb()
            true
        } catch (e: Exception) {
            Log.e("DriveRepo", "Failed to import manifest", e)
            false
        }
    }

    private fun syncVirtualFoldersToDb() {
        scope.launch {
            val vFolders = currentManifest.virtualFolders.values.toList()
            val entities = vFolders.map { vf ->
                val existing = driveDao.getVirtualFolderById(vf.id)
                
                // Traverse up to find the physical chat ID this hierarchy belongs to
                var targetChat: Long = savedMessagesChatId
                var currentPid = vf.parentId
                
                // Simple recursive check (max depth to prevent infinite loops if manifest is corrupted)
                var depth = 0
                while (currentPid != "0" && currentPid.isNotEmpty() && depth < 10) {
                    if (currentPid.startsWith("vf_")) {
                        // Move up to parent
                        currentPid = currentManifest.virtualFolders[currentPid]?.parentId ?: "0"
                    } else {
                        // Found a physical chat ID
                        targetChat = currentPid.toLongOrNull() ?: savedMessagesChatId
                        break
                    }
                    depth++
                }

                DriveItemEntity(
                    id = existing?.id ?: vf.id.replace("vf_", "").filter { it.isDigit() }.take(12).toLongOrNull() ?: System.currentTimeMillis(),
                    name = vf.name,
                    size = 0,
                    mimeType = "virtual_folder",
                    telegramFileId = 0,
                    parentChatId = targetChat, 
                    isFolder = true,
                    isVirtual = true,
                    virtualId = vf.id,
                    virtualParentId = if (vf.parentId.isEmpty()) "0" else vf.parentId,
                    createdAt = vf.createdAt * 1000,
                    isStarred = existing?.isStarred ?: false,
                    isSecure = vf.isSecure
                )
            }
            Log.d("DriveRepo", "Syncing ${entities.size} virtual folders to DB")
            driveDao.insertItems(entities)
        }
    }

    fun createVirtualFolder(name: String, parentId: String = "0", isSecure: Boolean = false) {
        val id = "vf_${System.nanoTime()}"
        val newFolder = VirtualFolder(id = id, name = name, parentId = parentId, isSecure = isSecure)
        val updatedFolders = currentManifest.virtualFolders.toMutableMap()
        updatedFolders[id] = newFolder
        currentManifest = currentManifest.copy(
            virtualFolders = updatedFolders,
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveCloudManifest()
        syncVirtualFoldersToDb()
    }

    fun createSecureFolder(name: String) {
        telegramClient.send(TdApi.CreateNewSupergroupChat(name, false, false, "TeleDrive Secure Folder", null, 0, false)) { result ->
            if (result is TdApi.Chat) {
                val updatedSecureIds = currentManifest.secureFolderChatIds.toMutableSet()
                updatedSecureIds.add(result.id)
                currentManifest = currentManifest.copy(
                    secureFolderChatIds = updatedSecureIds,
                    updatedAt = System.currentTimeMillis() / 1000
                )
                saveCloudManifest()
                fetchFiles()
            }
        }
    }

    fun isChatSecure(chatId: Long): Boolean {
        return currentManifest.secureFolderChatIds.contains(chatId)
    }

    fun isVirtualFolderSecure(vId: String): Boolean {
        return currentManifest.virtualFolders[vId]?.isSecure == true
    }

    fun getParentVirtualId(vId: String): String {
        return currentManifest.virtualFolders[vId]?.parentId ?: "0"
    }

    fun fetchCloudBackups(callback: (List<CloudBackup>) -> Unit) {
        if (savedMessagesChatId == 0L) { callback(emptyList()); return }
        Log.d("DriveRepo", "Fetching cloud backups from Saved Messages history...")
        
        // Search might be slow or unreliable for special prefixes, so let's use GetChatHistory
        // and scan for backup tags manually.
        telegramClient.send(TdApi.GetChatHistory(savedMessagesChatId, 0, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                val backups = result.messages.mapNotNull { msg ->
                    val content = msg.content
                    if (content is TdApi.MessageText && content.text.text.contains(MANIFEST_BACKUP_PREFIX)) {
                        try {
                            val text = content.text.text
                            val jsonStartIndex = text.indexOf('{')
                            if (jsonStartIndex == -1) return@mapNotNull null
                            
                            val jsonStr = text.substring(jsonStartIndex).trim()
                            val manifest = json.decodeFromString<TeleDriveManifest>(jsonStr)
                            CloudBackup(
                                messageId = msg.id,
                                date = msg.date.toLong() * 1000,
                                folderCount = manifest.virtualFolders.size
                            )
                        } catch (e: Exception) { null }
                    } else null
                }
                Log.d("DriveRepo", "Found ${backups.size} cloud backups")
                callback(backups)
            } else {
                callback(emptyList())
            }
        }
    }

    fun restoreManifestFromMessage(messageId: Long, callback: (Boolean) -> Unit) {
        telegramClient.send(TdApi.GetMessage(savedMessagesChatId, messageId)) { result ->
            if (result is TdApi.Message) {
                val content = result.content
                if (content is TdApi.MessageText) {
                    val text = content.text.text
                    val jsonStartIndex = text.indexOf('{')
                    if (jsonStartIndex != -1) {
                        val jsonStr = text.substring(jsonStartIndex).trim()
                        if (importManifestFromJson(jsonStr)) {
                            callback(true)
                            return@send
                        }
                    }
                }
            }
            callback(false)
        }
    }

    fun createManualCloudBackup() {
        saveCloudManifest(forceNewBackup = true)
    }

    private fun saveCloudManifest(forceNewBackup: Boolean = false) {
        if (savedMessagesChatId == 0L) return
        val jsonManifest = json.encodeToString(currentManifest)
        val manifestStr = "$MANIFEST_PREFIX\n$jsonManifest"
        
        val content = TdApi.InputMessageText(TdApi.FormattedText(manifestStr, emptyArray()), null, false)
        
        if (manifestMessageId != 0L) {
            telegramClient.send(TdApi.EditMessageText(
                savedMessagesChatId,
                manifestMessageId,
                null,
                content
            )) { }
        } else {
            telegramClient.send(TdApi.SendMessage(
                savedMessagesChatId,
                null,
                null,
                null,
                null,
                content
            )) { result ->
                if (result is TdApi.Message) {
                    manifestMessageId = result.id
                }
            }
        }

        // Automatic daily backup
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val backupTag = "$MANIFEST_BACKUP_PREFIX$today"
        
        if (forceNewBackup) {
            // Create a completely new message for force backup
            val backupContent = TdApi.InputMessageText(TdApi.FormattedText("$backupTag (Manual)\n$jsonManifest", emptyArray()), null, false)
            telegramClient.send(TdApi.SendMessage(
                savedMessagesChatId,
                null,
                null,
                null,
                null,
                backupContent
            )) { }
        } else {
            // Search if we already backed up today
            val searchRequest = TdApi.SearchChatMessages()
            searchRequest.chatId = savedMessagesChatId
            searchRequest.query = backupTag
            searchRequest.limit = 1
            
            telegramClient.send(searchRequest) { result ->
                val backupContent = TdApi.InputMessageText(TdApi.FormattedText("$backupTag\n$jsonManifest", emptyArray()), null, false)
                
                if (result is TdApi.Messages && result.messages.isNotEmpty()) {
                    // Update today's backup
                    telegramClient.send(TdApi.EditMessageText(
                        savedMessagesChatId,
                        result.messages.first().id,
                        null,
                        backupContent
                    )) { }
                } else {
                    // Create new backup for today
                    telegramClient.send(TdApi.SendMessage(
                        savedMessagesChatId,
                        null,
                        null,
                        null,
                        null,
                        backupContent
                    )) { }
                }
            }
        }
    }

    private val exportOnComplete = mutableMapOf<String, String>()
    private val deleteAfterUpload = mutableMapOf<Int, String>()

    init {
        scope.launch {
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                    val entity = if (uniqueId.isNotEmpty()) driveDao.getItemByUniqueId(uniqueId) else null
                    val isEncrypted = entity?.isEncrypted ?: false
                    val password = secureSessionManager.decryptedPassword.value

                    // Cek apakah file ini diminta untuk diekspor ke folder Download publik
                    val fileName = exportOnComplete[uniqueId] ?: exportOnComplete["temp_$fileId"]
                    if (fileName != null) {
                        if (isEncrypted) {
                            if (password != null) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val encryptedFile = java.io.File(file.local.path)
                                        val decryptedFile = java.io.File(context.cacheDir, "dec_${System.nanoTime()}_$fileName")
                                        encryptionManager.decryptFile(encryptedFile, decryptedFile, password)
                                        transferRepository.saveToPublicDownloads(decryptedFile.absolutePath, fileName)
                                        decryptedFile.delete()
                                    } catch (e: Exception) {
                                        Log.e("DriveRepo", "Decryption failed during export", e)
                                    }
                                }
                            } else {
                                Log.e("DriveRepo", "Cannot export encrypted file: Session locked")
                            }
                        } else {
                            transferRepository.saveToPublicDownloads(file.local.path, fileName)
                        }
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
                    
                    if (uniqueId.isNotEmpty()) {
                        checkAndMergeSplitFile(uniqueId)
                        settingsRepository.triggerCacheCheck()
                    }
                    
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
    fun getItems(chatId: Long?, virtualParentId: String = "0", searchQuery: String = ""): Flow<List<DriveItem>> {
        val targetChatId = chatId ?: savedMessagesChatId
        Log.d("DriveRepo", "getItems called with chatId: $chatId, vParent: $virtualParentId, smId: $savedMessagesChatId")
        
        val flow = if (searchQuery.isNotEmpty()) {
            driveDao.searchGlobal(searchQuery)
        } else if (targetChatId != 0L) {
            driveDao.getItemsFlow(targetChatId, virtualParentId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }

        return flow.map { entities ->
            Log.d("DriveRepo", "Flow emitted ${entities.size} entities for chat $targetChatId, vParent $virtualParentId")
            entities.forEach { Log.d("DriveRepo", "Item: ${it.name}, isFolder: ${it.isFolder}, isVirtual: ${it.isVirtual}, vId: ${it.virtualId}, vParent: ${it.virtualParentId}") }
            entities.sortedByDescending { it.createdAt }.map { entity ->
                if (entity.isFolder) {
                    DriveItem.Folder(
                        id = entity.id,
                        parentChatId = entity.parentChatId,
                        name = entity.name,
                        telegramChatId = entity.id,
                        isStarred = entity.isStarred,
                        isVirtual = entity.isVirtual,
                        virtualId = entity.virtualId,
                        virtualParentId = entity.virtualParentId,
                        isSecure = entity.isSecure
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
                        splitGroupId = entity.splitGroupId,
                        totalParts = entity.totalParts,
                        virtualParentId = entity.virtualParentId,
                        isEncrypted = entity.isEncrypted
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
                    fetchCloudManifest()
                    loadAllDriveItems(savedMessagesChatId)
                } else if (result is TdApi.Error) {
                    Log.e("DriveRepo", "GetMe failed: ${result.message}")
                }
            }
        } else {
            // Also sync manifest when refreshing any folder to ensure logical structure is up to date
            fetchCloudManifest()
            loadAllDriveItems(targetChatId)
        }
    }

    private fun loadAllDriveItems(chatId: Long) {
        Log.d("DriveRepo", "Loading items for chatId: $chatId")
        
        // If we are at root, explicitly check for all known secure folders
        if (chatId == savedMessagesChatId) {
            currentManifest.secureFolderChatIds.forEach { sid ->
                telegramClient.send(TdApi.GetChat(sid)) { chatResult ->
                    if (chatResult is TdApi.Chat) {
                        processDiscoveredFolder(chatResult)
                    }
                }
            }
        }

        // Use a limit of 1000 to ensure we catch enough files
        telegramClient.send(TdApi.GetChatHistory(chatId, 0, 0, 1000, false)) { result ->
            if (result is TdApi.Messages) {
                Log.d("DriveRepo", "Found ${result.messages.size} messages in chat $chatId")
                val entities = result.messages.mapNotNull { message ->
                    if (message.sendingState != null) return@mapNotNull null
                    
                    var splitInfo: SplitMetadata? = null
                    var virtualParentId: String? = "0"
                    val isSecureFolder = currentManifest.secureFolderChatIds.contains(chatId)
                    
                    val entity = when (val content = message.content) {
                        is TdApi.MessageDocument -> {
                            val caption = content.caption.text
                            val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                            splitInfo = parseSplitMetadata(caption)
                            virtualParentId = parseVirtualFolderTag(caption) 
                                ?: currentManifest.fileMappings[message.id.toString()] 
                                ?: "0"
                            val thumb = content.document.thumbnail
                            if (thumb != null && thumb.file.local.path.isEmpty() && settingsRepository.isThumbnailAutoDownloadEnabled.value) {
                                telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                            }
                            val docFile = content.document.document
                            DriveItemEntity(
                                id = message.id,
                                name = splitInfo?.originalName ?: content.document.fileName,
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
                                createdAt = message.date.toLong() * 1000,
                                splitGroupId = splitInfo?.groupId,
                                partIndex = splitInfo?.partIndex ?: 0,
                                totalParts = splitInfo?.totalParts ?: 1,
                                virtualParentId = virtualParentId,
                                isSecure = isSecureFolder,
                                isEncrypted = isEncrypted
                            )
                        }
                        is TdApi.MessagePhoto -> {
                            val caption = content.caption.text
                            val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                            virtualParentId = parseVirtualFolderTag(caption) 
                                ?: currentManifest.fileMappings[message.id.toString()] 
                                ?: "0"
                            val photo = content.photo.sizes.lastOrNull()
                            val thumb = if (content.photo.sizes.size > 1) content.photo.sizes.firstOrNull() else null
                            
                            if (thumb != null && thumb.photo.local.path.isEmpty() && settingsRepository.isThumbnailAutoDownloadEnabled.value) {
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
                                createdAt = message.date.toLong() * 1000,
                                virtualParentId = virtualParentId,
                                isSecure = isSecureFolder,
                                isEncrypted = isEncrypted
                            )
                        }
                        is TdApi.MessageVideo -> {
                            val caption = content.caption.text
                            val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                            splitInfo = parseSplitMetadata(caption)
                            virtualParentId = parseVirtualFolderTag(caption) 
                                ?: currentManifest.fileMappings[message.id.toString()] 
                                ?: "0"
                            val thumb = content.video.thumbnail
                            if (thumb != null && thumb.file.local.path.isEmpty() && settingsRepository.isThumbnailAutoDownloadEnabled.value) {
                                telegramClient.send(TdApi.DownloadFile(thumb.file.id, 1, 0, 0, false))
                            }
                            val videoFile = content.video.video
                            DriveItemEntity(
                                id = message.id,
                                name = splitInfo?.originalName ?: content.video.fileName,
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
                                createdAt = message.date.toLong() * 1000,
                                splitGroupId = splitInfo?.groupId,
                                partIndex = splitInfo?.partIndex ?: 0,
                                totalParts = splitInfo?.totalParts ?: 1,
                                virtualParentId = virtualParentId,
                                isSecure = isSecureFolder,
                                isEncrypted = isEncrypted
                            )
                        }
                        is TdApi.MessageAudio -> {
                            val caption = content.caption.text
                            val isEncrypted = caption.contains("[ENC]") || isSecureFolder
                            splitInfo = parseSplitMetadata(caption)
                            virtualParentId = parseVirtualFolderTag(caption) 
                                ?: currentManifest.fileMappings[message.id.toString()] 
                                ?: "0"
                            val audioFile = content.audio.audio
                            DriveItemEntity(
                                id = message.id,
                                name = splitInfo?.originalName ?: content.audio.fileName.ifEmpty { "Audio_${message.id}.mp3" },
                                size = audioFile.expectedSize,
                                mimeType = content.audio.mimeType,
                                telegramFileId = audioFile.id,
                                parentChatId = chatId,
                                isFolder = false,
                                localPath = audioFile.local.path.takeIf { it.isNotEmpty() },
                                isStarred = false,
                                remoteUniqueId = audioFile.remote.uniqueId,
                                createdAt = message.date.toLong() * 1000,
                                splitGroupId = splitInfo?.groupId,
                                partIndex = splitInfo?.partIndex ?: 0,
                                totalParts = splitInfo?.totalParts ?: 1,
                                virtualParentId = virtualParentId,
                                isSecure = isSecureFolder,
                                isEncrypted = isEncrypted
                            )
                        }
                        else -> null
                    }
                    entity
                }
                Log.d("DriveRepo", "Mapped ${entities.size} valid entities for chat $chatId")
                scope.launch {
                    driveDao.deletePendingItems()
                    // Preserve folders if we are in the root (Saved Messages) because they are synced separately
                    driveDao.refreshChatItems(chatId, entities, preserveFolders = chatId == savedMessagesChatId)
                }
            } else {
                Log.e("DriveRepo", "GetChatHistory failed or returned non-Messages: ${result::class.java.simpleName}")
                if (result is TdApi.Error) {
                    Log.e("DriveRepo", "Error: ${result.code} - ${result.message}")
                }
            }
        }

        if (chatId == savedMessagesChatId && savedMessagesChatId != 0L) {
            val chatLists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
            chatLists.forEach { chatList ->
                telegramClient.send(TdApi.GetChats(chatList, 100)) { result ->
                    if (result is TdApi.Chats) {
                        result.chatIds.forEach { cid ->
                            telegramClient.send(TdApi.GetChat(cid)) { chatResult ->
                                if (chatResult is TdApi.Chat) {
                                    // Process both channels AND groups (Supergroups)
                                    if (chatResult.type is TdApi.ChatTypeSupergroup && cid != savedMessagesChatId) {
                                        processDiscoveredFolder(chatResult)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processDiscoveredFolder(chat: TdApi.Chat) {
        val type = chat.type
        if (type is TdApi.ChatTypeSupergroup) {
            // A folder is secure if it's in our manifest OR if it's a private group (not channel) owned by us
            val isKnownSecure = currentManifest.secureFolderChatIds.contains(chat.id)
            val isPrivateGroup = !type.isChannel
            
            telegramClient.send(TdApi.GetSupergroup(type.supergroupId)) { sgResult ->
                if (sgResult is TdApi.Supergroup) {
                    val status = sgResult.status
                    val isCreator = status is TdApi.ChatMemberStatusCreator
                    
                    // Logic: 
                    // 1. If it's a channel, it's a standard folder (if we have admin/creator rights)
                    // 2. If it's a private group AND we are the creator, it's a Secure Folder
                    val isSecure = isKnownSecure || (isPrivateGroup && isCreator)
                    
                    // We only list folders where we have sufficient rights
                    if (isCreator || status is TdApi.ChatMemberStatusAdministrator) {
                        scope.launch {
                            val existing = driveDao.getItemById(chat.id, savedMessagesChatId)
                            driveDao.insertItems(listOf(
                                DriveItemEntity(
                                    id = chat.id,
                                    name = chat.title,
                                    size = 0,
                                    mimeType = "folder",
                                    telegramFileId = 0,
                                    parentChatId = savedMessagesChatId,
                                    isFolder = true,
                                    isStarred = existing?.isStarred ?: false,
                                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                                    isSecure = isSecure
                                )
                            ))
                            
                            // If we discovered a new secure folder that wasn't in manifest, add it to manifest
                            if (isSecure && !isKnownSecure) {
                                val updatedSecureIds = currentManifest.secureFolderChatIds.toMutableSet()
                                updatedSecureIds.add(chat.id)
                                currentManifest = currentManifest.copy(
                                    secureFolderChatIds = updatedSecureIds,
                                    updatedAt = System.currentTimeMillis() / 1000
                                )
                                saveCloudManifest()
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

    fun uploadFile(filePath: String, originalFileName: String, chatId: Long? = null, virtualFolderId: String? = null) {
        val targetChatId = chatId ?: savedMessagesChatId
        if (targetChatId == 0L) return

        val sourceFile = java.io.File(filePath)
        val vfTag = if (virtualFolderId != null && virtualFolderId != "0") "[VF:$virtualFolderId]" else ""

        if (sourceFile.length() > FileUtils.MAX_FILE_SIZE) {
            if (!FileUtils.hasEnoughSpace(context, sourceFile.length())) {
                val required = FileUtils.formatSize(sourceFile.length())
                Toast.makeText(context, context.getString(R.string.err_low_storage, required), Toast.LENGTH_LONG).show()
                return
            }
            
            // Start service early to prevent Android from killing the process during splitting
            startTransferService()
            
            scope.launch {
                // Add a placeholder transfer to show "Preparing" in the UI
                val placeholderId = "prep_${System.currentTimeMillis()}"
                transferRepository.addTransfer(
                    fileId = 0,
                    remoteUniqueId = placeholderId,
                    fileName = originalFileName,
                    isDownload = false,
                    totalSize = sourceFile.length(),
                    status = "Memecah file... 0%"
                )

                try {
                    val parts = FileUtils.splitFile(context, sourceFile) { progress ->
                        val percent = (progress * 100).toInt()
                        transferRepository.updateTransferManual(placeholderId, progress, "Memecah file... $percent%")
                    }
                    
                    // Remove placeholder after splitting is done
                    transferRepository.removeTransfer(placeholderId)

                    val groupId = FileUtils.generateSplitGroupId()
                    parts.forEachIndexed { index, part ->
                        val caption = "[TD_SPLIT|ID:$groupId|PART:$index/${parts.size}|NAME:$originalFileName]$vfTag"
                        uploadSinglePart(part.absolutePath, originalFileName, targetChatId, caption)
                    }
                } catch (e: Exception) {
                    Log.e("DriveRepo", "Split failed for $originalFileName", e)
                    transferRepository.updateTransferManual(placeholderId, 0f, "Gagal memecah file")
                }
            }
        } else {
            val caption = if (vfTag.isNotEmpty()) "$originalFileName $vfTag" else null
            uploadSinglePart(filePath, originalFileName, targetChatId, caption)
        }
    }

    private fun uploadSinglePart(filePath: String, originalFileName: String, targetChatId: Long, caption: String? = null) {
        startTransferService()
        
        val isSecure = isChatSecure(targetChatId)
        val password = secureSessionManager.decryptedPassword.value
        
        if (isSecure && password == null) {
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Buka Mode Aman untuk mengunggah ke folder ini", Toast.LENGTH_LONG).show()
            }
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
                } catch (e: Exception) {
                    Log.e("DriveRepo", "Encryption failed", e)
                    return@launch
                }
            }

            val content = TdApi.InputMessageDocument(
                TdApi.InputFileLocal(finalPath),
                null,
                false,
                TdApi.FormattedText(finalCaption, emptyArray())
            )
            
            telegramClient.send(TdApi.SendMessage(targetChatId, null, null, null, null, content)) { result ->
                if (result is TdApi.Message) {
                    val msgContent = result.content
                    if (msgContent is TdApi.MessageDocument) {
                        val doc = msgContent.document.document
                        // Delete temp split parts or temp encrypted files
                        if (filePath.contains(context.cacheDir.absolutePath)) {
                            deleteAfterUpload[doc.id] = filePath
                        }
                        tempEncFile?.let { 
                            deleteAfterUpload[doc.id] = it.absolutePath 
                        }
                        
                        transferRepository.addTransfer(
                            doc.id,
                            doc.remote.uniqueId,
                            if (caption != null) "$originalFileName (Part)" else originalFileName,
                            isDownload = false,
                            totalSize = doc.expectedSize
                        )
                    }
                }
                fetchFiles(targetChatId)
            }
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
        scope.launch {
            val entity = driveDao.getItemById(messageId, chatId)
            if (entity?.splitGroupId != null) {
                downloadSplitFile(entity.splitGroupId, fileName)
            } else {
                downloadSingleFile(messageId, chatId, fileName)
            }
        }
    }

    private fun downloadSplitFile(groupId: String, fileName: String) {
        scope.launch {
            val parts = driveDao.getSplitFileParts(groupId)
            parts.forEach { part ->
                if (part.localPath == null) {
                    downloadSingleFile(part.id, part.parentChatId, part.name, shouldExport = false)
                }
            }
        }
    }

    private fun downloadSingleFile(messageId: Long, chatId: Long, fileName: String, shouldExport: Boolean = true) {
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
                    if (shouldExport) {
                        transferRepository.saveToPublicDownloads(file.local.path, fileName)
                    }
                    transferRepository.addTransfer(msgFileId, remoteUniqueId, fileName, isDownload = true, totalSize = expectedSize, isCompleted = true)
                    return@send
                }

                if (remoteUniqueId.isNotEmpty()) {
                    if (shouldExport) exportOnComplete[remoteUniqueId] = fileName
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
                    if (shouldExport) exportOnComplete[tempId] = fileName
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

    fun getCloudFileTypeStats(): Flow<List<com.awd.teledrive.data.local.DriveDao.FileTypeStat>> {
        return driveDao.getCloudFileTypeStats()
    }

    fun getInternalCacheSize(): Flow<Long> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                var totalSize = 0L
                // Include TDLib files (in filesDir/tdlib)
                totalSize += calculateDirectorySize(context.filesDir)
                // Include app cache (thumbnails, temp files)
                totalSize += calculateDirectorySize(context.cacheDir)
                // Include external cache if available
                context.externalCacheDir?.let {
                    totalSize += calculateDirectorySize(it)
                }
                
                emit(totalSize)
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
        clearDatabaseLocalPaths()
        scope.launch {
            // 1. Manually clear Android's cache directories
            try {
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                Log.d("DriveRepo", "Manual cache directories cleared")
            } catch (e: Exception) {
                Log.e("DriveRepo", "Failed to clear manual cache dirs", e)
            }

            // 2. Tell TDLib to optimize storage (delete its internal file copies)
            telegramClient.send(TdApi.OptimizeStorage(
                0, // size 0 means clear all possible
                0, // ttl
                0, // count
                0, // immunityDelay
                null,
                null,
                null,
                true,
                0
            )) {
                fetchFiles() // Refresh to update localPaths to null
            }
        }
    }

    fun clearDatabaseLocalPaths(onlyThumbnails: Boolean = false, onlyFiles: Boolean = false) {
        scope.launch {
            if (onlyThumbnails) {
                driveDao.clearAllThumbnailPaths()
            } else if (onlyFiles) {
                driveDao.clearAllLocalPaths()
            } else {
                driveDao.clearAllLocalPaths()
                driveDao.clearAllThumbnailPaths()
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
                    DriveItem.Folder(entity.id, entity.parentChatId, entity.name, entity.id, entity.isStarred, isSecure = entity.isSecure)
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
                        isEncrypted = entity.isEncrypted
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
                    entity.remoteUniqueId ?: "",
                    isEncrypted = entity.isEncrypted
                )
            }
        }
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
                    // Physical folder (Channel/Group)
                    telegramClient.send(TdApi.SetChatTitle(item.telegramChatId, newName)) {}
                }
            } else if (item is DriveItem.File) {
                // For files, we update the caption which acts as the name in TeleDrive
                // We must preserve existing tags like [ENC], [TD_SPLIT], [VF:...]
                telegramClient.send(TdApi.GetMessage(item.parentChatId, item.id)) { result ->
                    if (result is TdApi.Message) {
                        val currentCaption = when (val content = result.content) {
                            is TdApi.MessageDocument -> content.caption.text
                            is TdApi.MessagePhoto -> content.caption.text
                            is TdApi.MessageVideo -> content.caption.text
                            is TdApi.MessageAudio -> content.caption.text
                            else -> ""
                        }

                        // Regex to find and keep our tags
                        val tagRegex = Regex("\\[(ENC|TD_SPLIT|VF:|ID:|PART:|NAME:).*?\\]")
                        val tags = tagRegex.findAll(currentCaption).map { it.value }.toList()
                        
                        // Replace the filename in the split tag if it exists
                        val updatedTags = tags.map { tag ->
                            if (tag.startsWith("[TD_SPLIT|") && tag.contains("NAME:")) {
                                tag.replace(Regex("NAME:.*?(?=\\]|\\|)"), "NAME:$newName")
                            } else tag
                        }
                        
                        val newCaptionText = if (updatedTags.isNotEmpty()) {
                            // If it's a split file, the name is inside the tag, so we just use the tags
                            if (updatedTags.any { it.startsWith("[TD_SPLIT") }) {
                                updatedTags.joinToString("")
                            } else {
                                "$newName ${updatedTags.joinToString("")}"
                            }
                        } else {
                            newName
                        }

                        telegramClient.send(TdApi.EditMessageCaption(
                            item.parentChatId,
                            item.id,
                            null,
                            TdApi.FormattedText(newCaptionText, emptyArray()),
                            false
                        )) { editResult ->
                            if (editResult is TdApi.Message) {
                                scope.launch {
                                    driveDao.renameItem(item.id, item.parentChatId, newName)
                                }
                            }
                        }
                    }
                }
            }
            // Update local DB for immediate UI update
            driveDao.renameItem(item.id, item.parentChatId, newName)
        }
    }

    fun permanentlyDeleteItems(chatId: Long, items: List<DriveItem>) {
        scope.launch {
            val messageIds = items.asSequence()
                .filterIsInstance<DriveItem.File>()
                .map { it.id }
                .toList()
            val physicalFolderIds = items.asSequence()
                .filterIsInstance<DriveItem.Folder>()
                .filter { !it.isVirtual }
                .map { it.telegramChatId }
                .toList()
            val virtualFolderIds = items.asSequence()
                .filterIsInstance<DriveItem.Folder>()
                .filter { it.isVirtual }
                .mapNotNull { it.virtualId }
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
                virtualFolderIds.forEach { vid ->
                    deleteVirtualFolderRecursive(chatId, vid, updatedFolders)
                }
                currentManifest = currentManifest.copy(
                    virtualFolders = updatedFolders,
                    updatedAt = System.currentTimeMillis() / 1000
                )
                saveCloudManifest()
            }
        }
    }

    private suspend fun deleteVirtualFolderRecursive(chatId: Long, virtualId: String, manifestMap: MutableMap<String, VirtualFolder>) {
        // 1. Get all children from DB
        val children = driveDao.getItemsByVirtualParentSync(virtualId)
        
        // 2. Separate into files and subfolders
        val fileIds = children.filter { !it.isFolder }.map { it.id }
        val subFolders = children.filter { it.isFolder && it.isVirtual }

        // 3. Delete files from Telegram
        if (fileIds.isNotEmpty()) {
            telegramClient.send(TdApi.DeleteMessages(chatId, fileIds.toLongArray(), true)) {}
        }

        // 4. Recursively delete subfolders
        subFolders.forEach { sf ->
            sf.virtualId?.let { deleteVirtualFolderRecursive(chatId, it, manifestMap) }
        }

        // 5. Delete self from DB and Manifest
        manifestMap.remove(virtualId)
        val entity = driveDao.getVirtualFolderById(virtualId)
        if (entity != null) {
            driveDao.deleteItemCompletely(entity.id, entity.parentChatId)
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

    private fun checkAndMergeSplitFile(remoteUniqueId: String) {
        scope.launch {
            val entity = driveDao.getItemByUniqueId(remoteUniqueId) ?: return@launch
            val groupId = entity.splitGroupId ?: return@launch
            
            val allParts = driveDao.getSplitFileParts(groupId)
            if (allParts.size == entity.totalParts && allParts.all { it.localPath != null }) {
                val totalSize = allParts.sumOf { it.size }
                if (!FileUtils.hasEnoughSpace(context, totalSize)) {
                    val required = FileUtils.formatSize(totalSize)
                    Log.e("DriveRepo", "Insufficient space to merge $groupId. Needed: $required")
                    // Show a notification or toast? Since this is in background, log is safer, 
                    // but we should update the transfer state.
                    return@launch
                }
                
                val firstPart = allParts.first()
                val originalName = firstPart.name
                val cacheMergeDir = java.io.File(context.cacheDir, "merge_temp")
                if (!cacheMergeDir.exists()) cacheMergeDir.mkdirs()
                val targetFile = java.io.File(cacheMergeDir, originalName)
                
                val placeholderId = "merge_$groupId"
                transferRepository.addTransfer(
                    fileId = 0,
                    remoteUniqueId = placeholderId,
                    fileName = originalName,
                    isDownload = true,
                    totalSize = allParts.sumOf { it.size },
                    status = "Menggabungkan file... 0%"
                )

                try {
                    FileUtils.mergeFiles(allParts.map { java.io.File(it.localPath!!) }, targetFile) { progress ->
                        val percent = (progress * 100).toInt()
                        transferRepository.updateTransferManual(placeholderId, progress, "Menggabungkan file... $percent%")
                    }

                    // Save to public storage
                    transferRepository.saveToPublicDownloads(targetFile.absolutePath, originalName)
                    
                    // Cleanup merged file
                    targetFile.delete()
                } catch (e: Exception) {
                    Log.e("DriveRepo", "Merge failed for $groupId", e)
                } finally {
                    transferRepository.removeTransfer(placeholderId)
                }
            }
        }
    }

    private data class SplitMetadata(
        val groupId: String,
        val partIndex: Int,
        val totalParts: Int,
        val originalName: String
    )

    private fun parseSplitMetadata(caption: String): SplitMetadata? {
        if (!caption.startsWith("[TD_SPLIT|")) return null
        return try {
            val parts = caption.removePrefix("[TD_SPLIT|").removeSuffix("]").split("|")
            val id = parts.find { it.startsWith("ID:") }?.removePrefix("ID:") ?: ""
            val partInfo = parts.find { it.startsWith("PART:") }?.removePrefix("PART:")?.split("/")
            val name = parts.find { it.startsWith("NAME:") }?.removePrefix("NAME:") ?: ""
            
            SplitMetadata(
                groupId = id,
                partIndex = partInfo?.get(0)?.toInt() ?: 0,
                totalParts = partInfo?.get(1)?.toInt() ?: 1,
                originalName = name
            )
        } catch (e: Exception) {
            null
        }
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
}
