package com.awd.teledrive.ui.screens.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val driveRepository: DriveRepository
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<DriveItem.File>> = driveRepository.getAllFiles()
        .map { items ->
            items.filter {
                it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") 
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<DriveItem.Folder>> = driveRepository.getItems(null)
        .map { list -> list.filterIsInstance<DriveItem.Folder>() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
