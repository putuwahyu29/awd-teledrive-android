package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecureStorageViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<Long?>(null)
    val currentFolderId = _currentFolderId.asStateFlow()

    private val _currentVirtualFolderId = MutableStateFlow("0")
    val currentVirtualFolderId = _currentVirtualFolderId.asStateFlow()

    private val _currentFolderName = MutableStateFlow<String?>(null)
    val currentFolderName = _currentFolderName.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val secureItems: StateFlow<List<DriveItem>> = combine(
        _currentFolderId, _currentVirtualFolderId
    ) { folderId, virtualId ->
        driveRepository.getItems(folderId, virtualId).map { items ->
            // In the dedicated screen, we show items that are secure OR 
            // if we are inside a secure folder, we show everything inside it.
            val isInsideSecure = if (virtualId != "0") {
                driveRepository.isVirtualFolderSecure(virtualId)
            } else if (folderId != null) {
                driveRepository.isChatSecure(folderId)
            } else false

            if (isInsideSecure) {
                items
            } else {
                items.filter { item ->
                    (item is DriveItem.Folder && item.isSecure) || 
                    (item is DriveItem.File && item.isEncrypted)
                }
            }
        }
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun navigateToFolder(folderId: Long?, virtualId: String?, folderName: String?) {
        _currentFolderId.value = folderId
        _currentVirtualFolderId.value = virtualId ?: "0"
        _currentFolderName.value = folderName
        fetchItems()
    }

    fun fetchItems() {
        driveRepository.fetchFiles(_currentFolderId.value)
    }

    fun navigateBack() {
        if (_currentVirtualFolderId.value != "0") {
            val parentId = driveRepository.getParentVirtualId(_currentVirtualFolderId.value)
            navigateToFolder(_currentFolderId.value, parentId, if (parentId == "0") null else "...") 
        } else if (_currentFolderId.value != null) {
            navigateToFolder(null, "0", null)
        }
    }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }

    fun deleteItems(items: List<DriveItem>) {
        viewModelScope.launch {
            val fromChatId = _currentFolderId.value ?: driveRepository.getSavedMessagesChatId()
            driveRepository.permanentlyDeleteItems(fromChatId, items)
        }
    }

    fun renameItem(item: DriveItem, newName: String) {
        viewModelScope.launch {
            driveRepository.renameItem(item, newName)
        }
    }

    fun createFolder(name: String, isVirtual: Boolean = true, isSecure: Boolean = false) {
        viewModelScope.launch {
            val parentId = if (_currentVirtualFolderId.value != "0") {
                _currentVirtualFolderId.value
            } else if (_currentFolderId.value != null) {
                _currentFolderId.value.toString()
            } else {
                "0"
            }

            // Inherit secure state from parent if creating virtual folder
            val isParentSecure = if (parentId == "0") isSecure else {
                if (parentId.startsWith("vf_")) {
                    driveRepository.isVirtualFolderSecure(parentId)
                } else {
                    driveRepository.isChatSecure(parentId.toLongOrNull() ?: 0L)
                }
            }

            if (isSecure && parentId == "0") {
                driveRepository.createSecureFolder(name)
            } else if (isVirtual) {
                driveRepository.createVirtualFolder(name, parentId, isSecure = isParentSecure)
            } else {
                driveRepository.createFolder(name)
            }
        }
    }

    fun uploadFile(filePath: String, fileName: String) {
        viewModelScope.launch {
            driveRepository.uploadFile(filePath, fileName, _currentFolderId.value, _currentVirtualFolderId.value)
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        viewModelScope.launch {
            driveRepository.downloadFile(messageId, chatId, fileName)
        }
    }

    init {
        fetchItems()
    }
}
