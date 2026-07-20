package com.awd.teledrive.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class MediaBackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "backup_channel"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MediaBackupWorkerEntryPoint {
        fun driveRepository(): DriveRepository
        fun secureSettings(): SecureSettings
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo(0, 0))
            
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MediaBackupWorkerEntryPoint::class.java
            )
            val driveRepository = entryPoint.driveRepository()
            val secureSettings = entryPoint.secureSettings()
            
            val backupFolders = secureSettings.getStringList("backup_folders")
            if (backupFolders.isEmpty()) return@withContext Result.success()

            // Update status to Scanning
            setForeground(createForegroundInfo(0, 0))

            val lastBackupTime = inputData.getLong("last_backup_time", 0L)
            val newMedia = scanNewMedia(lastBackupTime, backupFolders)
            
            if (newMedia.isEmpty()) return@withContext Result.success()

            newMedia.forEachIndexed { index, file ->
                val currentCount = index + 1
                val totalCount = newMedia.size
                
                // Set progress for UI
                setProgress(workDataOf(
                    "progress" to index.toFloat() / totalCount, 
                    "fileName" to file.name,
                    "current" to currentCount,
                    "total" to totalCount
                ))
                
                // Show notification "File X of Y"
                setForeground(createForegroundInfo(currentCount, totalCount))
                
                // Upload and wait for it to be registered
                driveRepository.uploadFile(file.absolutePath, file.name)
                
                // Add a small delay between starting uploads to prevent spamming the system
                // and allow TDLib to process the file metadata
                delay(2000)
                
                // Note: We don't wait for completion here because uploads can be very slow.
                // We just ensure we don't spam the initial SendMessage requests.
            }
            
            secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        createNotificationChannel()
        val title = applicationContext.getString(R.string.auto_backup)
        val content = if (total > 0) {
            applicationContext.getString(R.string.backing_up_media_count, current, total)
        } else {
            applicationContext.getString(R.string.scanning_media)
        }
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setProgress(total, current, total == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.auto_backup)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scanNewMedia(sinceTimestamp: Long, folders: List<String>): List<File> {
        val files = mutableListOf<File>()
        
        // 1. Scan via MediaStore (fast for Gallery folders)
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf((sinceTimestamp / 1000).toString())

        val mediaUris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )

        mediaUris.forEach { uri ->
            applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex)
                    if (folders.any { path.startsWith(it) }) {
                        files.add(File(path))
                    }
                }
            }
        }

        // 2. Scan recursively for all files in the selected folders (handles non-media and sub-folders)
        folders.forEach { folderPath ->
            val rootFolder = File(folderPath)
            if (rootFolder.exists() && rootFolder.isDirectory) {
                scanFolderRecursive(rootFolder, sinceTimestamp, files)
            }
        }
        
        return files.distinctBy { it.absolutePath }.sortedBy { it.lastModified() }
    }

    private fun scanFolderRecursive(folder: File, sinceTimestamp: Long, resultList: MutableList<File>) {
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanFolderRecursive(file, sinceTimestamp, resultList)
            } else if (file.isFile && file.lastModified() > sinceTimestamp) {
                resultList.add(file)
            }
        }
    }
}
