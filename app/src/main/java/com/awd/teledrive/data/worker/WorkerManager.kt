package com.awd.teledrive.data.worker

import android.content.Context
import androidx.work.*
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TeleDriveWorkerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secureSettings: SecureSettings
) {
    fun scheduleBackup(enabled: Boolean) {
        try {
            if (!enabled) {
                WorkManager.getInstance(context).cancelUniqueWork("media_backup_worker")
                return
            }

            val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
            val data = Data.Builder()
                .putLong("last_backup_time", lastBackupTime)
                .build()

            val backupRequest = PeriodicWorkRequestBuilder<MediaBackupWorker>(1, TimeUnit.HOURS)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED) // Less restrictive for manual trigger
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "media_backup_worker",
                ExistingPeriodicWorkPolicy.UPDATE, // Update to force refresh
                backupRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerOneTimeBackup() {
        try {
            val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
            val data = Data.Builder()
                .putLong("last_backup_time", lastBackupTime)
                .build()

            val backupRequest = OneTimeWorkRequestBuilder<MediaBackupWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "manual_backup_worker",
                ExistingWorkPolicy.REPLACE,
                backupRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scheduleCoreRefresh() {
        val refreshRequest = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "core_refresh_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            refreshRequest
        )
    }

    fun getBackupWorkInfo(): kotlinx.coroutines.flow.Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("media_backup_worker")
    }
}
