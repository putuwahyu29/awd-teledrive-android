package com.awd.teledrive.ui.screens.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

sealed class LoginUiState {
    object InitialSetup : LoginUiState()
    object EnteringCode : LoginUiState()
    object EnteringPassword : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    val authError: StateFlow<String?> = authRepository.authError
        .map { error ->
            if (error.isNullOrEmpty()) null else mapTechnicalError(error)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun mapTechnicalError(error: String): String {
        return when {
            error.contains("PHONE_NUMBER_INVALID", ignoreCase = true) -> context.getString(R.string.err_phone_invalid)
            error.contains("PHONE_CODE_INVALID", ignoreCase = true) -> context.getString(R.string.err_code_invalid)
            error.contains("PHONE_CODE_EXPIRED", ignoreCase = true) -> context.getString(R.string.err_code_expired)
            error.contains("PASSWORD_HASH_INVALID", ignoreCase = true) -> context.getString(R.string.err_password_invalid)
            error.contains("FLOOD_WAIT", ignoreCase = true) -> context.getString(R.string.err_flood_wait)
            error.contains("API_ID_INVALID", ignoreCase = true) || error.contains("API_HASH_INVALID", ignoreCase = true) -> context.getString(R.string.err_api_invalid)
            error.contains("Connection error", ignoreCase = true) -> context.getString(R.string.err_connection)
            else -> context.getString(R.string.err_unknown, error)
        }
    }

    val uiState: StateFlow<LoginUiState> = authRepository.authState
        .map { state ->
            when (state) {
                is TdApi.AuthorizationStateWaitTdlibParameters,
                is TdApi.AuthorizationStateWaitPhoneNumber -> LoginUiState.InitialSetup
                is TdApi.AuthorizationStateWaitCode -> LoginUiState.EnteringCode
                is TdApi.AuthorizationStateWaitPassword -> LoginUiState.EnteringPassword
                is TdApi.AuthorizationStateReady -> LoginUiState.Success
                null -> {
                    // Jika state belum ada, cek apakah API sudah siap. 
                    // Jika belum, jangan loading, langsung tampilkan setup.
                    if (!authRepository.isApiConfigured()) LoginUiState.InitialSetup
                    else LoginUiState.Loading
                }
                else -> LoginUiState.Loading 
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LoginUiState.Loading)

    private var pendingPhoneNumber: String? = null

    init {
        // Watch for state changes to auto-submit phone number after API success
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                if (state is TdApi.AuthorizationStateWaitPhoneNumber && pendingPhoneNumber != null) {
                    val phone = pendingPhoneNumber!!
                    pendingPhoneNumber = null
                    authRepository.setPhoneNumber(phone)
                }
                if (state is TdApi.AuthorizationStateWaitCode) {
                    _isProcessing.value = false
                }
            }
        }

        // Watch for errors to stop loading
        viewModelScope.launch {
            authRepository.authError.collect { error ->
                if (error != null) {
                    _isProcessing.value = false
                    pendingPhoneNumber = null
                }
            }
        }
    }

    fun onLoginSubmit(apiId: String, apiHash: String, phoneNumber: String) {
        if (apiId.isBlank() || apiHash.isBlank() || phoneNumber.isBlank()) {
            authRepository.setLocalError(context.getString(R.string.err_unknown, "Semua kolom wajib diisi"))
            return
        }
        
        val id = apiId.trim().toIntOrNull()
        if (id == null || id <= 0) {
            authRepository.setLocalError(context.getString(R.string.err_api_invalid))
            return
        }

        if (apiHash.trim().length < 10) {
            authRepository.setLocalError(context.getString(R.string.err_api_invalid))
            return
        }

        _isProcessing.value = true
        val phone = phoneNumber.trim()
        pendingPhoneNumber = phone
        
        val currentState = authRepository.authState.value
        if (currentState is TdApi.AuthorizationStateWaitPhoneNumber) {
            // If already in WaitPhoneNumber, submit phone directly
            pendingPhoneNumber = null
            authRepository.setPhoneNumber(phone)
        } else {
            // Otherwise, save API credentials and wait for state update
            authRepository.saveApiCredentials(id, apiHash.trim())
        }
    }

    fun onResetApi() {
        pendingPhoneNumber = null
        _isProcessing.value = false
        authRepository.clearApiCredentials()
    }

    fun onCodeSubmit(code: String) {
        authRepository.checkAuthenticationCode(code)
    }

    fun onPasswordSubmit(password: String) {
        authRepository.checkPassword(password)
    }
}
