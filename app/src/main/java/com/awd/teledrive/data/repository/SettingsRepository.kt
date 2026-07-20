package com.awd.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    @param:ApplicationContext private val context: Context
) {
    data class CacheStatistics(
        val totalSize: Long = 0,
        val thumbnails: Long = 0,
        val mediaAndFiles: Long = 0,
        val database: Long = 0
    )

    private val _cacheSizeLimit = MutableStateFlow(secureSettings.getLong(KEY_CACHE_SIZE_LIMIT, DEFAULT_CACHE_SIZE))
    val cacheSizeLimit = _cacheSizeLimit.asStateFlow()

    private val _cacheAgeLimit = MutableStateFlow(secureSettings.getInt(KEY_CACHE_AGE_LIMIT, DEFAULT_CACHE_AGE))
    val cacheAgeLimit = _cacheAgeLimit.asStateFlow()

    private val _isThumbnailAutoDownloadEnabled = MutableStateFlow(secureSettings.getBoolean(KEY_THUMBNAIL_AUTO_DOWNLOAD, true))
    val isThumbnailAutoDownloadEnabled = _isThumbnailAutoDownloadEnabled.asStateFlow()

    private val _isBackupWifiOnlyEnabled = MutableStateFlow(secureSettings.getBoolean(KEY_BACKUP_WIFI_ONLY, true))
    val isBackupWifiOnlyEnabled = _isBackupWifiOnlyEnabled.asStateFlow()

    private val _isAutoBackupEnabled = MutableStateFlow(secureSettings.getBoolean(KEY_AUTO_BACKUP, false))
    val isAutoBackupEnabled = _isAutoBackupEnabled.asStateFlow()

    fun setCacheSizeLimit(limitBytes: Long) {
        secureSettings.saveLong(KEY_CACHE_SIZE_LIMIT, limitBytes)
        _cacheSizeLimit.value = limitBytes
        applyCacheSettings()
    }

    fun setCacheAgeLimit(limitSeconds: Int) {
        secureSettings.saveInt(KEY_CACHE_AGE_LIMIT, limitSeconds)
        _cacheAgeLimit.value = limitSeconds
        applyCacheSettings()
    }

    fun setThumbnailAutoDownloadEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_THUMBNAIL_AUTO_DOWNLOAD, enabled)
        _isThumbnailAutoDownloadEnabled.value = enabled
    }

    fun setBackupWifiOnlyEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_BACKUP_WIFI_ONLY, enabled)
        _isBackupWifiOnlyEnabled.value = enabled
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_AUTO_BACKUP, enabled)
        _isAutoBackupEnabled.value = enabled
    }

    /**
     * Applies cache settings to TDLib. 
     * Note: TDLib uses options for global limits.
     */
    fun applyCacheSettings() {
        val totalLimit = _cacheSizeLimit.value
        val ageLimit = _cacheAgeLimit.value

        if (totalLimit == 0L) {
            // Unlimited
            telegramClient.send(TdApi.SetOption("storage_max_files_size", TdApi.OptionValueInteger(0)))
        } else {
            // Calculate non-TDLib cache usage to adjust TDLib's portion
            var otherUsage = 0L
            otherUsage += calculateDirectorySize(context.cacheDir)
            context.externalCacheDir?.let { otherUsage += calculateDirectorySize(it) }

            // TDLib should use (totalLimit - otherUsage), but at least a minimum (e.g. 50MB) to function
            val tdlibLimit = (totalLimit - otherUsage).coerceAtLeast(50 * 1024 * 1024L)
            telegramClient.send(TdApi.SetOption("storage_max_files_size", TdApi.OptionValueInteger(tdlibLimit)))
            
            // Immediate optimization to enforce the new limit
            telegramClient.send(TdApi.OptimizeStorage(
                tdlibLimit,
                0, 0, 0, null, null, null, false, 0
            ))
        }
        
        // storage_max_time_from_last_access: Max time from the last access to a file in the storage, in seconds. 0 for unlimited.
        telegramClient.send(TdApi.SetOption("storage_max_time_from_last_access", TdApi.OptionValueInteger(ageLimit.toLong())))
    }

    private fun calculateDirectorySize(directory: java.io.File): Long {
        var size: Long = 0
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
        }
        return size
    }

    fun clearCacheAggrersively(onComplete: () -> Unit = {}) {
        // Aggressive clearing: delete all files except for some essential ones if needed.
        // TdApi.OptimizeStorage parameters:
        // size, ttl, count, immunityDelay, fileTypes, chatIds, excludeChatIds, returnDeletedFileStatistics, chatLimit
        telegramClient.send(TdApi.OptimizeStorage(
            0, // size 0 means clear everything possible
            0, // ttl 0 means no time immunity
            0, // count 0 means delete all files
            0, // immunityDelay 0
            null, // all file types
            null, // all chats
            null, // no excluded chats
            true,
            0
        )) {
            onComplete()
        }
    }

    fun getStorageStatistics(onResult: (CacheStatistics) -> Unit) {
        telegramClient.send(TdApi.GetStorageStatistics(100)) { result ->
            if (result is TdApi.StorageStatistics) {
                onResult(mapStatistics(result))
            } else {
                onResult(CacheStatistics())
            }
        }
    }

    private fun mapStatistics(result: TdApi.StorageStatistics): CacheStatistics {
        var thumbnails = 0L
        var photos = 0L
        var videos = 0L
        var audio = 0L
        var documents = 0L
        var others = 0L

        result.byChat.forEach { chatStat ->
            chatStat.byFileType.forEach { stat ->
                when (stat.fileType) {
                    is TdApi.FileTypeThumbnail -> thumbnails += stat.size
                    is TdApi.FileTypePhoto -> photos += stat.size
                    is TdApi.FileTypeVideo, is TdApi.FileTypeVideoNote -> videos += stat.size
                    is TdApi.FileTypeAudio, is TdApi.FileTypeVoiceNote -> audio += stat.size
                    is TdApi.FileTypeDocument -> documents += stat.size
                    else -> others += stat.size
                }
            }
        }

        val totalRawSize = calculateDirectorySize(context.filesDir) + 
                          calculateDirectorySize(context.cacheDir) + 
                          (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L)

        val mediaAndFilesTotal = photos + videos + audio + documents + others
        val databaseAndSystem = (totalRawSize - (mediaAndFilesTotal + thumbnails + calculateDirectorySize(context.cacheDir) + (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L))).coerceAtLeast(0L)

        return CacheStatistics(
            totalSize = totalRawSize,
            thumbnails = thumbnails + calculateDirectorySize(context.cacheDir) + (context.externalCacheDir?.let { calculateDirectorySize(it) } ?: 0L),
            mediaAndFiles = mediaAndFilesTotal,
            database = databaseAndSystem
        )
    }

    fun clearCacheByType(fileType: TdApi.FileType?, isMediaAndFiles: Boolean = false, onResult: (CacheStatistics) -> Unit) {
        val fileTypes = if (isMediaAndFiles) {
            arrayOf(
                TdApi.FileTypePhoto(),
                TdApi.FileTypeVideo(),
                TdApi.FileTypeAudio(),
                TdApi.FileTypeDocument(),
                TdApi.FileTypeNone()
            )
        } else if (fileType != null && fileType !is TdApi.FileTypeNone) {
            arrayOf(fileType)
        } else {
            null
        }
        
        // If thumbnails or "all" (null) are selected, manually clear Android cache dirs too
        if (fileType == null || fileType is TdApi.FileTypeThumbnail || (fileType is TdApi.FileTypeNone && !isMediaAndFiles)) {
            try {
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                Log.d("SettingsRepo", "Android cache contents cleared manually")
            } catch (e: Exception) {
                Log.e("SettingsRepo", "Failed to clear Android cache contents", e)
            }
        }

        telegramClient.send(TdApi.OptimizeStorage(
            0, 0, 0, 0, fileTypes, null, null, true, 0
        )) { result ->
            if (result is TdApi.StorageStatistics) {
                onResult(mapStatistics(result))
            } else {
                getStorageStatistics(onResult)
            }
        }
    }

    companion object {
        const val KEY_CACHE_SIZE_LIMIT = "cache_size_limit"
        const val KEY_CACHE_AGE_LIMIT = "cache_age_limit"
        const val KEY_THUMBNAIL_AUTO_DOWNLOAD = "thumbnail_auto_download"
        const val KEY_BACKUP_WIFI_ONLY = "backup_wifi_only"
        const val KEY_AUTO_BACKUP = "auto_backup_enabled"

        // Default: 512MB
        const val DEFAULT_CACHE_SIZE = 512 * 1024 * 1024L
        // Default: 1 month (in seconds)
        const val DEFAULT_CACHE_AGE = 30 * 24 * 60 * 60 
        
        val CACHE_SIZE_OPTIONS = listOf(
            512 * 1024 * 1024L,   // 512MB
            1024 * 1024 * 1024L,  // 1GB
            2048 * 1024 * 1024L,  // 2GB
            5120 * 1024 * 1024L,  // 5GB
            10240 * 1024 * 1024L, // 10GB
            0L                    // Unlimited
        )

        val CACHE_AGE_OPTIONS = listOf(
            7 * 24 * 60 * 60,      // 1 week
            30 * 24 * 60 * 60,     // 1 month
            90 * 24 * 60 * 60,     // 3 months
            365 * 24 * 60 * 60,    // 1 year
            0                      // Forever
        )
    }
}
