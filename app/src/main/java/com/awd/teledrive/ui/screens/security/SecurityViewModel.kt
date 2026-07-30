package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.MasterPasswordService
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val masterPasswordService: MasterPasswordService,
    private val secureSettings: SecureSettings,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    private val _isPasswordSet = MutableStateFlow(masterPasswordService.isPasswordSet())
    val isPasswordSet = _isPasswordSet.asStateFlow()

    private val _isSecurityEnabled = MutableStateFlow(secureSettings.isSecurityEnabled())
    val isSecurityEnabled = _isSecurityEnabled.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(secureSettings.getBoolean("biometric_enabled"))
    val isBiometricEnabled = _isBiometricEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    fun setSecurityEnabled(enabled: Boolean) {
        if (enabled && !_isPasswordSet.value) {
            // Requirement: If enabling and no password, trigger set password flow
            // This is a UI state trigger usually, but we update the preference
            // and the UI should react to !isPasswordSet
        }
        secureSettings.setSecurityEnabled(enabled)
        _isSecurityEnabled.value = enabled
    }

    fun setLocked(locked: Boolean) {
        if (_isSecurityEnabled.value && _isPasswordSet.value) {
            _isLocked.value = locked
            if (locked) {
                secureSessionManager.clearSession()
            }
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun unlockSecureMode(password: String): Boolean {
        return if (masterPasswordService.verifyPassword(password)) {
            secureSessionManager.setSessionPassword(password)
            // Ensure raw password is saved for future biometric use
            masterPasswordService.setPassword(password) 
            true
        } else false
    }

    fun lockSecureMode() {
        secureSessionManager.clearSession()
    }

    fun unlockWithBiometric(): Boolean {
        val storedPassword = masterPasswordService.getStoredPassword()
        return if (storedPassword != null) {
            secureSessionManager.setSessionPassword(storedPassword)
            true
        } else false
    }

    val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

    fun setPassword(password: String) {
        masterPasswordService.setPassword(password)
        _isPasswordSet.value = true
        setSecurityEnabled(true)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        secureSettings.saveBoolean("biometric_enabled", enabled)
        _isBiometricEnabled.value = enabled
    }

    fun verifyPassword(password: String): Boolean {
        val isValid = masterPasswordService.verifyPassword(password)
        if (!isValid) {
            _error.value = "Incorrect password"
        } else {
            _error.value = null
            _isLocked.value = false
            // Re-save to ensure biometric has access to the raw key
            masterPasswordService.setPassword(password)
        }
        return isValid
    }
}
