package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.data.TrashEntry
import com.aster.files.services.TrashService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrashUiState(
    val entries: List<TrashEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class TrashViewModel(
    private val trashService: TrashService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val entries = withContext(ioDispatcher) { trashService.list() }
                mutableState.update { it.copy(entries = entries, loading = false) }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "无法读取回收站")
                }
            }
        }
    }

    fun restore(trashId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val result = withContext(ioDispatcher) { trashService.restore(trashId) }
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = result.message,
                        error = if (result.success) null else result.message
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "恢复失败")
                }
            }
            refresh()
        }
    }

    fun deletePermanently(trashId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val result = withContext(ioDispatcher) {
                    trashService.deletePermanently(trashId)
                }
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = result.message,
                        error = if (result.success) null else result.message
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "永久删除失败")
                }
            }
            refresh()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val batch = withContext(ioDispatcher) { trashService.emptyTrash() }
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = batch.summary(),
                        error = if (batch.results.any { result -> !result.success }) {
                            "部分项目清理失败"
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "清空回收站失败")
                }
            }
            refresh()
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
