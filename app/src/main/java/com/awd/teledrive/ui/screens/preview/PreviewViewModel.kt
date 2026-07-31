package com.awd.teledrive.ui.screens.preview

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val encryptionManager: EncryptionManager,
    private val secureSessionManager: SecureSessionManager,
    transferRepository: TransferRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: 0L
    private val initialFileId: Long = savedStateHandle.get<Long>("fileId") ?: 0L
    private val isMediaOnly: Boolean = savedStateHandle.get<Boolean>("isMediaOnly") ?: false

    val items: StateFlow<List<DriveItem.File>> = (if (isMediaOnly) {
        driveRepository.getAllFiles().map { list ->
            list.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }
        }
    } else {
        driveRepository.getItems(if (chatId != 0L) chatId else null)
            .map { list -> list.filterIsInstance<DriveItem.File>() }
    }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<DriveItem.Folder>> = driveRepository.getItems(null)
        .map { list -> list.filterIsInstance<DriveItem.Folder>() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tracks download progress of the files in the list
    val transfers = transferRepository.transfers

    private val _decryptedPaths = MutableStateFlow<Map<Long, String>>(emptyMap())
    val decryptedPaths = _decryptedPaths.asStateFlow()

    private val _isDecrypting = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isDecrypting = _isDecrypting.asStateFlow()

    init {
        // Observe the items flow. When an encrypted file's localPath becomes non-null, decrypt it.
        viewModelScope.launch {
            items.collect { fileList ->
                fileList.forEach { file ->
                    if (file.isEncrypted && file.localPath != null && !_decryptedPaths.value.containsKey(file.id)) {
                        decryptForPreview(file)
                    }
                }
            }
        }
    }

    fun getInitialIndex(): Int {
        val index = items.value.indexOfFirst { it.id == initialFileId }
        return if (index >= 0) index else 0
    }

    fun downloadForPreview(file: DriveItem.File) {
        if (file.localPath == null) {
            driveRepository.downloadForPreview(file.id, file.parentChatId, file.name)
        }
    }

    fun saveToDevice(file: DriveItem.File) {
        driveRepository.saveToPublicStorage(file)
    }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }

    fun autoDownloadForPreview(file: DriveItem.File) {
        if (file.localPath == null) {
            driveRepository.downloadForPreview(file.id, file.parentChatId, file.name)
        } else if (file.isEncrypted && !_decryptedPaths.value.containsKey(file.id)) {
            decryptForPreview(file)
        }
    }

    private fun decryptForPreview(file: DriveItem.File) {
        val password = secureSessionManager.decryptedPassword.value ?: return
        val localPath = file.localPath ?: return
        if (_isDecrypting.value[file.id] == true) return

        viewModelScope.launch {
            _isDecrypting.value = _isDecrypting.value + (file.id to true)
            withContext(Dispatchers.IO) {
                try {
                    val encryptedFile = File(localPath)
                    val decryptedFile = File(context.cacheDir, "prev_dec_${file.id}_${file.name}")
                    encryptionManager.decryptFile(encryptedFile, decryptedFile, password)
                    _decryptedPaths.value = _decryptedPaths.value + (file.id to decryptedFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isDecrypting.value = _isDecrypting.value - file.id
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Cleanup decrypted preview files
        _decryptedPaths.value.values.forEach { path ->
            try { File(path).delete() } catch (e: Exception) {}
        }
    }

    fun deleteItem(file: DriveItem.File) {
        viewModelScope.launch {
            driveRepository.permanentlyDeleteItems(file.parentChatId, listOf(file))
        }
    }

    fun moveItem(file: DriveItem.File, targetChatId: Long) {
        viewModelScope.launch {
            val destination = if (targetChatId == 0L) driveRepository.getSavedMessagesChatId() else targetChatId
            driveRepository.moveItems(file.parentChatId, destination, listOf(file.id))
        }
    }
}
