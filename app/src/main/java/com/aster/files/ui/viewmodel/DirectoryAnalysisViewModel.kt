package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.services.DirectoryAnalysisResult
import com.aster.files.services.DirectoryAnalysisService
import com.aster.files.services.FileService
import com.aster.files.services.TrashService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DirectoryAnalysisUiState(
    val path: String = "",
    val result: DirectoryAnalysisResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class DirectoryAnalysisViewModel(
    private val service: DirectoryAnalysisService,
    private val fileService: FileService? = null,
    private val trashService: TrashService? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(DirectoryAnalysisUiState())
    val state: StateFlow<DirectoryAnalysisUiState> = mutableState.asStateFlow()

    fun analyze(path: String) {
        if (mutableState.value.loading && mutableState.value.path == path) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(path = path, loading = true, error = null, result = null)
            }
            try {
                val result = withContext(ioDispatcher) { service.analyze(path) }
                mutableState.update {
                    it.copy(result = result, loading = false)
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "目录分析失败")
                }
            }
        }
    }

    fun deletePaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val trash = trashService ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { trash.softDeleteBatch(paths) }
                mutableState.update { it.copy(message = "已移入回收站") }
                analyze(mutableState.value.path)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }

    fun movePaths(paths: List<String>, targetDirectory: String) {
        if (paths.isEmpty()) return
        val files = fileService ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { files.moveBatch(paths, targetDirectory) }
                mutableState.update { it.copy(message = "已移动") }
                analyze(mutableState.value.path)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "移动失败") }
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
