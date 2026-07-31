package com.awd.teledrive.ui.screens.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.ShareRepository
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

@HiltViewModel
class StarredViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val shareRepository: ShareRepository,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    private val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

    val starredItems: StateFlow<List<DriveItem>> = combine(
        driveRepository.getStarredItems(),
        isSecureModeActive
    ) { items: List<DriveItem>, secureActive: Boolean ->
        items.filter { item ->
            when (item) {
                is DriveItem.File -> secureActive || !item.isEncrypted
                is DriveItem.Folder -> secureActive || !item.isSecure
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGridView = MutableStateFlow(false)
    val isGridView = _isGridView.asStateFlow()

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        viewModelScope.launch {
            driveRepository.downloadFile(messageId, chatId, fileName)
        }
    }

    fun downloadFolderContents(chatId: Long) {
        viewModelScope.launch {
            driveRepository.downloadFolderContents(chatId)
        }
    }

    fun getFolderInviteLink(chatId: Long, callback: (String?) -> Unit) {
        shareRepository.getFolderInviteLink(chatId, callback)
    }

    fun getFolderMembers(chatId: Long, callback: (List<TdApi.User>) -> Unit) {
        shareRepository.getChatMembers(chatId, callback)
    }

    fun shareFileToPhone(phoneNumber: String, messageId: Long, onResult: (Boolean, String?) -> Unit) {
        val item = starredItems.value.find { it.id == messageId } as? DriveItem.File
        if (item != null) {
            shareRepository.shareFileToPhone(phoneNumber, messageId, item.parentChatId, onResult)
        } else {
            onResult(false, "Item not found or not a file")
        }
    }
}
