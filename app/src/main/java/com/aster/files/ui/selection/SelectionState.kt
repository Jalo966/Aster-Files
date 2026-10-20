package com.aster.files.ui.selection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 文件页、分类页、分析页共用的多选状态。 */
@Stable
class SelectionState {
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    val active: Boolean get() = selectedPaths.isNotEmpty()
    val count: Int get() = selectedPaths.size

    fun toggle(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun replace(paths: Collection<String>) {
        selectedPaths = paths.toSet()
    }

    fun add(paths: Collection<String>) {
        selectedPaths = selectedPaths + paths
    }

    fun clear() {
        selectedPaths = emptySet()
    }
}
