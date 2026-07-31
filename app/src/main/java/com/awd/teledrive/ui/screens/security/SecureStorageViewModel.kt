package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.home.FilterType
import com.awd.teledrive.ui.screens.home.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder = _sortOrder.asStateFlow()

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType = _filterType.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val secureItems: StateFlow<List<DriveItem>> = combine(
        listOf(_currentFolderId, _currentVirtualFolderId, _searchQuery, _sortOrder, _filterType, driveRepository.getSavedMessagesChatIdFlow())
    ) { arr ->
        val folderId = arr[0] as? Long
        val virtualId = arr[1] as String
        val query = arr[2] as String
        val order = arr[3] as SortOrder
        val filter = arr[4] as FilterType
        // savedMessagesChatId is arr[5], we don't need it directly but observing it triggers refresh when ready

        driveRepository.getItems(folderId, virtualId, query).map { allItems ->
            val isInsideSecure = if (virtualId != "0") {
                driveRepository.isVirtualFolderSecure(virtualId)
            } else if (folderId != null) {
                driveRepository.isChatSecure(folderId)
            } else false

            val secureBase = if (isInsideSecure) {
                allItems
            } else {
                allItems.filter { item ->
                    (item is DriveItem.Folder && item.isSecure) || 
                    (item is DriveItem.File && item.isEncrypted)
                }
            }

            val filteredByType = when (filter) {
                FilterType.ALL -> secureBase
                FilterType.PHOTOS -> secureBase.filter { it is DriveItem.File && it.mimeType.startsWith("image/") }
                FilterType.VIDEOS -> secureBase.filter { it is DriveItem.File && it.mimeType.startsWith("video/") }
                FilterType.AUDIO -> secureBase.filter { it is DriveItem.File && it.mimeType.startsWith("audio/") }
                FilterType.DOCUMENTS -> secureBase.filter { it is DriveItem.File && !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") && !it.mimeType.startsWith("audio/") }
            }

            val (folders, files) = filteredByType.partition { it is DriveItem.Folder }
            
            val sortedFolders = when (order) {
                SortOrder.NAME -> folders.sortedBy { it.name }
                else -> folders.sortedByDescending { it.id }
            }

            val sortedFiles = when (order) {
                SortOrder.NAME -> files.sortedBy { it.name }
                SortOrder.DATE -> files 
                SortOrder.SIZE -> files.sortedByDescending { (it as? DriveItem.File)?.size ?: 0L }
            }

            sortedFolders + sortedFiles
        }
    }.flatMapLatest { it }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setFilterType(type: FilterType) {
        _filterType.value = type
    }

    fun navigateToFolder(folderId: Long?, virtualId: String?, folderName: String?) {
        _currentFolderId.value = folderId
        _currentVirtualFolderId.value = virtualId ?: "0"
        _currentFolderName.value = folderName
        fetchItems()
    }

    fun fetchItems() {
        viewModelScope.launch {
            _isRefreshing.value = true
            driveRepository.fetchFiles(_currentFolderId.value)
            
            if (_currentFolderId.value == null) {
                // Root secure storage, wait for discovery
                kotlinx.coroutines.delay(2000)
            } else {
                kotlinx.coroutines.delay(1000)
            }
            _isRefreshing.value = false
        }
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
