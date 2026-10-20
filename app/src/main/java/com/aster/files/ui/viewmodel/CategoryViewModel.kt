package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.data.FileCategory
import com.aster.files.data.FileEntry
import com.aster.files.services.ArchiveService
import com.aster.files.services.CategoryScanResult
import com.aster.files.services.CategoryService
import com.aster.files.services.FileClassifier
import com.aster.files.services.FileService
import com.aster.files.services.TrashService
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.FileSortPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryUiState(
    val result: CategoryScanResult? = null,
    val selectedCategory: FileCategory? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val operationProgress: Float? = null,
    val operationLabel: String? = null,
    val sort: FileSort = FileSort.DEFAULT,
    val preview: FilePreview? = null,
    val message: String? = null,
    val error: String? = null
)

class CategoryViewModel(
    private val categoryService: CategoryService,
    private val fileService: FileService,
    private val trashService: TrashService,
    private val archiveService: ArchiveService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    rootPath: String = fileService.rootPath,
    private val sortPreferences: FileSortPreferences? = null
) : ViewModel() {

    val rootPath: String = rootPath

    private val mutableState = MutableStateFlow(
        CategoryUiState(sort = sortPreferences?.sort?.value ?: FileSort.DEFAULT)
    )
    val state: StateFlow<CategoryUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            sortPreferences?.sort?.collect { sort ->
                mutableState.update { it.copy(sort = sort) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val result = withContext(ioDispatcher) { categoryService.scan() }
                mutableState.update { it.copy(result = result, loading = false) }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "分类扫描失败")
                }
            }
        }
    }

    fun setSort(sort: FileSort) {
        mutableState.update { it.copy(sort = sort) }
        sortPreferences?.setSort(sort)
    }

    fun openCategory(category: FileCategory) {
        mutableState.update { it.copy(selectedCategory = category) }
    }

    fun closeCategory() {
        mutableState.update { it.copy(selectedCategory = null) }
    }

    fun openPreview(entry: FileEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val preview = when {
                    FileClassifier.isImage(entry.name) -> {
                        FilePreview.Image(
                            name = entry.name,
                            path = fileService.canonicalReadablePath(entry.path)
                        )
                    }
                    FileClassifier.isTextPreviewable(entry.name) -> {
                        val text = fileService.readTextPreview(entry.path)
                        if (text != null) {
                            FilePreview.Text(entry.name, entry.path, text)
                        } else {
                            FilePreview.Metadata(entry.name, entry.path, entry.sizeBytes)
                        }
                    }
                    else -> FilePreview.Metadata(entry.name, entry.path, entry.sizeBytes)
                }
                mutableState.update { it.copy(preview = preview) }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "无法预览文件") }
            }
        }
    }

    fun dismissPreview() {
        mutableState.update { it.copy(preview = null) }
    }

    fun rename(entry: FileEntry, newName: String) {
        runOperation("重命名") {
            fileService.rename(entry.path, newName)
        }
    }

    fun copy(entry: FileEntry, targetDirectory: String) {
        runOperation("复制") {
            fileService.copy(entry.path, targetDirectory)
        }
    }

    fun move(entry: FileEntry, targetDirectory: String) {
        runOperation("移动") {
            fileService.move(entry.path, targetDirectory)
        }
    }

    fun delete(entry: FileEntry) {
        runOperation("删除") {
            trashService.softDelete(entry.path)
        }
    }

    fun copyPaths(paths: List<String>, targetDirectory: String) {
        if (paths.isEmpty()) return
        runBatchOperation("复制") {
            fileService.copyBatch(paths, targetDirectory)
        }
    }

    fun movePaths(paths: List<String>, targetDirectory: String) {
        if (paths.isEmpty()) return
        runBatchOperation("移动") {
            fileService.moveBatch(paths, targetDirectory)
        }
    }

    fun deletePaths(paths: List<String>) {
        if (paths.isEmpty()) return
        runBatchOperation("删除") {
            trashService.softDeleteBatch(paths)
        }
    }

    fun unzip(zipPath: String, targetDirectory: String) {
        runOperation("解压") {
            archiveService.unzip(zipPath, targetDirectory)
        }
    }

    fun unzipAdvanced(
        zipPath: String,
        targetDirectory: String,
        password: String?,
        deleteSource: Boolean
    ) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    error = null,
                    operationProgress = 0f,
                    operationLabel = "正在解压…"
                )
            }
            try {
                val result = withContext(ioDispatcher) {
                    archiveService.unzipAdvanced(
                        zipPath = zipPath,
                        targetDirectory = targetDirectory,
                        password = password,
                        deleteSource = deleteSource,
                        onProgress = { progress ->
                            mutableState.update { state ->
                                state.copy(operationProgress = progress)
                            }
                        }
                    )
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.message,
                        error = if (result.success) null else result.message,
                        operationProgress = null,
                        operationLabel = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "高级解压失败",
                        operationProgress = null,
                        operationLabel = null
                    )
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

    private fun runBatchOperation(
        label: String,
        block: suspend () -> com.aster.files.services.BatchResult
    ) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) { block() }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.summary(),
                        error = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: (label + "失败"))
                }
            }
            refresh()
        }
    }

    private fun runOperation(label: String, block: suspend () -> com.aster.files.services.OperationResult) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) { block() }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.message,
                        error = if (result.success) null else result.message
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: (label + "失败"))
                }
            }
            refresh()
        }
    }
}
