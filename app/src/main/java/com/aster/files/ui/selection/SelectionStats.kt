package com.aster.files.ui.selection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

data class SelectionStats(
    val folderCount: Int,
    val fileCount: Int,
    val totalBytes: Long
)

/**
 * 递归统计选中项：
 * - 选中的文件夹计入 folderCount；
 * - 文件夹内所有普通文件计入 fileCount 与大小；
 * - 跳过符号链接，避免循环与越界读取；
 * - 父目录与子文件同时被选中时按 visited 去重。
 */
object SelectionStatsCalculator {

    suspend fun calculate(
        paths: Collection<String>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): SelectionStats = withContext(ioDispatcher) {
        calculateBlocking(paths)
    }

    fun calculateBlocking(paths: Collection<String>): SelectionStats {
        val visited = HashSet<String>()
        var folders = 0
        var files = 0
        var bytes = 0L

        fun visit(file: File) {
            val canonical = try {
                file.canonicalPath
            } catch (_: Exception) {
                file.absolutePath
            }
            if (!visited.add(canonical)) return
            if (isSymlink(file)) return
            if (file.isDirectory) {
                folders++
                val children = file.listFiles() ?: return
                for (child in children) visit(child)
            } else {
                files++
                bytes += file.length().coerceAtLeast(0L)
            }
        }

        paths.map { it.trim() }.filter { it.isNotEmpty() }.forEach { path ->
            visit(File(path))
        }
        return SelectionStats(folders, files, bytes)
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        false
    }
}
