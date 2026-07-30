package com.awd.teledrive.data.secure

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSessionManager @Inject constructor() {
    private val _decryptedPassword = MutableStateFlow<String?>(null)
    val decryptedPassword = _decryptedPassword.asStateFlow()

    fun setSessionPassword(password: String) {
        _decryptedPassword.value = password
    }

    fun clearSession() {
        _decryptedPassword.value = null
    }

    fun isUnlocked() = _decryptedPassword.value != null
}
