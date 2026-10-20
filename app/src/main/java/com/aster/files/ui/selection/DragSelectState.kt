package com.aster.files.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** 长按后滑动多选：按 item 根坐标命中，支持反向取消。 */
class DragSelectState {
    val bounds = mutableMapOf<String, Rect>()
    private var active = false
    private var selectMode = true
    private val visited = mutableSetOf<String>()

    fun register(path: String, rect: Rect) {
        bounds[path] = rect
    }

    fun begin(path: String, wasSelected: Boolean, selection: Set<String>, onChange: (Set<String>) -> Unit) {
        active = true
        selectMode = !wasSelected
        visited.clear()
        visited.add(path)
        onChange(if (selectMode) selection + path else selection - path)
    }

    fun drag(positionInRoot: Offset, selection: Set<String>, onChange: (Set<String>) -> Unit) {
        if (!active) return
        val path = bounds.entries.firstOrNull { it.value.contains(positionInRoot) }?.key ?: return
        if (!visited.add(path)) return
        onChange(if (selectMode) selection + path else selection - path)
    }

    fun end() {
        active = false
        visited.clear()
    }
}
