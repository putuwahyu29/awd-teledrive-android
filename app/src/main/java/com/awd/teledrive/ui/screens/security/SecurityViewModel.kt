package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.MasterPasswordService
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val masterPasswordService: MasterPasswordService,
    private val secureSettings: SecureSettings
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
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

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
        }
        return isValid
    }
}
