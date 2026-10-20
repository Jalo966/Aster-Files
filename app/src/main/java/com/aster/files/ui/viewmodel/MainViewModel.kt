package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aster.files.ui.sort.FileSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

data class FileTabState(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    val selectedPaths: Set<String> = emptySet(),
    val sort: FileSort = FileSort.DEFAULT
) {
    val title: String
        get() = File(path).name.ifBlank { "根目录" }
}

data class MainUiState(
    val tabs: List<FileTabState>,
    val activeTabId: String
) {
    val activeTab: FileTabState
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
}

/** 浏览器式文件标签页；每个标签保存独立路径、选中项和排序。 */
class MainViewModel(
    rootPath: String,
    defaultSort: FileSort = FileSort.DEFAULT
) : ViewModel() {

    private val firstTab = FileTabState(path = rootPath, sort = defaultSort)
    private val mutableState = MutableStateFlow(
        MainUiState(tabs = listOf(firstTab), activeTabId = firstTab.id)
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun addTab(path: String, sort: FileSort): FileTabState {
        val tab = FileTabState(path = path, sort = sort)
        mutableState.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
        return tab
    }

    fun activateTab(id: String) {
        mutableState.update {
            if (it.tabs.any { tab -> tab.id == id }) it.copy(activeTabId = id) else it
        }
    }

    fun updateTab(
        id: String,
        path: String,
        selectedPaths: Set<String>,
        sort: FileSort
    ) {
        mutableState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == id) {
                        tab.copy(path = path, selectedPaths = selectedPaths, sort = sort)
                    } else {
                        tab
                    }
                }
            )
        }
    }

    /** 关闭标签，返回关闭后应激活的标签；只剩一个标签时拒绝关闭。 */
    fun closeTab(id: String): FileTabState? {
        val current = mutableState.value
        if (current.tabs.size <= 1) return null
        val index = current.tabs.indexOfFirst { it.id == id }
        if (index < 0) return current.activeTab
        val remaining = current.tabs.filterNot { it.id == id }
        val active = if (current.activeTabId == id) {
            remaining[(index - 1).coerceAtLeast(0)]
        } else {
            current.activeTab
        }
        mutableState.value = MainUiState(remaining, active.id)
        return active
    }
}
