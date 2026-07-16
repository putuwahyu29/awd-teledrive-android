package com.awd.teledrive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
) : ViewModel() {
    private val _isDarkMode = MutableStateFlow(
        if (secureSettings.getString("theme_mode_key") == null) null 
        else secureSettings.getBoolean("theme_mode")
    )
    val isDarkMode = _isDarkMode.asStateFlow()

    private val _themeColor = MutableStateFlow(
        Color(secureSettings.getInt("theme_color", 0xFF24A1DE.toInt()))
    )
    val themeColor = _themeColor.asStateFlow()

    fun setDarkMode(enabled: Boolean?) {
        _isDarkMode.value = enabled
        if (enabled == null) {
            secureSettings.saveString("theme_mode_key", "system")
        } else {
            secureSettings.saveString("theme_mode_key", "set")
            secureSettings.saveBoolean("theme_mode", enabled)
        }
    }

    fun setThemeColor(color: Color) {
        _themeColor.value = color
        secureSettings.saveInt("theme_color", color.toArgb())
    }
}
