package com.aster.files.ui.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.services.AnimatedImageInfo
import com.aster.files.services.FrameExtractionService
import com.aster.files.services.FrameImageFormat
import com.aster.files.services.FrameSaveResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FrameExtractUiState(
    val loading: Boolean = true,
    val info: AnimatedImageInfo? = null,
    val selected: Set<Int> = emptySet(),
    val thumbnails: Map<Int, ImageBitmap> = emptyMap(),
    val loadingThumbnails: Set<Int> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
    val saveMessage: String? = null,
    val savedFolder: String? = null
)

class FrameExtractViewModel(
    private val service: FrameExtractionService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(FrameExtractUiState())
    val state: StateFlow<FrameExtractUiState> = _state.asStateFlow()

    private var loadedPath: String? = null
    private var currentPath: String? = null
    private val thumbnailQueue = Channel<Int>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (index in thumbnailQueue) {
                val current = _state.value
                val info = current.info ?: continue
                val path = currentPath ?: continue
                if (current.thumbnails.containsKey(index)) {
                    _state.update { it.copy(loadingThumbnails = it.loadingThumbnails - index) }
                    continue
                }
                val bitmap = runCatching {
                    withContext(ioDispatcher) {
                        service.loadFrame(path, info.type, index, THUMBNAIL_MAX)
                    }
                }.getOrNull()
                _state.update { state ->
                    state.copy(
                        loadingThumbnails = state.loadingThumbnails - index,
                        thumbnails = if (bitmap != null) {
                            state.thumbnails + (index to bitmap.asImageBitmap())
                        } else {
                            state.thumbnails
                        }
                    )
                }
            }
        }
    }

    fun load(path: String) {
        currentPath = path
        if (loadedPath == path && _state.value.info != null) return
        loadedPath = path
        _state.value = FrameExtractUiState(loading = true)
        viewModelScope.launch {
            val info = runCatching { withContext(ioDispatcher) { service.inspect(path) } }
                .getOrNull()
            _state.update {
                if (info == null) {
                    it.copy(loading = false, error = "无法读取动图帧；仅支持 GIF/APNG")
                } else {
                    it.copy(loading = false, info = info)
                }
            }
        }
    }

    fun ensureThumbnail(index: Int) {
        val current = _state.value
        if (current.thumbnails.containsKey(index) || index in current.loadingThumbnails) return
        if (current.info == null) return
        _state.update { it.copy(loadingThumbnails = it.loadingThumbnails + index) }
        thumbnailQueue.trySend(index)
    }

    fun toggle(index: Int) {
        _state.update { state ->
            val selected = if (index in state.selected) {
                state.selected - index
            } else {
                state.selected + index
            }
            state.copy(selected = selected)
        }
    }

    fun selectAll() {
        val count = _state.value.info?.frameCount ?: return
        _state.update { it.copy(selected = (0 until count).toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun save(path: String, format: FrameImageFormat) {
        val current = _state.value
        if (current.selected.isEmpty() || current.saving) return
        saveIndices(path, format, current.selected)
    }

    /** 导出全部帧：一次只解码/写出一帧，避免大图整组帧同时进内存。 */
    fun saveAll(path: String, format: FrameImageFormat) {
        val current = _state.value
        val info = current.info ?: return
        if (current.saving) return
        saveIndices(path, format, (0 until info.frameCount).toSet())
    }

    private fun saveIndices(path: String, format: FrameImageFormat, indices: Set<Int>) {
        val info = _state.value.info ?: return
        if (indices.isEmpty() || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null, saveMessage = null) }
        viewModelScope.launch {
            val result: FrameSaveResult = runCatching {
                withContext(ioDispatcher) {
                    service.saveFrames(path, info.type, indices, format)
                }
            }.getOrElse {
                FrameSaveResult(false, it.message ?: "保存失败")
            }
            _state.update {
                it.copy(
                    saving = false,
                    selected = if (result.success) emptySet() else it.selected,
                    error = if (result.success) null else result.message,
                    saveMessage = if (result.success) result.message else null,
                    savedFolder = result.folderName
                )
            }
        }
    }

    fun consumeSaveMessage() {
        _state.update { it.copy(saveMessage = null) }
    }

    companion object {
        const val THUMBNAIL_MAX = 180
    }
}
