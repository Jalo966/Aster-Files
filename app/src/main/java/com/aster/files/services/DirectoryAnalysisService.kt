package com.aster.files.services

import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.data.MediaType
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.security.GuardedPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.PriorityQueue

data class DuplicateGroup(
    val sizeBytes: Long,
    val hashPrefix: String,
    val files: List<FileEntry>
)

data class FolderUsage(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val fileCount: Int
)

data class DirectoryAnalysisResult(
    val rootPath: String,
    val largeFiles: List<FileEntry>,
    val zeroByteFiles: List<FileEntry>,
    val emptyDirectories: List<FileEntry>,
    val duplicateGroups: List<DuplicateGroup>,
    val folderUsage: List<FolderUsage>,
    val directFiles: List<FileEntry>,
    val recentFiles: List<FileEntry>,
    val scannedItems: Int,
    val truncated: Boolean
)

/**
 * 当前目录的大文件、冗余项和疑似重复项分析。
 *
 * 重复项先按文件大小分组，只对同大小组读取前 4KB 计算 SHA-256；不读取完整文件。
 * 所有路径经过 FileGuard，不跟随符号链接，不访问内部目录。
 */
class DirectoryAnalysisService(
    private val guard: FileGuard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxLargeFiles: Int = DEFAULT_MAX_LARGE_FILES,
    private val maxDuplicateGroups: Int = DEFAULT_MAX_DUPLICATE_GROUPS,
    private val duplicatePrefixBytes: Int = DEFAULT_DUPLICATE_PREFIX_BYTES,
    private val minLargeFileBytes: Long = MIN_LARGE_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    init {
        require(maxItems > 0)
        require(maxLargeFiles > 0)
        require(maxDuplicateGroups > 0)
        require(duplicatePrefixBytes > 0)
        require(minLargeFileBytes >= 0)
    }

    suspend fun analyze(rootPath: String = guard.rootPath): DirectoryAnalysisResult =
        withContext(ioDispatcher) {
            val root = guard.guardForRead(rootPath)
            if (!root.isDirectory) {
                throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, root.canonicalPath)
            }

            val large = PriorityQueue<FileEntry>(
                compareBy<FileEntry> { it.sizeBytes }.thenByDescending { it.path }
            )
            val zeroFiles = ArrayList<FileEntry>()
            val emptyDirectories = ArrayList<FileEntry>()
            val bySize = HashMap<Long, MutableList<FileEntry>>()
            val folderUsage = LinkedHashMap<String, FolderUsageBuilder>()
            val recentFiles = ArrayList<FileEntry>()
            val directFiles = ArrayList<FileEntry>()
            val recentCutoff = clock() - RECENT_WINDOW_MS
            val queue = ArrayDeque<File>()
            queue.add(root.file)

            root.file.listFiles()?.forEach { child ->
                if (!child.isDirectory) return@forEach
                val guarded = try {
                    guard.guardForRead(child.absolutePath)
                } catch (_: FileGuardException) {
                    return@forEach
                }
                if (guarded.isSymlink) return@forEach
                folderUsage[guarded.canonicalPath] = FolderUsageBuilder(
                    name = guarded.name,
                    path = guarded.canonicalPath
                )
            }

            var scannedItems = 0
            var truncated = false

            scan@ while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = queue.removeFirst()
                val children = directory.listFiles()?.sortedBy { it.name } ?: continue
                if (children.isEmpty()) {
                    val guarded = try {
                        guard.guardForRead(directory.absolutePath)
                    } catch (_: FileGuardException) {
                        continue
                    }
                    emptyDirectories.add(buildEntry(guarded))
                    continue
                }

                for (child in children) {
                    if (scannedItems >= maxItems) {
                        truncated = true
                        break@scan
                    }
                    scannedItems++
                    if (scannedItems and 0x3FF == 0) {
                        currentCoroutineContext().ensureActive()
                    }

                    val guarded = try {
                        guard.guardForRead(child.absolutePath)
                    } catch (_: FileGuardException) {
                        continue
                    }
                    if (guarded.isSymlink) continue

                    if (guarded.isDirectory) {
                        queue.add(guarded.file)
                    } else {
                        val entry = buildEntry(guarded)
                        if (entry.sizeBytes == 0L) {
                            zeroFiles.add(entry)
                        }
                        if (entry.modifiedAt >= recentCutoff) {
                            recentFiles.add(entry)
                        }
                        val relative = entryPathRelativeTo(root.file, guarded.file)
                        if (relative == null) {
                            directFiles.add(entry)
                        } else {
                            val firstFolder = relative.substringBefore(File.separatorChar)
                            val folderPath = File(root.file, firstFolder).absolutePath
                            val builder = folderUsage.getOrPut(folderPath) {
                                FolderUsageBuilder(
                                    name = firstFolder,
                                    path = folderPath
                                )
                            }
                            builder.sizeBytes += entry.sizeBytes
                            builder.fileCount += 1
                        }
                        if (entry.sizeBytes > minLargeFileBytes) {
                            if (large.size < maxLargeFiles) {
                                large.add(entry)
                            } else {
                                val smallest = large.peek()
                                if (smallest != null && entry.sizeBytes > smallest.sizeBytes) {
                                    large.poll()
                                    large.add(entry)
                                }
                            }
                        }
                        if (entry.sizeBytes > 0L) {
                            bySize.getOrPut(entry.sizeBytes) { ArrayList() }.add(entry)
                        }
                    }
                }
            }

            if (queue.isNotEmpty()) truncated = true

            val duplicateGroups = ArrayList<DuplicateGroup>()
            duplicateLoop@ for ((size, sameSizeFiles) in bySize) {
                if (sameSizeFiles.size < 2) continue
                val byHash = HashMap<String, MutableList<FileEntry>>()
                for (file in sameSizeFiles) {
                    val hash = hashPrefix(file.path) ?: continue
                    byHash.getOrPut(hash) { ArrayList() }.add(file)
                }
                for ((hash, files) in byHash) {
                    if (files.size < 2) continue
                    if (duplicateGroups.size >= maxDuplicateGroups) {
                        truncated = true
                        break@duplicateLoop
                    }
                    duplicateGroups.add(
                        DuplicateGroup(
                            sizeBytes = size,
                            hashPrefix = hash,
                            files = files.sortedBy { it.path }
                        )
                    )
                }
            }

            DirectoryAnalysisResult(
                rootPath = root.canonicalPath,
                largeFiles = large.sortedWith(
                    compareByDescending<FileEntry> { it.sizeBytes }.thenBy { it.path }
                ),
                zeroByteFiles = zeroFiles.sortedBy { it.path },
                emptyDirectories = emptyDirectories.sortedBy { it.path },
                duplicateGroups = duplicateGroups.sortedWith(
                    compareByDescending<DuplicateGroup> { it.sizeBytes * (it.files.size - 1) }
                        .thenBy { it.hashPrefix }
                ),
                folderUsage = folderUsage.values
                    .map {
                        FolderUsage(
                            name = it.name,
                            path = it.path,
                            sizeBytes = it.sizeBytes,
                            fileCount = it.fileCount
                        )
                    }
                    .sortedWith(
                        compareByDescending<FolderUsage> { it.sizeBytes }
                            .thenBy { it.name.lowercase() }
                    ),
                directFiles = directFiles.sortedWith(
                    compareByDescending<FileEntry> { it.sizeBytes }.thenBy { it.path }
                ),
                recentFiles = recentFiles.sortedWith(
                    compareByDescending<FileEntry> { it.modifiedAt }.thenBy { it.path }
                ),
                scannedItems = scannedItems,
                truncated = truncated
            )
        }

    private fun entryPathRelativeTo(root: File, file: File): String? {
        return try {
            val path = file.relativeTo(root).toPath()
            if (path.nameCount <= 1) null else path.getName(0).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun buildEntry(guarded: GuardedPath): FileEntry {
        val kind = FileClassifier.kindOf(guarded.name, guarded.isDirectory)
        return FileEntry(
            name = guarded.name,
            path = guarded.canonicalPath,
            isDirectory = guarded.isDirectory,
            isSymlink = false,
            sizeBytes = if (guarded.isDirectory) 0L else guarded.sizeBytes,
            modifiedAt = guarded.modifiedAt,
            extension = FileClassifier.extensionOf(guarded.name),
            kind = kind,
            mediaType = when (kind) {
                FileKind.IMAGE -> MediaType.PHOTO
                FileKind.VIDEO -> MediaType.VIDEO
                else -> null
            }
        )
    }

    private fun hashPrefix(path: String): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(duplicatePrefixBytes)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            digest.update(buffer, 0, total)
        }
        digest.digest().joinToString("") { String.format("%02x", it) }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MIN_LARGE_BYTES = 10L * 1024L * 1024L
        const val DEFAULT_MAX_ITEMS = 250_000
        const val DEFAULT_MAX_LARGE_FILES = 100
        const val DEFAULT_MAX_DUPLICATE_GROUPS = 200
        const val DEFAULT_DUPLICATE_PREFIX_BYTES = 4 * 1024
        const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private data class FolderUsageBuilder(
        val name: String,
        val path: String,
        var sizeBytes: Long = 0L,
        var fileCount: Int = 0
    )
}
