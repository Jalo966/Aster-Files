package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.data.FileEntry
import com.aster.files.services.ArchiveService
import com.aster.files.services.BatchRenameOptions
import com.aster.files.services.BatchRenamePlanner
import com.aster.files.services.FileClassifier
import com.aster.files.services.FileService
import com.aster.files.services.OperationResult
import com.aster.files.services.TrashService
import com.aster.files.services.ZipOptions
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.FileSortPreferences
import com.aster.files.ui.sort.FileSorter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

sealed interface FilePreview {
    data class Text(val name: String, val path: String, val content: String) : FilePreview
    data class Image(val name: String, val path: String) : FilePreview
    data class Metadata(val name: String, val path: String, val sizeBytes: Long) : FilePreview
}

data class FilesUiState(
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val showHidden: Boolean = false,
    val gridMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<FileEntry>? = null,
    val sort: FileSort = FileSort.DEFAULT,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val operationProgress: Float? = null,
    val operationLabel: String? = null,
    val preview: FilePreview? = null,
    val message: String? = null,
    val error: String? = null
) {
    val visibleEntries: List<FileEntry> get() = searchResults ?: entries
    val searching: Boolean get() = searchQuery.isNotBlank()
    val selectedCount: Int get() = selectedPaths.size
}

class FilesViewModel(
    private val fileService: FileService,
    private val trashService: TrashService,
    private val archiveService: ArchiveService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    rootPath: String = fileService.rootPath,
    private val sortPreferences: FileSortPreferences? = null
) : ViewModel() {

    val rootPath: String = rootPath

    private val mutableState = MutableStateFlow(
        FilesUiState(
            currentPath = rootPath,
            sort = sortPreferences?.sort?.value ?: FileSort.DEFAULT
        )
    )
    val state: StateFlow<FilesUiState> = mutableState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            sortPreferences?.sort?.collect { applySort(it) }
        }
        refresh()
    }

    fun refresh() {
        val snapshot = mutableState.value
        if (snapshot.searching) {
            performSearch(snapshot.searchQuery, snapshot.showHidden)
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val entries = fileService.listDirectory(snapshot.currentPath, snapshot.showHidden)
                val sorted = FileSorter.sort(entries, snapshot.sort)
                mutableState.update {
                    it.copy(entries = sorted, loading = false, error = null)
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "无法读取目录")
                }
            }
        }
    }

    fun openDirectory(path: String) {
        mutableState.update {
            it.copy(
                currentPath = path,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = null,
                error = null
            )
        }
        refresh()
    }

    fun navigateUp() {
        val current = mutableState.value.currentPath
        if (current == rootPath) return
        val parent = File(current).parentFile?.absolutePath ?: rootPath
        val safeParent = if (parent.startsWith(rootPath)) parent else rootPath
        openDirectory(safeParent)
    }

    fun setSort(sort: FileSort) {
        applySort(sort)
        sortPreferences?.setSort(sort)
    }

    private fun applySort(sort: FileSort) {
        mutableState.update { state ->
            state.copy(
                sort = sort,
                entries = FileSorter.sort(state.entries, sort),
                searchResults = state.searchResults?.let { FileSorter.sort(it, sort) }
            )
        }
    }

    fun setShowHidden(show: Boolean) {
        mutableState.update { it.copy(showHidden = show) }
        refresh()
    }

    fun toggleViewMode() {
        mutableState.update { it.copy(gridMode = !it.gridMode) }
    }

    fun setSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableState.update { it.copy(searchResults = null, loading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            performSearch(query, mutableState.value.showHidden)
        }
    }

    private fun performSearch(query: String, showHidden: Boolean) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val results = fileService.search(
                    directoryPath = mutableState.value.currentPath,
                    query = query,
                    showHidden = showHidden
                )
                mutableState.update {
                    it.copy(
                        searchResults = FileSorter.sort(results, it.sort),
                        loading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "搜索失败")
                }
            }
        }
    }

    fun setSelection(paths: Set<String>) {
        mutableState.update { it.copy(selectedPaths = paths) }
    }

    fun toggleSelection(path: String) {
        mutableState.update {
            val selected = if (path in it.selectedPaths) {
                it.selectedPaths - path
            } else {
                it.selectedPaths + path
            }
            it.copy(selectedPaths = selected)
        }
    }

    fun selectAll() {
        mutableState.update {
            it.copy(selectedPaths = it.visibleEntries.map { entry -> entry.path }.toSet())
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun openPreview(entry: FileEntry) {
        if (entry.isDirectory) {
            openDirectory(entry.path)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val name = entry.name
                val preview = when {
                    FileClassifier.isImage(name) -> {
                        val canonical = fileService.canonicalReadablePath(entry.path)
                        FilePreview.Image(name, canonical)
                    }
                    FileClassifier.isTextPreviewable(name) -> {
                        val text = fileService.readTextPreview(entry.path)
                        if (text != null) {
                            FilePreview.Text(name, entry.path, text)
                        } else {
                            FilePreview.Metadata(name, entry.path, entry.sizeBytes)
                        }
                    }
                    else -> FilePreview.Metadata(name, entry.path, entry.sizeBytes)
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

    fun createFolder(name: String) {
        runOperation("创建文件夹") {
            fileService.createDirectory(mutableState.value.currentPath, name)
        }
    }

    fun createFile(name: String) {
        runOperation("创建文件") {
            fileService.createFile(mutableState.value.currentPath, name)
        }
    }

    fun rename(path: String, newName: String) {
        runOperation("重命名") { fileService.rename(path, newName) }
    }

    fun copy(paths: List<String>, targetDirectory: String) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) {
                    fileService.copyBatch(paths, targetDirectory)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.summary(),
                        selectedPaths = emptySet()
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "复制失败")
                }
            }
            refresh()
        }
    }

    fun move(paths: List<String>, targetDirectory: String) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) {
                    fileService.moveBatch(paths, targetDirectory)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.summary(),
                        selectedPaths = emptySet()
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "移动失败")
                }
            }
            refresh()
        }
    }

    fun renameBatch(entries: List<FileEntry>, options: BatchRenameOptions) {
        if (entries.isEmpty()) return
        val plan = BatchRenamePlanner.plan(entries, options)
        if (!plan.canExecute) {
            mutableState.update {
                it.copy(error = plan.conflicts.joinToString("；"))
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) {
                    fileService.renameBatch(plan.requests)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = if (result.success) result.message else null,
                        error = if (result.success) null else result.message,
                        selectedPaths = if (result.success) emptySet() else it.selectedPaths
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "批量重命名失败")
                }
            }
            refresh()
        }
    }

    fun delete(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result = withContext(ioDispatcher) {
                    trashService.softDeleteBatch(paths)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.summary(),
                        selectedPaths = emptySet()
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "删除失败")
                }
            }
            refresh()
        }
    }

    fun zip(paths: List<String>, zipName: String) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result: OperationResult = withContext(ioDispatcher) {
                    archiveService.zip(paths, mutableState.value.currentPath, zipName)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.message,
                        error = if (result.success) null else result.message,
                        selectedPaths = emptySet()
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "压缩失败")
                }
            }
            refresh()
        }
    }

    fun zipAdvanced(
        paths: List<String>,
        zipName: String,
        options: ZipOptions
    ) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    error = null,
                    operationProgress = 0f,
                    operationLabel = "正在压缩…"
                )
            }
            try {
                val result = withContext(ioDispatcher) {
                    archiveService.zipAdvanced(
                        sources = paths,
                        targetDirectory = mutableState.value.currentPath,
                        zipName = zipName,
                        options = options,
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
                        selectedPaths = emptySet(),
                        operationProgress = null,
                        operationLabel = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "高级压缩失败",
                        operationProgress = null,
                        operationLabel = null
                    )
                }
            }
            refresh()
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

    fun unzip(zipPath: String, targetDirectory: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val result: OperationResult = withContext(ioDispatcher) {
                    archiveService.unzip(zipPath, targetDirectory)
                }
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = result.message,
                        error = if (result.success) null else result.message
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: "解压失败")
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

    private fun runOperation(label: String, block: suspend () -> OperationResult) {
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
                refresh()
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = e.message ?: (label + "失败"))
                }
            }
        }
    }
}
