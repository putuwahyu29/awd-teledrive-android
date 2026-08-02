package com.awd.teledrive.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.TransferDao
import com.awd.teledrive.data.local.TransferEntity
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val transferDao: TransferDao,
    private val driveDao: DriveDao,
    @param:ApplicationContext private val context: Context,
) {
    object Status {
        const val QUEUED = "Queued"
        const val DOWNLOADING = "Downloading"
        const val UPLOADING = "Uploading"
        const val COMPLETED = "Completed"
        const val FAILED = "Failed"
        const val CANCELLED = "Cancelled"
    }

    private data class PendingTransfer(
        val remoteUniqueId: String,
        val block: suspend () -> Unit
    )

    private val queue = mutableListOf<PendingTransfer>()
    private var activeTransferId: String? = null
    private val completionDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val idMigrationMap = mutableMapOf<String, String>()
    private val lastUpdateMap = mutableMapOf<String, Long>()
    private val lastProgressMap = mutableMapOf<String, Float>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    val transfers = transferDao.getAllTransfersFlow()
        .map { entities ->
            entities.associateBy({ it.remoteUniqueId }, { entity ->
                TransferInfo(
                    fileId = entity.fileId,
                    remoteUniqueId = entity.remoteUniqueId,
                    fileName = entity.fileName,
                    progress = entity.progress,
                    isDownload = entity.isDownload,
                    status = entity.status,
                    totalSize = entity.totalSize,
                    downloadedSize = entity.downloadedSize,
                    localPath = entity.localPath
                )
            })
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                val localPath = file.local.path
                
                // Find in DB with path priority to prevent duplicates during handover
                val entity = (if (localPath.isNotEmpty()) transferDao.getTransferByLocalPath(localPath) else null)
                    ?: transferDao.getTransferByUniqueId(uniqueId) 
                    ?: transferDao.getTransferByFileId(fileId)
                    ?: return@collect
                
                val isDownload = entity.isDownload
                val isCompleted = if (isDownload) file.local.isDownloadingCompleted else file.remote.isUploadingCompleted
                val totalSize = if (file.expectedSize > 0L) file.expectedSize else (if (file.size > 0L) file.size else entity.totalSize)
                
                val currentSize = if (isDownload) file.local.downloadedSize else file.remote.uploadedSize
                val progress = if (isCompleted) 1.0f else (if (totalSize > 0L) currentSize.toFloat() / totalSize.toFloat() else 0.0f)
                
                // Aggressive completion check for uploads: if progress is 100%, mark as COMPLETED
                val isEffectivelyCompleted = isCompleted || (!isDownload && totalSize > 0L && currentSize >= totalSize)
                
                val status = when {
                    isEffectivelyCompleted -> Status.COMPLETED
                    isDownload && file.local.isDownloadingActive -> Status.DOWNLOADING
                    !isDownload && (file.remote.isUploadingActive || file.remote.uploadedSize > 0L) -> Status.UPLOADING
                    file.local.canBeDownloaded.not() && isDownload && !file.local.isDownloadingCompleted -> Status.FAILED
                    else -> entity.status
                }
                
                val isTerminal = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
                if (isTerminal) {
                    synchronized(queue) {
                        if (uniqueId.isNotEmpty()) {
                            completionDeferreds[uniqueId]?.complete(Unit)
                            Log.d("TransferRepo", "Completed deferred for uniqueId: $uniqueId")
                        }
                        completionDeferreds[entity.remoteUniqueId]?.complete(Unit)
                        // Also try by fileId
                        completionDeferreds[fileId.toString()]?.complete(Unit)
                        
                        if (activeTransferId == uniqueId || activeTransferId == entity.remoteUniqueId || activeTransferId == fileId.toString()) {
                            activeTransferId = null
                            Log.d("TransferRepo", "Active transfer cleared. Processing next in queue.")
                            processQueue()
                        }
                    }

                    // CRITICAL FIX: If it's a completed upload, clear the localPath in drive_items
                    // to prevent ENOENT errors (since the temp/cache file is now likely deleted)
                    if (status == Status.COMPLETED && !isDownload && uniqueId.isNotEmpty()) {
                        scope.launch {
                            driveDao.updateLocalPathByUniqueId(uniqueId, null)
                        }
                    }
                }
                
                // If ID has changed (e.g. from temp to real), perform migration
                if (uniqueId.isNotEmpty() && entity.remoteUniqueId != uniqueId) {
                    Log.d("TransferRepo", "Handover detected in init: Migrating ${entity.remoteUniqueId} -> $uniqueId")
                    updateRemoteUniqueId(entity.remoteUniqueId, uniqueId, fileId, localPath.takeIf { it.isNotEmpty() })
                } else {
                    transferDao.updateProgress(entity.remoteUniqueId, progress, status, currentSize)
                }
            }
        }
    }

    fun saveToPublicDownloads(internalPath: String, fileName: String) {
        try {
            val sourceFile = File(internalPath)
            if (!sourceFile.exists()) {
                Log.e("TransferRepo", "Source file not found: $internalPath")
                return
            }

            val userDownloadUri = secureSettings.getDownloadUri()
            if (!userDownloadUri.isNullOrEmpty()) {
                saveToUserSelectedFolder(sourceFile, fileName, userDownloadUri)
                return
            }

            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("TransferRepo", "Berhasil menyimpan ke folder Download: $fileName")
                } else {
                    Log.e("TransferRepo", "Gagal membuat MediaStore entry untuk $fileName")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                val destFile = File(downloadsDir, fileName)
                FileOutputStream(destFile).use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d("TransferRepo", "Berhasil menyimpan ke folder Download: $fileName")
            }
        } catch (e: Exception) {
            Log.e("TransferRepo", "Error saat menyimpan file: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun saveToUserSelectedFolder(sourceFile: File, fileName: String, uriString: String) {
        try {
            val treeUri = android.net.Uri.parse(uriString)
            val docUri = DocumentFile.fromTreeUri(context, treeUri)
            
            if (docUri == null || !docUri.canWrite()) {
                Log.e("TransferRepo", "Selected folder is not writable or no longer exists: $uriString")
                // Fallback to standard Downloads if SAF fails
                secureSettings.saveDownloadUri("") 
                return
            }

            // Create file in selected folder
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            
            val newFile = docUri.createFile(mimeType, fileName)
            if (newFile != null) {
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d("TransferRepo", "Berhasil menyimpan ke folder pilihan user: $fileName")
            }
        } catch (e: Exception) {
            Log.e("TransferRepo", "Gagal menyimpan ke folder pilihan user: ${e.message}")
        }
    }

    fun updateRemoteUniqueId(oldId: String, newUniqueId: String, newFileId: Int, localPath: String? = null) {
        if (oldId == newUniqueId && localPath == null) return
        
        // Eagerly update migration map to prevent race conditions with progress updates
        synchronized(queue) {
            if (oldId != newUniqueId) {
                idMigrationMap[oldId] = newUniqueId
                Log.d("TransferRepo", "Migration map updated: $oldId -> $newUniqueId")
            }
            if (activeTransferId == oldId) {
                activeTransferId = newUniqueId
            }
            completionDeferreds[oldId]?.let {
                completionDeferreds[newUniqueId] = it
                completionDeferreds.remove(oldId)
            }
        }

        scope.launch {
            try {
                if (oldId != newUniqueId) {
                    // Check if newUniqueId already exists to avoid PK conflict
                    val existing = transferDao.getTransferByUniqueId(newUniqueId)
                    if (existing != null) {
                        Log.d("TransferRepo", "Target ID $newUniqueId already exists. Deleting old ID $oldId.")
                        transferDao.deleteTransfer(oldId)
                    } else {
                        Log.d("TransferRepo", "Updating ID in DB: $oldId -> $newUniqueId")
                        transferDao.updateUniqueId(oldId, newUniqueId)
                    }
                }
                
                // Update fileId and localPath if provided
                if (newFileId != 0) {
                    transferDao.updateFileId(newUniqueId, newFileId)
                }
                if (localPath != null) {
                    transferDao.updateLocalPath(newUniqueId, localPath)
                }
            } catch (e: Exception) {
                Log.e("TransferRepo", "Migration failed in DB: ${e.message}")
            }
        }
    }

    fun enqueueTransfer(
        fileId: Int,
        remoteUniqueId: String,
        fileName: String,
        isDownload: Boolean,
        totalSize: Long,
        localPath: String? = null,
        block: suspend () -> Unit
    ) {
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.currentTimeMillis()}_$fileId"
            
            // Check if already in queue or active
            if (activeTransferId == key || queue.any { it.remoteUniqueId == key }) return@launch
            
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = 0f,
                isDownload = isDownload,
                status = Status.QUEUED,
                totalSize = totalSize,
                downloadedSize = 0L,
                localPath = localPath
            )
            transferDao.insertTransfer(entity)
            
            synchronized(queue) {
                queue.add(PendingTransfer(key, block))
            }
            processQueue()
        }
    }

    private fun processQueue() {
        synchronized(queue) {
            if (activeTransferId != null || queue.isEmpty()) return
            
            val next = queue.removeAt(0)
            activeTransferId = next.remoteUniqueId
            
            scope.launch {
                try {
                    // Initial status update
                    val entity = transferDao.getTransferByUniqueId(next.remoteUniqueId)
                    if (entity != null) {
                        val startStatus = if (entity.isDownload) Status.DOWNLOADING else Status.UPLOADING
                        transferDao.updateProgress(next.remoteUniqueId, 0f, startStatus, 0L)
                    }
                    
                    // Execute the actual task
                    next.block()
                } catch (e: Exception) {
                    Log.e("TransferRepo", "Error in queued task ${next.remoteUniqueId}: ${e.message}")
                    transferDao.updateProgress(next.remoteUniqueId, 0f, Status.FAILED, 0L)
                    
                    // On failure, move to next
                    synchronized(queue) {
                        if (activeTransferId == next.remoteUniqueId) {
                            activeTransferId = null
                        }
                    }
                    processQueue()
                }
            }
        }
    }

    fun addTransfer(fileId: Int, remoteUniqueId: String, fileName: String, isDownload: Boolean, totalSize: Long = 0, isCompleted: Boolean = false, status: String? = null, localPath: String? = null) {
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.currentTimeMillis()}_$fileId"
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = if (isCompleted) 1f else 0f,
                isDownload = isDownload,
                status = status ?: if (isCompleted) Status.COMPLETED else (if (isDownload) Status.DOWNLOADING else Status.UPLOADING),
                totalSize = totalSize,
                downloadedSize = if (isCompleted) totalSize else 0L,
                localPath = localPath
            )
            transferDao.insertTransfer(entity)
            
            // If we manually add an active transfer, we should track it to not block the queue
            if (entity.status == Status.DOWNLOADING || entity.status == Status.UPLOADING) {
                activeTransferId = key
            }
        }
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String, throttled: Boolean = false) {
        val targetId = synchronized(queue) { idMigrationMap[remoteUniqueId] ?: remoteUniqueId }
        
        if (throttled) {
            val now = System.currentTimeMillis()
            val lastTime = lastUpdateMap[targetId] ?: 0L
            val lastProgress = lastProgressMap[targetId] ?: -1f
            
            // Limit to once every 500ms OR 2% progress change to really reduce DB pressure
            if (now - lastTime < 500 && Math.abs(progress - lastProgress) < 0.02f) {
                return
            }
            lastUpdateMap[targetId] = now
            lastProgressMap[targetId] = progress
        }

        // Use a single-threaded approach or sequential queue for DB writes during preparation
        // to prevent "Database is locked" or high contention
        scope.launch {
            try {
                if (progress >= 1.0f || status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED || status.startsWith("Gagal") || status.startsWith("Failed")) {
                    val entity = transferDao.getTransferByUniqueId(targetId)
                    if (entity != null) {
                        transferDao.updateProgressFull(targetId, progress, status, entity.totalSize)
                    } else {
                        transferDao.updateProgressOnly(targetId, progress, status)
                    }
                    
                    // Signal completion if anyone is waiting
                    synchronized(queue) {
                        completionDeferreds[targetId]?.complete(Unit)
                        completionDeferreds[remoteUniqueId]?.complete(Unit)
                        if (activeTransferId == targetId || activeTransferId == remoteUniqueId) {
                            activeTransferId = null
                            processQueue()
                        }
                    }
                } else {
                    transferDao.updateProgressOnly(targetId, progress, status)
                }
            } catch (e: Exception) {
                Log.e("TransferRepo", "Failed to update progress for $targetId: ${e.message}")
            }
        }
    }

    fun removeTransfer(remoteUniqueId: String) {
        scope.launch {
            transferDao.deleteTransfer(remoteUniqueId)
        }
    }

    fun cancelTransfer(uniqueId: String) {
        scope.launch {
            var foundInQueue = false
            synchronized(queue) {
                val inQueue = queue.find { it.remoteUniqueId == uniqueId }
                if (inQueue != null) {
                    queue.remove(inQueue)
                    foundInQueue = true
                }
                completionDeferreds[uniqueId]?.complete(Unit)
            }

            if (foundInQueue) {
                transferDao.updateProgress(uniqueId, 0f, Status.CANCELLED, 0L)
                return@launch
            }

            val entity = transferDao.getTransferByUniqueId(uniqueId) ?: return@launch
            telegramClient.send(org.drinkless.tdlib.TdApi.CancelDownloadFile(entity.fileId, true))
            transferDao.updateProgress(uniqueId, entity.progress, Status.CANCELLED, entity.downloadedSize)
            
            if (activeTransferId == uniqueId) {
                activeTransferId = null
                processQueue()
            }
        }
    }

    fun clearCompleted() {
        scope.launch {
            transferDao.clearCompleted()
        }
    }

    suspend fun waitForCompletion(remoteUniqueId: String) {
        // Resolve final ID in case of migration
        val finalId = synchronized(queue) { idMigrationMap[remoteUniqueId] ?: remoteUniqueId }
        
        // Check current status first to avoid waiting for already completed transfers
        val currentTransfers = transfers.value
        val currentTransfer = currentTransfers[finalId]
        if (currentTransfer != null) {
            val status = currentTransfer.status
            if (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED || status.startsWith("Failed")) {
                Log.d("TransferRepo", "waitForCompletion: $finalId already in terminal state: $status")
                return
            }
        }

        val deferred = synchronized(queue) {
            // Re-check migration inside lock to be safe
            val targetId = idMigrationMap[remoteUniqueId] ?: remoteUniqueId
            completionDeferreds.getOrPut(targetId) { 
                Log.d("TransferRepo", "waitForCompletion: Creating new deferred for $targetId")
                CompletableDeferred() 
            }
        }
        deferred.await()
        synchronized(queue) {
            completionDeferreds.remove(finalId)
            val originalId = idMigrationMap.filterValues { it == finalId }.keys.firstOrNull()
            if (originalId != null) idMigrationMap.remove(originalId)
            Log.d("TransferRepo", "waitForCompletion: $finalId finished waiting.")
        }
    }
}
