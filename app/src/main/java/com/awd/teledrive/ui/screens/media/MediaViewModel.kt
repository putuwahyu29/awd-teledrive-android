package com.awd.teledrive.ui.screens.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    private val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<DriveItem.File>> = combine(
        driveRepository.getAllFiles(),
        isSecureModeActive
    ) { items, secureActive ->
        items.filter {
            (it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")) &&
            (secureActive || !it.isEncrypted)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<DriveItem.Folder>> = combine(
        driveRepository.getItems(null),
        isSecureModeActive
    ) { items, secureActive ->
        items.filterIsInstance<DriveItem.Folder>().filter { secureActive || !it.isSecure }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun downloadFile(file: DriveItem.File) {
        viewModelScope.launch {
            driveRepository.downloadFile(file.id, file.parentChatId, file.name)
        }
    }

    fun deleteItems(files: List<DriveItem.File>) {
        viewModelScope.launch {
            // Group by chatId for efficiency if possible, or just delete one by one group
            files.groupBy { it.parentChatId }.forEach { (chatId, items) ->
                driveRepository.permanentlyDeleteItems(chatId, items)
            }
        }
    }

    fun moveItems(fileIds: List<Long>, targetChatId: Long) {
        val destination = if (targetChatId == 0L) driveRepository.getSavedMessagesChatId() else targetChatId
        
        viewModelScope.launch {
            // Find all files to get their parentChatId
            val files = mediaItems.value.filter { it.id in fileIds }
            files.groupBy { it.parentChatId }.forEach { (fromChatId, items) ->
                if (fromChatId != destination) {
                    driveRepository.moveItems(fromChatId, destination, items.map { it.id })
                }
            }
        }
    }
    
    fun toggleStarred(file: DriveItem.File) {
        viewModelScope.launch {
            driveRepository.toggleStarred(file)
        }
    }
}
