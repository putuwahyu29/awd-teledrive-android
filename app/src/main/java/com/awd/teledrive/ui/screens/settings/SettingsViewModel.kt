package com.awd.teledrive.ui.screens.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.core.utils.VersionUtils
import com.awd.teledrive.data.repository.AuthRepository
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.repository.UpdateRepository
import com.awd.teledrive.data.repository.UpdateState
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val driveRepository: DriveRepository,
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
    private val secureSettings: SecureSettings,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    
    val internalCacheSize: StateFlow<Long> = driveRepository.getInternalCacheSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _cacheStatistics = MutableStateFlow(SettingsRepository.CacheStatistics())
    val cacheStatistics = _cacheStatistics.asStateFlow()

    fun refreshCacheStatistics() {
        settingsRepository.getStorageStatistics { stats ->
            _cacheStatistics.value = stats
        }
    }

    val cacheSizeLimit = settingsRepository.cacheSizeLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_CACHE_SIZE)

    val cacheAgeLimit = settingsRepository.cacheAgeLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_CACHE_AGE)

    val updateState = updateRepository.updateState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateState.Idle)

    val versionName = VersionUtils.getVersionName(context)
    val versionCode = VersionUtils.getVersionCode(context)

    private val _currentLanguage = MutableStateFlow(
        secureSettings.getString("app_language") ?: 
        AppCompatDelegate.getApplicationLocales().get(0)?.language ?: 
        Locale.getDefault().language
    )
    val currentLanguage = _currentLanguage.asStateFlow()

    private val _downloadUri = MutableStateFlow(secureSettings.getDownloadUri())
    val downloadUri = _downloadUri.asStateFlow()

    fun getApiCredentials(): Pair<Int, String> {
        val id = secureSettings.getInt("api_id", 0)
        val hash = secureSettings.getString("api_hash", "") ?: ""
        return id to hash
    }

    fun setDownloadUri(uri: String?) {
        if (uri == null) {
            secureSettings.saveString("download_uri", "")
        } else {
            secureSettings.saveDownloadUri(uri)
        }
        _downloadUri.value = uri
    }

    fun setLanguage(languageCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        
        secureSettings.saveString("app_language", languageCode)

        _currentLanguage.value = languageCode
    }

    fun logout() {
        authRepository.logout()
    }

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        authRepository.saveApiCredentials(apiId, apiHash)
    }

    fun clearCache() {
        settingsRepository.clearCacheAggrersively {
            // After clearing, we might want to refresh UI or drive info
            driveRepository.fetchFiles()
        }
    }

    fun setCacheSizeLimit(limit: Long) {
        settingsRepository.setCacheSizeLimit(limit)
    }

    fun setCacheAgeLimit(limitSeconds: Int) {
        settingsRepository.setCacheAgeLimit(limitSeconds)
    }

    fun clearCacheByType(fileType: TdApi.FileType?) {
        val isThumbnail = fileType is TdApi.FileTypeThumbnail
        val isAll = fileType == null
        
        settingsRepository.clearCacheByType(fileType, isMediaAndFiles = false) { stats ->
            if (isThumbnail) driveRepository.clearDatabaseLocalPaths(onlyThumbnails = true)
            else if (isAll) driveRepository.clearDatabaseLocalPaths()
            
            _cacheStatistics.value = stats
            driveRepository.fetchFiles()
        }
    }

    fun clearMediaAndFilesCache() {
        settingsRepository.clearCacheByType(null, isMediaAndFiles = true) { stats ->
            driveRepository.clearDatabaseLocalPaths(onlyFiles = true)
            _cacheStatistics.value = stats
            driveRepository.fetchFiles()
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateRepository.checkForUpdates(manual = true)
        }
    }

    fun resetUpdateState() {
        updateRepository.resetState()
    }
}
