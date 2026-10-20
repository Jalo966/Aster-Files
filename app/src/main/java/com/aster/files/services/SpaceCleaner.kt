package com.aster.files.services

import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import com.aster.files.security.FileGuard
import java.io.File

enum class CleanKind(val label: String) {
    EMPTY_DIRECTORY("空文件夹"),
    EMPTY_FILE("空文件"),
    CACHE_RESIDUE("缓存残留"),
    SIMILAR_IMAGE("相似图片")
}

@Immutable
data class CleanCandidate(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val kind: CleanKind,
    val reason: String
)

/** 原生空间清理扫描：只读扫描，删除由 TrashService 软删除完成。 */
class SpaceCleaner(private val guard: FileGuard) {

    suspend fun scan(kind: CleanKind, root: String): List<CleanCandidate> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val rootFile = File(root)
        when (kind) {
            CleanKind.EMPTY_DIRECTORY -> EmptyDirectoryScanner.find(rootFile).map {
                CleanCandidate(it.absolutePath, it.name, 0, kind, "目录内无任何文件")
            }
            CleanKind.EMPTY_FILE -> emptyFiles(rootFile).map {
                CleanCandidate(it.absolutePath, it.name, 0, kind, "0 字节文件")
            }
            CleanKind.CACHE_RESIDUE -> cacheResidues(rootFile)
            CleanKind.SIMILAR_IMAGE -> similarImages(rootFile)
        }
    }

    private fun emptyDirectories(root: File): List<File> {
        val empties = mutableListOf<File>()

        fun containsFile(directory: File): Boolean {
            val children = directory.listFiles() ?: return true
            var hasFile = false
            for (child in children) {
                if (child.isDirectory && !child.isSymlink()) {
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

    private fun emptyFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(directory: File, depth: Int) {
            if (depth > 12 || result.size >= 500) return
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (child.isSymlink()) continue
                if (child.isDirectory) walk(child, depth + 1)
                else if (child.isFile && child.length() == 0L) result.add(child)
                if (result.size >= 500) return
            }
        }
        walk(root, 0)
        return result
    }

    private fun cacheResidues(root: File): List<CleanCandidate> {
        val dataRoot = File(root, "Android/data")
        if (!dataRoot.isDirectory) return emptyList()
        val result = mutableListOf<CleanCandidate>()
        dataRoot.listFiles()?.forEach { packageDir ->
            if (packageDir.isDirectory && !packageDir.isSymlink()) {
                listOf("cache", "logs", "log", "tmp").forEach { name ->
                    val directory = File(packageDir, name)
                    if (directory.isDirectory && accessible(directory)) {
                        val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        if (size > 0) {
                            result.add(
                                CleanCandidate(
                                    directory.absolutePath,
                                    packageDir.name + "/" + name,
                                    size,
                                    CleanKind.CACHE_RESIDUE,
                                    "应用缓存/日志目录"
                                )
                            )
                        }
                    }
                }
            }
        }
        return result.take(200)
    }

    private fun similarImages(root: File): List<CleanCandidate> {
        val images = mutableListOf<File>()
        fun walk(directory: File, depth: Int) {
            if (depth > 8 || images.size >= 2000) return
            directory.listFiles()?.forEach { child ->
                if (child.isSymlink()) return@forEach
                if (child.isDirectory) walk(child, depth + 1)
                else if (isImage(child.name)) images.add(child)
            }
        }
        walk(root, 0)
        val infos = images.map { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            SimilarImageInfo(
                path = file.absolutePath,
                name = file.name,
                sizeBytes = file.length(),
                width = options.outWidth,
                height = options.outHeight,
                modifiedAt = file.lastModified()
            )
        }
        return SimilarImageDetector.findDuplicates(infos).map { duplicate ->
            CleanCandidate(
                duplicate.path,
                duplicate.name,
                duplicate.sizeBytes,
                CleanKind.SIMILAR_IMAGE,
                "文件名相似且大小/分辨率一致"
            )
        }.take(500)
    }

    private fun normalizeImageName(name: String): String {
        return name.substringBeforeLast('.').lowercase()
            .replace(Regex("\\s*\\(\\d+\\)$"), "")
            .replace(Regex("[_\\- ](copy|副本|converted)$"), "")
            .trim()
    }

    private fun isImage(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase() in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "apng"
        )
    }

    private fun accessible(file: File): Boolean {
        return runCatching { guard.guardForRead(file.absolutePath); true }.getOrDefault(false)
    }

    private fun File.isSymlink(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(toPath())
    }.getOrDefault(false)
}
