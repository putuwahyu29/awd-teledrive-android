package com.awd.teledrive.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.data.local.TransferDao
import com.awd.teledrive.data.local.TransferEntity
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context,
) {
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
                    downloadedSize = entity.downloadedSize
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
                
                // Find in DB
                val entity = transferDao.getTransferByUniqueId(uniqueId) 
                    ?: transferDao.getTransferByFileId(fileId)
                    ?: return@collect
                
                val isDownload = entity.isDownload
                val isCompleted = if (isDownload) file.local.isDownloadingCompleted else file.remote.isUploadingCompleted
                val totalSize = if (file.expectedSize > 0L) file.expectedSize else (if (file.size > 0L) file.size else entity.totalSize)
                
                val currentSize = if (isDownload) file.local.downloadedSize else file.remote.uploadedSize
                val progress = if (isCompleted) 1.0f else (if (totalSize > 0L) currentSize.toFloat() / totalSize.toFloat() else 0.0f)
                
                val status = when {
                    isCompleted -> "Selesai"
                    isDownload && file.local.isDownloadingActive -> "Mengunduh"
                    !isDownload && (file.remote.isUploadingActive || file.remote.uploadedSize > 0L) -> "Mengunggah"
                    file.local.canBeDownloaded.not() && isDownload && !file.local.isDownloadingCompleted -> "Gagal"
                    else -> entity.status
                }
                
                if (uniqueId.isNotEmpty() && entity.remoteUniqueId != uniqueId) {
                    transferDao.deleteTransfer(entity.remoteUniqueId)
                    transferDao.insertTransfer(entity.copy(
                        remoteUniqueId = uniqueId,
                        fileId = fileId,
                        progress = progress,
                        status = status,
                        downloadedSize = currentSize,
                        totalSize = totalSize
                    ))
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

    fun addTransfer(fileId: Int, remoteUniqueId: String, fileName: String, isDownload: Boolean, totalSize: Long = 0, isCompleted: Boolean = false, status: String? = null) {
        scope.launch {
            val key = if (remoteUniqueId.isNotEmpty()) remoteUniqueId else "temp_${System.currentTimeMillis()}_$fileId"
            val entity = TransferEntity(
                remoteUniqueId = key,
                fileId = fileId,
                fileName = fileName,
                progress = if (isCompleted) 1f else 0f,
                isDownload = isDownload,
                status = status ?: if (isCompleted) "Selesai" else (if (isDownload) "Mengunduh" else "Mengunggah"),
                totalSize = totalSize,
                downloadedSize = if (isCompleted) totalSize else 0L
            )
            transferDao.insertTransfer(entity)
        }
    }

    fun updateTransferManual(remoteUniqueId: String, progress: Float, status: String) {
        scope.launch {
            val entity = transferDao.getTransferByUniqueId(remoteUniqueId) ?: return@launch
            val downloaded = (progress * entity.totalSize).toLong()
            transferDao.updateProgress(remoteUniqueId, progress, status, downloaded)
        }
    }

    fun removeTransfer(remoteUniqueId: String) {
        scope.launch {
            transferDao.deleteTransfer(remoteUniqueId)
        }
    }

    fun cancelTransfer(uniqueId: String) {
        scope.launch {
            val entity = transferDao.getTransferByUniqueId(uniqueId) ?: return@launch
            telegramClient.send(org.drinkless.tdlib.TdApi.CancelDownloadFile(entity.fileId, true))
            transferDao.updateProgress(uniqueId, entity.progress, "Dibatalkan", entity.downloadedSize)
        }
    }

    fun clearCompleted() {
        scope.launch {
            transferDao.clearCompleted()
        }
    }
}
