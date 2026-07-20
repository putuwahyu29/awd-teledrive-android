package com.awd.teledrive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupFeedback {
    object NoFolders : BackupFeedback()
    object Starting : BackupFeedback()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    private val workerManager: TeleDriveWorkerManager
) : ViewModel() {

    val isAutoBackupEnabled = settingsRepository.isAutoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _lastBackupTime = MutableStateFlow(secureSettings.getLong("last_backup_time", 0L))
    val lastBackupTime = _lastBackupTime.asStateFlow()

    private val _backupFolders = MutableStateFlow(secureSettings.getStringList("backup_folders"))
    val backupFolders = _backupFolders.asStateFlow()

    private val _feedback = MutableSharedFlow<BackupFeedback>()
    val feedback: SharedFlow<BackupFeedback> = _feedback.asSharedFlow()

    val isBackupWifiOnly = settingsRepository.isBackupWifiOnlyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val backupWorkInfo = workerManager.getBackupWorkInfo()

    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsRepository.setAutoBackupEnabled(enabled)
        workerManager.scheduleBackup(enabled)
    }

    fun setBackupWifiOnly(enabled: Boolean) {
        settingsRepository.setBackupWifiOnlyEnabled(enabled)
        // Reschedule to apply constraints
        if (isAutoBackupEnabled.value) {
            workerManager.scheduleBackup(true)
        }
    }

    fun toggleBackupFolder(path: String) {
        val current = _backupFolders.value.toMutableList()
        if (current.contains(path)) current.remove(path)
        else current.add(path)
        
        _backupFolders.value = current
        secureSettings.saveStringList("backup_folders", current)
    }

    fun addCustomBackupFolder(path: String) {
        if (path.isEmpty()) return
        val current = _backupFolders.value.toMutableList()
        if (!current.contains(path)) {
            current.add(path)
            _backupFolders.value = current
            secureSettings.saveStringList("backup_folders", current)
        }
    }

    fun triggerManualBackup() {
        if (_backupFolders.value.isEmpty()) {
            viewModelScope.launch {
                _feedback.emit(BackupFeedback.NoFolders)
            }
            return
        }
        viewModelScope.launch {
            _feedback.emit(BackupFeedback.Starting)
        }
        workerManager.triggerOneTimeBackup()
    }
}
