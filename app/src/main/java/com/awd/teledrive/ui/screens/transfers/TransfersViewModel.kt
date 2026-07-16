package com.awd.teledrive.ui.screens.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.TransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transferRepository: TransferRepository
) : ViewModel() {
    val transfers: StateFlow<Map<String, TransferInfo>> = transferRepository.transfers

    fun cancelTransfer(uniqueId: String) {
        transferRepository.cancelTransfer(uniqueId)
    }

    fun clearCompleted() {
        transferRepository.clearCompleted()
    }
}
