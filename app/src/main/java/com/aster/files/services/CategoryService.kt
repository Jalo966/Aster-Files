package com.aster.files.services

import com.aster.files.data.FileCategory
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
import java.util.EnumMap

/**
 * 分类扫描：从存储根递归收集图片/视频/音频/文档/APK/压缩包。
 *
 * 跳过隐藏文件和隐藏目录、符号链接、内部路径与未知扩展名；
 * 所有目录与文件路径继续经过 FileGuard。结果在 IO 上构建。
 */
class CategoryService(
    private val guard: FileGuard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxPerCategory: Int = DEFAULT_MAX_PER_CATEGORY
) {

    init {
        require(maxItems > 0) { "maxItems 必须大于 0" }
        require(maxPerCategory > 0) { "maxPerCategory 必须大于 0" }
    }

    suspend fun scan(rootPath: String = guard.rootPath): CategoryScanResult =
        withContext(ioDispatcher) {
            val root = guard.guardForRead(rootPath)
            if (!root.isDirectory) {
                throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, root.canonicalPath)
            }

            val buckets = EnumMap<FileCategory, MutableList<FileEntry>>(FileCategory::class.java)
            FileCategory.values().forEach { buckets[it] = ArrayList() }

            var scannedItems = 0
            var truncated = false
            val queue = ArrayDeque<File>()
            queue.add(root.file)

            scanLoop@ while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = queue.removeFirst()
                val children = directory.listFiles()?.sortedBy { it.name } ?: continue

                for (child in children) {
                    // 分类页不展示隐藏内容，也不进入隐藏目录（如缩略图缓存）。
                    if (child.name.startsWith(".")) continue
                    if (scannedItems >= maxItems) {
                        truncated = true
                        break@scanLoop
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
                        continue
                    }

                    val category = FileClassifier.categoryOf(child.name) ?: continue
                    val list = buckets.getValue(category)
                    if (list.size >= maxPerCategory) {
                        truncated = true
                        continue
                    }
                    list.add(buildEntry(child, guarded))
                }
            }

            if (queue.isNotEmpty()) truncated = true

            val sorted = buckets.mapValues { (_, entries) ->
                entries.sortedWith(
                    compareByDescending<FileEntry> { it.modifiedAt }
                        .thenBy { it.path }
                )
            }
            CategoryScanResult(
                categories = sorted,
                scannedItems = scannedItems,
                truncated = truncated
            )
        }

    private fun buildEntry(requested: File, guarded: GuardedPath): FileEntry {
        val extension = FileClassifier.extensionOf(requested.name)
        val kind = FileClassifier.kindOf(requested.name, isDirectory = false)
        return FileEntry(
            name = requested.name,
            path = guarded.canonicalPath,
            isDirectory = false,
            isSymlink = false,
            sizeBytes = guarded.sizeBytes,
            modifiedAt = guarded.modifiedAt,
            extension = extension,
            kind = kind,
            mediaType = when (kind) {
                FileKind.IMAGE -> MediaType.PHOTO
                FileKind.VIDEO -> MediaType.VIDEO
                else -> null
            }
        )
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 250_000
        const val DEFAULT_MAX_PER_CATEGORY = 5_000
    }
}
