package com.awd.teledrive.data.repository

import android.content.Context
import com.awd.teledrive.core.Config
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.core.utils.VersionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

    private fun getApiId(): Int {
        val stored = secureSettings.getInt("api_id", 0)
        if (stored != 0) return stored
        if (secureSettings.getBoolean("api_explicitly_cleared", false)) return 0
        return Config.API_ID
    }

    private fun getApiHash(): String {
        val stored = secureSettings.getString("api_hash", "")
        if (!stored.isNullOrEmpty()) return stored
        if (secureSettings.getBoolean("api_explicitly_cleared", false)) return ""
        return Config.API_HASH
    }

    init {
        scope.launch {
            telegramClient.send(TdApi.GetAuthorizationState()) { result ->
                if (result is TdApi.AuthorizationState) {
                    _authState.value = result
                    handleAuthorizationState(result)
                }
            }

            telegramClient.updates.collect { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    _authState.value = update.authorizationState
                    handleAuthorizationState(update.authorizationState)
                }
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                // If we have stored credentials, we can try to auto-submit
                // But only if we are not explicitly cleared
                if (isApiConfigured() && !secureSettings.getBoolean("api_explicitly_cleared", false)) {
                    val apiId = getApiId()
                    val apiHash = getApiHash()
                    
                    // Use a private helper to avoid the redundant checks in saveApiCredentials
                    sendTdlibParameters(apiId, apiHash)
                }
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                telegramClient.send(TdApi.RegisterUser("TeleDrive", "User", false))
            }
        }
    }

    private fun sendTdlibParameters(apiId: Int, apiHash: String) {
        val parameters = TdApi.SetTdlibParameters(
            false,
            File(context.filesDir, "tdlib").absolutePath,
            "",
            null,
            true,
            true,
            true,
            false,
            apiId,
            apiHash,
            "en",
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE,
            VersionUtils.getVersionName(context)
        )

        telegramClient.send(parameters) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            } else {
                _authError.value = null
                // Apply cache settings once parameters are set
                settingsRepository.applyCacheSettings()
            }
        }
    }

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        _authError.value = null
        secureSettings.saveInt("api_id", apiId)
        secureSettings.saveString("api_hash", apiHash)
        secureSettings.saveBoolean("api_explicitly_cleared", false)

        // Only send if we are in the correct state
        val currentState = _authState.value
        if (currentState is TdApi.AuthorizationStateWaitTdlibParameters) {
            sendTdlibParameters(apiId, apiHash)
        } else if (currentState != null && currentState !is TdApi.AuthorizationStateWaitPhoneNumber) {
            // If we are already past WaitTdlibParameters, we might need to log out to re-set them
            // but for now, we just report that it's unexpected if not in WaitPhoneNumber/WaitTdlib
            // Actually, if we are in WaitPhoneNumber, it's also "unexpected" to set parameters.
        }
    }

    fun clearApiCredentials() {
        _authError.value = null
        secureSettings.saveInt("api_id", 0)
        secureSettings.saveString("api_hash", "")
        secureSettings.saveBoolean("api_explicitly_cleared", true)
        
        // Force state to reset immediately for UI
        _authState.value = TdApi.AuthorizationStateWaitTdlibParameters()
        
        // Tell TDLib to reset
        telegramClient.send(TdApi.LogOut())
    }

    fun isApiConfigured(): Boolean {
        return getApiId() != 0 && getApiHash().isNotEmpty()
    }

    fun setPhoneNumber(phoneNumber: String) {
        _authError.value = null
        telegramClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun checkAuthenticationCode(code: String) {
        _authError.value = null
        telegramClient.send(TdApi.CheckAuthenticationCode(code)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun checkPassword(password: String) {
        _authError.value = null
        telegramClient.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun setLocalError(message: String?) {
        _authError.value = if (message.isNullOrEmpty()) null else message
    }

    fun logout() {
        _authError.value = null
        telegramClient.send(TdApi.LogOut()) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
            // Langsung minta status terbaru untuk memperbarui StateFlow
            telegramClient.send(TdApi.GetAuthorizationState()) { state ->
                if (state is TdApi.AuthorizationState) {
                    _authState.value = state
                    handleAuthorizationState(state)
                }
            }
        }
    }
}
