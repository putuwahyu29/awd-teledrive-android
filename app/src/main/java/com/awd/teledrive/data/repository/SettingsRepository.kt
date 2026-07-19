package com.awd.teledrive.data.repository

import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings
) {
    private val _cacheSizeLimit = MutableStateFlow(secureSettings.getLong(KEY_CACHE_SIZE_LIMIT, DEFAULT_CACHE_SIZE))
    val cacheSizeLimit = _cacheSizeLimit.asStateFlow()

    private val _cacheAgeLimit = MutableStateFlow(secureSettings.getInt(KEY_CACHE_AGE_LIMIT, DEFAULT_CACHE_AGE))
    val cacheAgeLimit = _cacheAgeLimit.asStateFlow()

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

    /**
     * Applies cache settings to TDLib. 
     * Note: TDLib uses options for global limits.
     */
    fun applyCacheSettings() {
        val sizeLimit = _cacheSizeLimit.value
        val ageLimit = _cacheAgeLimit.value

        // storage_max_files_size: Max total size of files in the storage, in bytes. 0 for unlimited.
        telegramClient.send(TdApi.SetOption("storage_max_files_size", TdApi.OptionValueInteger(sizeLimit)))
        
        // storage_max_time_from_last_access: Max time from the last access to a file in the storage, in seconds. 0 for unlimited.
        telegramClient.send(TdApi.SetOption("storage_max_time_from_last_access", TdApi.OptionValueInteger(ageLimit.toLong())))
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

    companion object {
        const val KEY_CACHE_SIZE_LIMIT = "cache_size_limit"
        const val KEY_CACHE_AGE_LIMIT = "cache_age_limit"

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
