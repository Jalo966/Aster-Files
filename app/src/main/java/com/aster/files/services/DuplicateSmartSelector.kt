package com.aster.files.services

import com.aster.files.data.FileEntry

enum class DuplicateSelectionStrategy(val label: String) {
    SMART_KEEP_SHORTEST_PATH_NEWEST("智能选择（保留最短路径最新）"),
    KEEP_LONGEST_NAME("保留名字最长"),
    KEEP_SHORTEST_NAME("保留名字最短"),
    KEEP_NEWEST("保留最新"),
    KEEP_OLDEST("保留最旧"),
    KEEP_LONGEST_PATH("保留路径最长"),
    KEEP_SHORTEST_PATH("保留路径最短")
}

/**
 * 每个重复组只保留一个文件，返回应被删除/清理的其余文件路径。
 * 所有策略均带稳定的路径兜底，避免同一输入产生不确定结果。
 */
object DuplicateSmartSelector {

    fun selectForDeletion(
        groups: List<DuplicateGroup>,
        strategy: DuplicateSelectionStrategy
    ): Set<String> {
        val result = LinkedHashSet<String>()
        for (group in groups) {
            if (group.files.size < 2) continue
            val sorted = group.files.sortedWith(comparator(strategy))
            val keep = sorted.first()
            sorted.drop(1).forEach { result.add(it.path) }
            check(keep.path !in result)
        }
        return result
    }

    private fun comparator(strategy: DuplicateSelectionStrategy): Comparator<FileEntry> =
        when (strategy) {
            DuplicateSelectionStrategy.SMART_KEEP_SHORTEST_PATH_NEWEST ->
                compareBy<FileEntry> { it.path.length }
                    .thenByDescending { it.modifiedAt }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_LONGEST_NAME ->
                compareByDescending<FileEntry> { it.name.length }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_SHORTEST_NAME ->
                compareBy<FileEntry> { it.name.length }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_NEWEST ->
                compareByDescending<FileEntry> { it.modifiedAt }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_OLDEST ->
                compareBy<FileEntry> { it.modifiedAt }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_LONGEST_PATH ->
                compareByDescending<FileEntry> { it.path.length }
                    .thenBy { it.path }
            DuplicateSelectionStrategy.KEEP_SHORTEST_PATH ->
                compareBy<FileEntry> { it.path.length }
                    .thenBy { it.path }
        }
}
