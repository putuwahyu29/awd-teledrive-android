package com.awd.teledrive.ui.screens.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    transferRepository: TransferRepository,
    savedStateHandle: SavedStateHandle,
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
