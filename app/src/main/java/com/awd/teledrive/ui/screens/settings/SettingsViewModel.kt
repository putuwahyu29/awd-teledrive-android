package com.awd.teledrive.ui.screens.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.AuthRepository
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val driveRepository: DriveRepository,
    private val secureSettings: SecureSettings,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    
    val internalCacheSize: StateFlow<Long> = driveRepository.getInternalCacheSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

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
        driveRepository.clearInternalCache()
        // Refresh cache size immediately
    }
}
