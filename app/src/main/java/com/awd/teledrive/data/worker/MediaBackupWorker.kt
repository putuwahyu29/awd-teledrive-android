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

            val lastBackupTime = inputData.getLong("last_backup_time", 0L)
            val newMedia = scanNewMedia(lastBackupTime, backupFolders)
            
            if (newMedia.isEmpty()) return@withContext Result.success()

            newMedia.forEachIndexed { index, file ->
                setProgress(workDataOf("progress" to index.toFloat() / newMedia.size, "fileName" to file.name))
                setForeground(createForegroundInfo(index + 1, newMedia.size))
                driveRepository.uploadFile(file.absolutePath, file.name)
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
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED)
        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf((sinceTimestamp / 1000).toString())

        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        applicationContext.contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIndex)
                if (folders.any { path.startsWith(it) }) {
                    files.add(File(path))
                }
            }
        }
        return files
    }
}
