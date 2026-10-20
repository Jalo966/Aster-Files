package com.aster.files.services

import com.aster.files.data.FileKind
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumMap
import java.util.PriorityQueue

/**
 * 常规存储分析。
 *
 * 只读取目录项与文件大小，不读取媒体内容、不创建扫描任务，也不依赖任何
 * 完整性检测类。扫描在 IO 调度器执行，并定期检查协程取消。
 */
class AnalysisService(
    private val guard: FileGuard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val largestLimit: Int = DEFAULT_LARGEST_LIMIT
) {

    init {
        require(maxItems > 0) { "maxItems 必须大于 0" }
        require(largestLimit > 0) { "largestLimit 必须大于 0" }
    }

    suspend fun analyze(rootPath: String = guard.rootPath): AnalysisResult =
        withContext(ioDispatcher) {
            val root = guard.guardForRead(rootPath)
            if (!root.isDirectory) {
                throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, root.canonicalPath)
            }

            val categoryCounts = EnumMap<AnalysisCategory, Int>(AnalysisCategory::class.java)
            AnalysisCategory.values().forEach { categoryCounts[it] = 0 }

            val largest = PriorityQueue<AnalyzedFile>(
                compareBy<AnalyzedFile> { it.sizeBytes }.thenByDescending { it.path }
            )
            val effectiveLargestLimit = largestLimit.coerceAtMost(MAX_LARGEST_FILES)

            var fileCount = 0
            var directoryCount = 0
            var totalBytes = 0L
            var scannedItems = 0
            var truncated = false

            val queue = ArrayDeque<File>()
            queue.add(root.file)

            scan@ while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = queue.removeFirst()
                val children = directory.listFiles()?.sortedBy { it.name } ?: continue

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
                    } catch (e: FileGuardException) {
                        // 内部路径、越界链接或断链不参与分析。
                        continue
                    }
                    if (guarded.isSymlink) continue

                    if (guarded.isDirectory) {
                        directoryCount++
                        queue.add(guarded.file)
                    } else {
                        fileCount++
                        totalBytes += guarded.sizeBytes

                        val category = categoryOf(guarded.file.name)
                        categoryCounts[category] = (categoryCounts[category] ?: 0) + 1

                        val item = AnalyzedFile(
                            path = guarded.canonicalPath,
                            name = guarded.file.name,
                            sizeBytes = guarded.sizeBytes,
                            category = category
                        )
                        if (largest.size < effectiveLargestLimit) {
                            largest.add(item)
                        } else {
                            val smallest = largest.peek()
                            if (smallest != null && item.sizeBytes > smallest.sizeBytes) {
                                largest.poll()
                                largest.add(item)
                            }
                        }
                    }
                }
            }

            if (queue.isNotEmpty()) {
                truncated = true
            }

            val largestFiles = largest
                .sortedWith(
                    compareByDescending<AnalyzedFile> { it.sizeBytes }
                        .thenBy { it.path }
                )

            AnalysisResult(
                rootPath = root.canonicalPath,
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalBytes = totalBytes,
                categoryCounts = categoryCounts.toMap(),
                largestFiles = largestFiles,
                scannedItems = scannedItems,
                maxItems = maxItems,
                truncated = truncated
            )
        }

    private fun categoryOf(name: String): AnalysisCategory =
        when (FileClassifier.kindOf(name, isDirectory = false)) {
            FileKind.IMAGE -> AnalysisCategory.PHOTO
            FileKind.VIDEO -> AnalysisCategory.VIDEO
            FileKind.DOCUMENT -> AnalysisCategory.DOCUMENT
            FileKind.AUDIO -> AnalysisCategory.AUDIO
            FileKind.ARCHIVE -> AnalysisCategory.ARCHIVE
            FileKind.CODE -> AnalysisCategory.CODE
            FileKind.FOLDER, FileKind.OTHER -> AnalysisCategory.OTHER
        }

    companion object {
        const val DEFAULT_MAX_ITEMS = 250_000
        const val DEFAULT_LARGEST_LIMIT = 20
        const val MAX_LARGEST_FILES = 20
    }
}
