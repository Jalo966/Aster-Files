package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.data.MediaType
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.security.GuardedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID

/**
 * 文件浏览与基础文件操作服务。
 *
 * 所有路径先经过 FileGuard；所有 IO 在 [ioDispatcher]（默认 Dispatchers.IO）
 * 上执行。复制/移动逐项写审计，批量操作逐项返回失败原因。
 */
class FileService(
    private val guard: FileGuard,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBatchItems: Int = MAX_BATCH_ITEMS
) {

    val rootPath: String get() = guard.rootPath

    init {
        require(maxBatchItems > 0) { "maxBatchItems 必须大于 0" }
    }

    // ---------------------------------------------------------------------
    // 浏览与搜索
    // ---------------------------------------------------------------------

    /**
     * 列出目录内容。
     *
     * @param showHidden false 时过滤以 . 开头的文件和文件夹；true 时全部显示。
     * 应用内部路径（.trash、.aster-data）无论 showHidden 为何都不会显示。
     */
    suspend fun listDirectory(
        path: String = guard.rootPath,
        showHidden: Boolean = false
    ): List<FileEntry> =
        withContext(ioDispatcher) {
            val directory = guard.guardForRead(path)
            if (!directory.isDirectory) {
                throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, directory.canonicalPath)
            }
            val children = directory.file.listFiles()
                ?: throw FileGuardException(
                    GuardErrorCode.IO_ERROR,
                    directory.canonicalPath,
                    "无法读取目录内容"
                )
            children
                .filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                .mapNotNull { child -> toFileEntryOrNull(child) }
        }

    /**
     * 当前目录递归搜索。
     *
     * 最多 [MAX_SEARCH_DEPTH] 层、[MAX_SEARCH_RESULTS] 条；不跟随符号链接。
     */
    suspend fun search(
        directoryPath: String = guard.rootPath,
        query: String,
        maxDepth: Int = MAX_SEARCH_DEPTH,
        maxResults: Int = MAX_SEARCH_RESULTS,
        showHidden: Boolean = false
    ): List<FileEntry> = withContext(ioDispatcher) {
        if (query.isBlank()) return@withContext emptyList()

        val root = guard.guardForRead(directoryPath)
        if (!root.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_DIRECTORY, root.canonicalPath)
        }

        val depthLimit = maxDepth.coerceIn(1, MAX_SEARCH_DEPTH)
        val resultLimit = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val results = ArrayList<FileEntry>()
        val queue = ArrayDeque<Pair<GuardedPath, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && results.size < resultLimit) {
            val (directory, depth) = queue.removeFirst()
            val children = directory.file.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (results.size >= resultLimit) break
                if (!showHidden && child.name.startsWith(".")) continue
                val guarded = try {
                    guard.guardForRead(child.absolutePath)
                } catch (e: FileGuardException) {
                    continue
                }
                // 不跟随符号链接，也不把链接本身作为搜索结果。
                if (guarded.isSymlink) continue

                if (child.name.contains(query, ignoreCase = true)) {
                    results.add(buildFileEntry(child, guarded))
                }
                if (guarded.isDirectory && depth + 1 < depthLimit) {
                    queue.add(guarded to depth + 1)
                }
            }
        }
        results.sortedBy { it.path.lowercase(Locale.ROOT) }
    }

    // ---------------------------------------------------------------------
    // 预览
    // ---------------------------------------------------------------------

    /** 校验并返回可读取文件的真实路径，供图片预览等 UI 使用。 */
    suspend fun canonicalReadablePath(path: String): String = withContext(ioDispatcher) {
        guard.guardReadableFile(path).canonicalPath
    }

    /**
     * 读取文本预览；非文本文件返回 null。
     *
     * 最多读取 maxBytes，超出部分标记为截断；遇到 NUL 视为二进制。
     */
    suspend fun readTextPreview(
        path: String,
        maxBytes: Int = DEFAULT_TEXT_PREVIEW_BYTES
    ): String? = withContext(ioDispatcher) {
        val guarded = guard.guardReadableFile(path)
        if (!FileClassifier.isTextPreviewable(guarded.file.name)) return@withContext null
        if (maxBytes <= 0) return@withContext null

        val bytes = java.io.ByteArrayOutputStream()
        var truncated = false
        java.io.FileInputStream(guarded.file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > maxBytes) {
                    val allowed = (maxBytes - bytes.size()).coerceAtLeast(0)
                    if (allowed > 0) bytes.write(buffer, 0, allowed)
                    truncated = true
                    break
                }
                bytes.write(buffer, 0, read)
            }
        }
        val data = bytes.toByteArray()
        if (data.any { it == 0.toByte() }) return@withContext null
        val text = String(data, Charsets.UTF_8)
        if (truncated) text + "\n\n…（内容已截断）" else text
    }

    /** 读取完整文本文件用于编辑器；超过上限时拒绝，避免无界内存。 */
    suspend fun readTextFile(
        path: String,
        maxBytes: Int = DEFAULT_MAX_TEXT_EDIT_BYTES
    ): String = withContext(ioDispatcher) {
        require(maxBytes > 0)
        val guarded = guard.guardReadableFile(path)
        if (guarded.isDirectory) {
            throw FileGuardException(GuardErrorCode.NOT_FILE, guarded.canonicalPath)
        }
        if (guarded.sizeBytes > maxBytes) {
            throw FileGuardException(
                GuardErrorCode.IO_ERROR,
                guarded.canonicalPath,
                "文本文件超过编辑上限 " + maxBytes + " 字节"
            )
        }
        val bytes = Files.readAllBytes(guarded.file.toPath())
        if (bytes.any { it == 0.toByte() }) {
            throw FileGuardException(
                GuardErrorCode.UNSUPPORTED_FILE_TYPE,
                guarded.canonicalPath,
                "二进制文件不能作为文本编辑"
            )
        }
        String(bytes, Charsets.UTF_8)
    }

    /**
     * 保存文本编辑器内容。
     * 写入前再次通过 FileGuard 校验文件身份与路径，不改变文件名。
     */
    suspend fun writeTextFile(
        path: String,
        content: String,
        maxBytes: Int = DEFAULT_MAX_TEXT_EDIT_BYTES
    ): OperationResult = withContext(ioDispatcher) {
        val result = try {
            require(maxBytes > 0)
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > maxBytes) {
                throw IllegalArgumentException("内容超过保存上限 " + maxBytes + " 字节")
            }
            val guarded = guard.guardForWrite(path)
            if (guarded.isDirectory) {
                throw FileGuardException(GuardErrorCode.NOT_FILE, guarded.canonicalPath)
            }
            guard.verifyUnchanged(guarded)
            Files.write(
                guarded.file.toPath(),
                bytes,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            OperationResult.ok(
                message = "已保存",
                targetPath = guarded.canonicalPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, path, null)
        }
        auditFile("WRITE_TEXT", result)
        result
    }

    // ---------------------------------------------------------------------
    // 新建与重命名
    // ---------------------------------------------------------------------

    suspend fun createDirectory(parentPath: String, name: String): OperationResult =
        withContext(ioDispatcher) {
            createEntry(parentPath, name, directory = true)
        }

    suspend fun createFile(parentPath: String, name: String): OperationResult =
        withContext(ioDispatcher) {
            createEntry(parentPath, name, directory = false)
        }

    suspend fun rename(path: String, newName: String): OperationResult =
        withContext(ioDispatcher) {
            var sourcePath: String? = path
            var targetPath: String? = null
            val result = try {
                val plan = guard.prepareRename(path, newName)
                sourcePath = plan.source.canonicalPath
                targetPath = plan.target.targetPath
                guard.verifyRenamePlan(plan)
                Files.move(plan.source.file.toPath(), plan.target.target.toPath())
                FileTransferSupport.verifyCreated(guard, plan.target)
                OperationResult.ok(
                    message = "重命名完成",
                    sourcePath = sourcePath,
                    targetPath = targetPath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e, sourcePath, targetPath)
            }
            auditFile("RENAME", result)
            result
        }

    /**
     * 批量重命名：先完成全部 FileGuard 预检，再逐项移动；任何冲突都不会开始。
     * 执行中异常会尽量回滚已经完成的项，并为每个条目写带同一 taskId 的审计。
     */
    suspend fun renameBatch(requests: List<BatchRenameRequest>): OperationResult =
        withContext(ioDispatcher) {
            if (requests.isEmpty()) {
                return@withContext OperationResult.failed(
                    "没有需要重命名的文件",
                    "BATCH_RENAME_EMPTY"
                )
            }
            if (requests.size > maxBatchItems) {
                return@withContext OperationResult.failed(
                    "单次最多重命名 $maxBatchItems 项",
                    "BATCH_RENAME_TOO_MANY"
                )
            }
            if (requests.map { it.sourcePath }.distinct().size != requests.size) {
                return@withContext OperationResult.failed(
                    "批量重命名中存在重复源文件",
                    "BATCH_RENAME_DUPLICATE_SOURCE"
                )
            }

            val batchId = UUID.randomUUID().toString()
            val prepared = ArrayList<Pair<BatchRenameRequest, com.aster.files.security.RenamePlan>>()
            val batchResult = try {
                for (request in requests) {
                    val plan = guard.prepareRename(request.sourcePath, request.newName)
                    guard.verifyRenamePlan(plan)
                    prepared.add(request to plan)
                }
                if (prepared.map { it.second.target.targetPath }.distinct().size != prepared.size) {
                    throw FileGuardException(
                        GuardErrorCode.ALREADY_EXISTS,
                        requests.first().sourcePath,
                        "批量重命名目标文件名重复"
                    )
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e, requests.first().sourcePath, null)
            }
            if (batchResult != null) {
                auditBatchRename(
                    taskId = batchId,
                    status = AuditStatus.FAILED,
                    sourcePath = requests.first().sourcePath,
                    targetPath = null,
                    reason = batchResult.message
                )
                return@withContext batchResult.copy(errorCode = "BATCH_RENAME_CONFLICT")
            }

            val completed = ArrayList<Pair<BatchRenameRequest, com.aster.files.security.RenamePlan>>()
            try {
                for ((request, plan) in prepared) {
                    Files.move(plan.source.file.toPath(), plan.target.target.toPath())
                    FileTransferSupport.verifyCreated(guard, plan.target)
                    completed.add(request to plan)
                    audit.record(
                        category = AuditCategory.FILE,
                        operation = "BATCH_RENAME_ITEM",
                        status = AuditStatus.SUCCESS,
                        taskId = batchId,
                        sourcePath = request.sourcePath,
                        targetPath = plan.target.targetPath
                    )
                }
                auditBatchRename(
                    taskId = batchId,
                    status = AuditStatus.SUCCESS,
                    sourcePath = null,
                    targetPath = null,
                    reason = null
                )
                OperationResult.ok(
                    message = "批量重命名完成（" + completed.size + " 项）",
                    sourcePath = requests.first().sourcePath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audit.record(
                    category = AuditCategory.FILE,
                    operation = "BATCH_RENAME_ITEM",
                    status = AuditStatus.FAILED,
                    taskId = batchId,
                    sourcePath = null,
                    targetPath = null,
                    reason = e.message ?: "批量重命名失败"
                )
                var rollbackFailed = 0
                for ((request, plan) in completed.asReversed()) {
                    try {
                        val rollback = guard.prepareRename(plan.target.targetPath, request.oldName)
                        guard.verifyRenamePlan(rollback)
                        Files.move(rollback.source.file.toPath(), rollback.target.target.toPath())
                        FileTransferSupport.verifyCreated(guard, rollback.target)
                    } catch (_: Exception) {
                        rollbackFailed++
                    }
                }
                val result = failure(e, requests.first().sourcePath, null)
                auditBatchRename(
                    taskId = batchId,
                    status = AuditStatus.FAILED,
                    sourcePath = requests.first().sourcePath,
                    targetPath = null,
                    reason = if (rollbackFailed > 0) {
                        result.message + "；另有 $rollbackFailed 项回滚失败"
                    } else {
                        result.message + "；已完成项已回滚"
                    }
                )
                result
            }
        }

    private suspend fun auditBatchRename(
        taskId: String,
        status: AuditStatus,
        sourcePath: String?,
        targetPath: String?,
        reason: String?
    ) {
        audit.record(
            category = AuditCategory.FILE,
            operation = "BATCH_RENAME",
            status = status,
            taskId = taskId,
            sourcePath = sourcePath,
            targetPath = targetPath,
            reason = reason
        )
    }

    // ---------------------------------------------------------------------
    // 复制与移动
    // ---------------------------------------------------------------------

    suspend fun copy(
        sourcePath: String,
        targetDirectory: String,
        newName: String? = null
    ): OperationResult = withContext(ioDispatcher) {
        copyInternal(sourcePath, targetDirectory, newName)
    }

    suspend fun move(
        sourcePath: String,
        targetDirectory: String,
        newName: String? = null
    ): OperationResult = withContext(ioDispatcher) {
        moveInternal(sourcePath, targetDirectory, newName)
    }

    suspend fun copyBatch(
        sourcePaths: List<String>,
        targetDirectory: String
    ): BatchResult = withContext(ioDispatcher) {
        val unique = sourcePaths.distinct()
        if (unique.size > maxBatchItems) {
            val result = OperationResult.failed(
                "单次最多 $maxBatchItems 项",
                "BATCH_TOO_LARGE"
            )
            auditFile("COPY_BATCH", result)
            return@withContext BatchResult("COPY", listOf(result))
        }
        val results = unique.map { source ->
            copyInternal(source, targetDirectory, newName = null)
        }
        BatchResult("COPY", results)
    }

    suspend fun moveBatch(
        sourcePaths: List<String>,
        targetDirectory: String
    ): BatchResult = withContext(ioDispatcher) {
        val unique = sourcePaths.distinct()
        if (unique.size > maxBatchItems) {
            val result = OperationResult.failed(
                "单次最多 $maxBatchItems 项",
                "BATCH_TOO_LARGE"
            )
            auditFile("MOVE_BATCH", result)
            return@withContext BatchResult("MOVE", listOf(result))
        }
        val results = unique.map { source ->
            moveInternal(source, targetDirectory, newName = null)
        }
        BatchResult("MOVE", results)
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private suspend fun createEntry(
        parentPath: String,
        name: String,
        directory: Boolean
    ): OperationResult {
        var targetPath: String? = null
        val result = try {
            val plan = guard.prepareCreate(parentPath, name)
            targetPath = plan.targetPath
            guard.verifyCreationTarget(plan)
            if (directory) {
                Files.createDirectory(plan.target.toPath())
            } else {
                Files.createFile(plan.target.toPath())
            }
            FileTransferSupport.verifyCreated(guard, plan)
            OperationResult.ok(
                message = if (directory) "已创建文件夹" else "已创建文件",
                sourcePath = parentPath,
                targetPath = plan.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, parentPath, targetPath)
        }
        auditFile(if (directory) "CREATE_DIRECTORY" else "CREATE_FILE", result)
        return result
    }

    private suspend fun copyInternal(
        sourcePath: String,
        targetDirectory: String,
        newName: String?
    ): OperationResult {
        var plan: com.aster.files.security.CopyPlan? = null
        var targetPath: String? = null
        val result = try {
            val prepared = guard.prepareCopy(sourcePath, targetDirectory, newName)
            plan = prepared
            targetPath = prepared.target.targetPath
            guard.verifyCopyPlan(prepared)
            FileTransferSupport.copyTree(guard, prepared.source, prepared.target)
            FileTransferSupport.verifyCreated(guard, prepared.target)
            OperationResult.ok(
                message = "复制完成",
                sourcePath = prepared.source.canonicalPath,
                targetPath = prepared.target.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            plan?.let { FileTransferSupport.cleanupTarget(guard, it.target) }
            failure(e, sourcePath, targetPath)
        }
        auditFile("COPY", result)
        return result
    }

    private suspend fun moveInternal(
        sourcePath: String,
        targetDirectory: String,
        newName: String?
    ): OperationResult {
        var targetPath: String? = null
        val result = try {
            val plan = guard.prepareMove(sourcePath, targetDirectory, newName)
            targetPath = plan.target.targetPath
            guard.verifyMovePlan(plan)
            val mode = FileTransferSupport.moveOrCopy(guard, plan.source, plan.target)
            FileTransferSupport.verifyCreated(guard, plan.target)
            OperationResult.ok(
                message = if (mode == TransferMode.RENAMED) {
                    "移动完成"
                } else {
                    "移动完成（跨卷复制后删除源）"
                },
                sourcePath = plan.source.canonicalPath,
                targetPath = plan.target.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, sourcePath, targetPath)
        }
        auditFile("MOVE", result)
        return result
    }

    private fun toFileEntryOrNull(child: File): FileEntry? = try {
        val guarded = guard.guardForRead(child.absolutePath)
        buildFileEntry(child, guarded)
    } catch (e: FileGuardException) {
        // 越界/断链条目不在普通列表中展示，避免后续操作误触。
        null
    }

    private fun buildFileEntry(requested: File, guarded: GuardedPath): FileEntry {
        val extension = FileClassifier.extensionOf(requested.name)
        val kind = FileClassifier.kindOf(requested.name, guarded.isDirectory)
        return FileEntry(
            name = requested.name,
            path = requested.absolutePath,
            isDirectory = guarded.isDirectory,
            isSymlink = guarded.isSymlink,
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

    private suspend fun auditFile(operation: String, result: OperationResult) {
        audit.record(
            category = AuditCategory.FILE,
            operation = operation,
            status = if (result.success) AuditStatus.SUCCESS else AuditStatus.FAILED,
            sourcePath = result.sourcePath,
            targetPath = result.targetPath,
            reason = if (result.success) null else result.message
        )
    }

    private fun failure(
        error: Exception,
        sourcePath: String?,
        targetPath: String?
    ): OperationResult {
        val code = when (error) {
            is FileGuardException -> error.stableCode
            is FileAlreadyExistsException -> GuardErrorCode.ALREADY_EXISTS.code
            else -> "IO_ERROR"
        }
        return OperationResult.failed(
            message = error.message ?: "操作失败",
            errorCode = code,
            sourcePath = sourcePath,
            targetPath = targetPath
        )
    }

    companion object {
        const val MAX_BATCH_ITEMS = 200
        const val MAX_SEARCH_DEPTH = 12
        const val MAX_SEARCH_RESULTS = 300
        const val DEFAULT_TEXT_PREVIEW_BYTES = 64 * 1024
        const val DEFAULT_MAX_TEXT_EDIT_BYTES = 5 * 1024 * 1024
    }
}
