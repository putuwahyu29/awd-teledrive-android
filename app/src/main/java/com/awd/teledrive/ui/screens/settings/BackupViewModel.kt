package com.awd.teledrive.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val workerManager: TeleDriveWorkerManager
) : ViewModel() {

    private val _isAutoBackupEnabled = MutableStateFlow(secureSettings.getBoolean("auto_backup_enabled", false))
    val isAutoBackupEnabled = _isAutoBackupEnabled.asStateFlow()

    private val _lastBackupTime = MutableStateFlow(secureSettings.getLong("last_backup_time", 0L))
    val lastBackupTime = _lastBackupTime.asStateFlow()

    private val _backupFolders = MutableStateFlow(secureSettings.getStringList("backup_folders"))
    val backupFolders = _backupFolders.asStateFlow()

    val backupWorkInfo = workerManager.getBackupWorkInfo()

    fun setAutoBackupEnabled(enabled: Boolean) {
        secureSettings.saveBoolean("auto_backup_enabled", enabled)
        _isAutoBackupEnabled.value = enabled
        workerManager.scheduleBackup(enabled)
    }

    fun toggleBackupFolder(path: String) {
        val current = _backupFolders.value.toMutableList()
        if (current.contains(path)) current.remove(path)
        else current.add(path)
        
        _backupFolders.value = current
        secureSettings.saveStringList("backup_folders", current)
    }

    fun triggerManualBackup() {
        workerManager.triggerOneTimeBackup()
    }
}
