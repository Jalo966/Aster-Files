package com.aster.files.diagnostics

import java.io.File

/** cacheDir 的轻量统计与清理；不会删除 cacheDir 本身。 */
object CacheManager {
    fun sizeBytes(directory: File): Long {
        if (!directory.exists()) return 0L
        return directory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun clear(directory: File): Long {
        if (!directory.exists()) return 0L
        val before = sizeBytes(directory)
        directory.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
        return (before - sizeBytes(directory)).coerceAtLeast(0L)
    }
}
