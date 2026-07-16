package com.awd.teledrive.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder {
    NAME, DATE, SIZE
}

enum class FilterType {
    ALL, PHOTOS, VIDEOS, AUDIO, DOCUMENTS
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    val connectivityStatus = connectivityObserver.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityObserver.Status.Available)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder = _sortOrder.asStateFlow()

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType = _filterType.asStateFlow()

    private val _currentFolderId = MutableStateFlow<Long?>(null)
    val currentFolderId = _currentFolderId.asStateFlow()

    private val _currentFolderName = MutableStateFlow("Drive Saya")
    val currentFolderName = _currentFolderName.asStateFlow()

    private val _isGridView = MutableStateFlow(value = false)
    val isGridView = _isGridView.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading = _isInitialLoading.asStateFlow()

    val totalStorageUsed: StateFlow<Long> = driveRepository.getTotalStorageUsed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<DriveItem>> = combine(
        _currentFolderId,
        _searchQuery,
        _sortOrder,
        _filterType,
        driveRepository.getSavedMessagesChatIdFlow()
    ) { folderId, query, order, filter, _ ->
        driveRepository.getItems(folderId, query).map { items ->
            val filteredByType = when (filter) {
                FilterType.ALL -> items
                FilterType.PHOTOS -> items.filter { it is DriveItem.File && it.mimeType.startsWith("image/") }
                FilterType.VIDEOS -> items.filter { it is DriveItem.File && it.mimeType.startsWith("video/") }
                FilterType.AUDIO -> items.filter { it is DriveItem.File && it.mimeType.startsWith("audio/") }
                FilterType.DOCUMENTS -> items.filter { it is DriveItem.File && !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") && !it.mimeType.startsWith("audio/") }
            }

            // Fix the infinite reordering glitch:
            // 1. Keep folders always at the top
            // 2. Sort consistently within folders and files
            val (folders, files) = filteredByType.partition { it is DriveItem.Folder }
            
            val sortedFolders = when (order) {
                SortOrder.NAME -> folders.sortedBy { it.name }
                else -> folders.sortedByDescending { it.id } // Consistent sort for folders
            }

            val sortedFiles = when (order) {
                SortOrder.NAME -> files.sortedBy { it.name }
                SortOrder.DATE -> files // Already sorted by DATE in Repo
                SortOrder.SIZE -> files.sortedByDescending { (it as? DriveItem.File)?.size ?: 0L }
            }

            sortedFolders + sortedFiles
        }
    }.flatMapLatest { it }
    .distinctUntilChanged() // Crucial to stop the glitch
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        fetchItems()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setFilterType(type: FilterType) {
        _filterType.value = type
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun navigateToFolder(folderId: Long?, folderName: String) {
        _currentFolderId.value = folderId
        _currentFolderName.value = folderName
        fetchItems()
    }

    fun navigateBack() {
        if (_currentFolderId.value != null) {
            navigateToFolder(null, "My TeleDrive")
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            driveRepository.createFolder(name)
        }
    }

    fun fetchItems() {
        viewModelScope.launch {
            if (items.value.isEmpty()) _isInitialLoading.value = true
            else _isRefreshing.value = true
            
            driveRepository.fetchFiles(_currentFolderId.value)
            
            // Give a small delay for DB to sync and UI to feel smooth
            kotlinx.coroutines.delay(1000)
            _isInitialLoading.value = false
            _isRefreshing.value = false
            
            // Second fetch to ensure everything is caught if TDLib was still processing
            if (items.value.isEmpty()) {
                kotlinx.coroutines.delay(2000)
                driveRepository.fetchFiles(_currentFolderId.value)
            }
        }
    }

    fun uploadFile(filePath: String, fileName: String) {
        viewModelScope.launch {
            driveRepository.uploadFile(filePath, fileName, _currentFolderId.value)
        }
    }

    fun downloadFile(messageId: Long, chatId: Long, fileName: String) {
        viewModelScope.launch {
            driveRepository.downloadFile(messageId, chatId, fileName)
        }
    }

    fun downloadFolderContents(folderChatId: Long) {
        viewModelScope.launch {
            driveRepository.downloadFolderContents(folderChatId)
        }
    }

    fun deleteItems(itemsToDelete: List<DriveItem>) {
        viewModelScope.launch {
            val fromChatId = _currentFolderId.value ?: driveRepository.getSavedMessagesChatId()
            driveRepository.permanentlyDeleteItems(fromChatId, itemsToDelete)
        }
    }

    fun moveItems(ids: Set<Long>, targetChatId: Long) {
        val fromChatId = _currentFolderId.value ?: driveRepository.getSavedMessagesChatId()
        val destination = if (targetChatId == 0L) driveRepository.getSavedMessagesChatId() else targetChatId
        
        if (fromChatId != 0L && fromChatId != destination) {
            viewModelScope.launch {
                driveRepository.moveItems(fromChatId, destination, ids.toList())
            }
        }
    }

    fun moveFolderContentsAndDelete(folderChatId: Long, targetChatId: Long) {
        val destination = if (targetChatId == 0L) driveRepository.getSavedMessagesChatId() else targetChatId
        viewModelScope.launch {
            driveRepository.moveFolderContentsAndDelete(folderChatId, destination)
        }
    }

    fun toggleStarred(item: DriveItem) {
        viewModelScope.launch {
            driveRepository.toggleStarred(item)
        }
    }
}
