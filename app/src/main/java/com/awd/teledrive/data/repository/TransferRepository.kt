package com.awd.teledrive.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _transfers = MutableStateFlow<Map<String, TransferInfo>>(emptyMap())
    val transfers = _transfers.asStateFlow()

    init {
        scope.launch {
            telegramClient.fileUpdates.collect { update ->
                val file = update.file
                val uniqueId = file.remote.uniqueId
                val fileId = file.id
                
                Log.d("TransferRepo", "Received UpdateFile: id=$fileId, uniqueId=$uniqueId, downloaded=${file.local.downloadedSize}/${file.expectedSize}, active=${file.local.isDownloadingActive}")

                val currentTransfers = _transfers.value.toMutableMap()
                
                // Enhanced matching logic
                var transferKey: String? = null
                
                if (uniqueId.isNotEmpty() && currentTransfers.containsKey(uniqueId)) {
                    transferKey = uniqueId
                } else {
                    // Try to find by fileId
                    val entry = currentTransfers.entries.find { it.value.fileId == fileId }
                    if (entry != null) {
                        transferKey = entry.key
                    }
                }

                if (transferKey != null) {
                    val oldInfo = currentTransfers[transferKey]!!
                    
                    Log.d("TransferRepo", "Processing update for ${oldInfo.fileName}: downloaded=${file.local.downloadedSize}, status=${file.local.isDownloadingActive}")

                    // Update key if we found a uniqueId for a temp entry
                    var finalKey = transferKey
                    if (uniqueId.isNotEmpty() && transferKey.startsWith("temp_")) {
                        currentTransfers.remove(transferKey)
                        finalKey = uniqueId
                    }

                    val totalSize = when {
                        file.expectedSize > 0 -> file.expectedSize
                        file.size > 0 -> file.size
                        oldInfo.totalSize > 0 -> oldInfo.totalSize
                        else -> 0L
                    }

                    val isCompleted = if (oldInfo.isDownload) {
                        file.local.isDownloadingCompleted
                    } else {
                        file.remote.isUploadingCompleted
                    }

                    val progress = when {
                        isCompleted -> 1.0
                        totalSize > 0 -> {
                            if (oldInfo.isDownload) {
                                file.local.downloadedSize.toDouble() / totalSize.toDouble()
                            } else {
                                file.remote.uploadedSize.toDouble() / totalSize.toDouble()
                            }
                        }
                        else -> 0.0
                    }
                    
                    val status = when {
                        isCompleted -> "Selesai"
                        oldInfo.isDownload && file.local.isDownloadingActive -> "Mengunduh"
                        !oldInfo.isDownload && (file.remote.isUploadingActive || file.remote.uploadedSize > 0) -> "Mengunggah"
                        file.local.canBeDownloaded.not() && oldInfo.isDownload && !file.local.isDownloadingCompleted -> "Gagal"
                        else -> oldInfo.status
                    }

                    val newInfo = oldInfo.copy(
                        fileId = file.id,
                        remoteUniqueId = if (uniqueId.isNotEmpty()) uniqueId else oldInfo.remoteUniqueId,
                        progress = if (isCompleted) 1f else progress.toFloat(),
                        status = status,
                        totalSize = totalSize,
                        downloadedSize = if (oldInfo.isDownload) file.local.downloadedSize else file.remote.uploadedSize
                    )
                    
                    if (newInfo != oldInfo || finalKey != transferKey) {
                        currentTransfers[finalKey] = newInfo
                        _transfers.value = currentTransfers
                        Log.d("TransferRepo", "Update progress: ${newInfo.fileName} - ${(newInfo.progress * 100).toInt()}% ($status)")
                    }
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

    fun addTransfer(fileId: Int, remoteUniqueId: String, fileName: String, isDownload: Boolean, totalSize: Long = 0, isCompleted: Boolean = false) {
        Log.d("TransferRepo", "Adding transfer: $fileName, uniqueId: $remoteUniqueId, fileId: $fileId, size: $totalSize, completed: $isCompleted")
        val currentTransfers = _transfers.value.toMutableMap()
        currentTransfers[remoteUniqueId] = TransferInfo(
            fileId = fileId,
            remoteUniqueId = remoteUniqueId,
            fileName = fileName,
            progress = if (isCompleted) 1f else 0f,
            isDownload = isDownload,
            status = if (isCompleted) "Selesai" else (if (isDownload) "Mengunduh" else "Mengunggah"),
            totalSize = totalSize,
            downloadedSize = if (isCompleted) totalSize else 0L
        )
        _transfers.value = currentTransfers
    }

    fun cancelTransfer(uniqueId: String) {
        val currentTransfers = _transfers.value.toMutableMap()
        val info = currentTransfers[uniqueId] ?: return
        
        // CancelDownloadFile often works for both download and upload in TDLib for a given fileId
        telegramClient.send(org.drinkless.tdlib.TdApi.CancelDownloadFile(info.fileId, true))
        
        currentTransfers[uniqueId] = info.copy(status = "Dibatalkan")
        _transfers.value = currentTransfers
    }

    fun clearCompleted() {
        val currentTransfers = _transfers.value.toMutableMap()
        val filtered = currentTransfers.filter { it.value.status == "Mengunduh" || it.value.status == "Mengunggah" }
        _transfers.value = filtered
    }
}
