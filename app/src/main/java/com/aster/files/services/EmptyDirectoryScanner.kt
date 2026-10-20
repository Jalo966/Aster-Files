package com.aster.files.services

import java.io.File

/** 纯文件系统空目录检测；可在 JVM 单元测试中直接运行。 */
object EmptyDirectoryScanner {
    fun find(root: File): List<File> {
        val empties = mutableListOf<File>()
        fun containsFile(directory: File): Boolean {
            val children = directory.listFiles() ?: return true
            var hasFile = false
            for (child in children) {
                if (child.isDirectory && !isSymlink(child)) {
                    if (containsFile(child)) hasFile = true else empties.add(child)
                } else if (child.isFile) {
                    hasFile = true
                }
            }
            return hasFile
        }
        containsFile(root)
        return empties
            .filter { candidate ->
                empties.none { other ->
                    other != candidate && candidate.path.startsWith(other.path + File.separator)
                }
            }
            .sortedBy { it.path.lowercase() }
            .take(500)
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)
}
