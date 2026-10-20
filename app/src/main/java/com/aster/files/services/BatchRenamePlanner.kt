package com.aster.files.services

import com.aster.files.data.FileEntry
import java.io.File

enum class BatchRenameMode {
    PREFIX_SEQUENCE,
    FIND_REPLACE
}

data class BatchRenameOptions(
    val mode: BatchRenameMode,
    val prefix: String = "",
    val find: String = "",
    val replace: String = "",
    val startNumber: Int = 1,
    val padding: Int = 3
) {
    init {
        require(startNumber > 0) { "起始序号必须大于 0" }
        require(padding in 1..9) { "序号宽度必须在 1..9" }
    }
}

data class BatchRenameRequest(
    val sourcePath: String,
    val oldName: String,
    val parentPath: String,
    val newName: String
)

data class BatchRenamePlan(
    val requests: List<BatchRenameRequest>,
    val conflicts: List<String>
) {
    val canExecute: Boolean get() = conflicts.isEmpty() && requests.isNotEmpty()
}

/**
 * 纯 JVM 批量重命名规划：
 * - 仅文件，文件夹直接判冲突；
 * - 前缀模式保留各自扩展名，序号从 001 开始；
 * - 查找替换只处理主文件名，保留扩展名；
 * - 目标名重复、含路径分隔符、与现有文件冲突均整体拒绝。
 */
object BatchRenamePlanner {

    fun plan(
        entries: List<FileEntry>,
        options: BatchRenameOptions,
        existingPaths: Set<String> = emptySet()
    ): BatchRenamePlan {
        val conflicts = ArrayList<String>()
        val requests = ArrayList<BatchRenameRequest>()
        val targetKeys = HashSet<String>()
        var sequence = options.startNumber

        for (entry in entries) {
            if (entry.isDirectory) {
                conflicts.add("文件夹不支持批量重命名：" + entry.name)
                continue
            }
            val oldName = entry.name
            val dot = oldName.lastIndexOf('.')
            val hasExtension = dot > 0
            val base = if (hasExtension) oldName.substring(0, dot) else oldName
            val extension = if (hasExtension) oldName.substring(dot) else ""

            val newBase = when (options.mode) {
                BatchRenameMode.PREFIX_SEQUENCE -> {
                    val prefix = options.prefix.trim()
                    if (prefix.isEmpty()) {
                        conflicts.add("前缀不能为空")
                        continue
                    }
                    prefix + sequence.toString().padStart(options.padding, '0')
                }
                BatchRenameMode.FIND_REPLACE -> {
                    if (options.find.isEmpty()) {
                        conflicts.add("查找内容不能为空")
                        continue
                    }
                    base.replace(options.find, options.replace)
                }
            }
            sequence++
            val newName = newBase + extension
            if (newName.isBlank() || newName == "." || newName == ".." ||
                newName.contains('/') || newName.contains('\\')
            ) {
                conflicts.add("非法文件名：" + newName)
                continue
            }
            val parentPath = File(entry.path).parentFile?.absolutePath
                ?: entry.path.substringBeforeLast(File.separator, "")
            val targetPath = File(parentPath, newName).absolutePath
            if (newName == oldName && targetPath == entry.path) {
                continue
            }
            val key = targetPath.lowercase()
            if (!targetKeys.add(key)) {
                conflicts.add("目标文件名重复：" + newName)
                continue
            }
            if (targetPath in existingPaths && targetPath != entry.path) {
                conflicts.add("目标文件名已存在：" + newName)
                continue
            }
            requests.add(
                BatchRenameRequest(
                    sourcePath = entry.path,
                    oldName = oldName,
                    parentPath = parentPath,
                    newName = newName
                )
            )
        }
        if (requests.isEmpty() && conflicts.isEmpty()) {
            conflicts.add("没有文件需要重命名")
        }
        return BatchRenamePlan(requests, conflicts)
    }
}
