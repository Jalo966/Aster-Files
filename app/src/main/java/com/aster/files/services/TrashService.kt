package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.EntryType
import com.aster.files.data.TrashEntry
import com.aster.files.data.TrashStatus
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.security.GuardedPath
import com.aster.files.security.MovePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID

/**
 * 软删除与恢复服务。
 *
 * 物理回收站固定在 $trashRoot/files，trashRoot 必须是 FileGuard 的内部根，
 * 不进入可浏览根。提供软删除、恢复、永久删除、清空与可配置保留期清理。
 */
class TrashService(
    private val guard: FileGuard,
    trashRoot: File,
    private val trashStore: TrashStore,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val maxBatchItems: Int = FileService.MAX_BATCH_ITEMS
) {

    // 构造时不做磁盘访问或 FileGuard 初始化；首次在 IO 上使用时才校验。
    private val canonicalTrashRoot: File by lazy {
        try {
            trashRoot.canonicalFile
        } catch (e: IOException) {
            throw IllegalArgumentException("无法解析回收站根目录", e)
        }
    }

    /** 回收站物理目录（应用私有，不在可浏览根内）。 */
    val trashFilesPath: String get() = File(validatedTrashRoot(), "files").path

    init {
        require(maxBatchItems > 0) { "maxBatchItems 必须大于 0" }
    }

    private fun validatedTrashRoot(): File {
        val canonical = canonicalTrashRoot
        require(guard.internalRootPaths.any { it == canonical.path }) {
            "trashRoot 必须是 FileGuard 的内部根：${canonical.path}"
        }
        return canonical
    }

    // ---------------------------------------------------------------------
    // 软删除
    // ---------------------------------------------------------------------

    suspend fun softDelete(path: String): OperationResult = withContext(ioDispatcher) {
        val result = softDeleteInternal(path)
        auditTrash("SOFT_DELETE", result)
        result
    }

    suspend fun softDeleteBatch(paths: List<String>): BatchResult = withContext(ioDispatcher) {
        val unique = paths.distinct()
        if (unique.size > maxBatchItems) {
            val result = OperationResult.failed(
                "单次最多 $maxBatchItems 项",
                "BATCH_TOO_LARGE"
            )
            auditTrash("SOFT_DELETE_BATCH", result)
            return@withContext BatchResult("SOFT_DELETE", listOf(result))
        }
        val results = unique.map { softDelete(it) }
        BatchResult("SOFT_DELETE", results)
    }

    // ---------------------------------------------------------------------
    // 恢复
    // ---------------------------------------------------------------------

    suspend fun restore(trashId: String): OperationResult = withContext(ioDispatcher) {
        val result = restoreInternal(trashId)
        auditTrash("RESTORE", result)
        result
    }

    /** 回收站列表；只返回 ACTIVE 记录。 */
    suspend fun list(): List<TrashEntry> = withContext(ioDispatcher) {
        trashStore.listActive()
    }

    // ---------------------------------------------------------------------
    // 永久删除与过期清理
    // ---------------------------------------------------------------------

    /** 永久删除单条回收站记录：先删物理文件，再删数据库行。 */
    suspend fun deletePermanently(trashId: String): OperationResult = withContext(ioDispatcher) {
        val result = deletePermanentlyInternal(trashId)
        auditTrash("PERMANENT_DELETE", result)
        result
    }

    /** 清空回收站；逐项永久删除。 */
    suspend fun emptyTrash(): BatchResult = withContext(ioDispatcher) {
        val results = trashStore.listActive().map { entry ->
            deletePermanentlyInternal(entry.trashId).also { result ->
                auditTrash("PERMANENT_DELETE", result)
            }
        }
        BatchResult("EMPTY_TRASH", results)
    }

    /**
     * 启动时清理超过用户配置保留期的记录（默认 30 天）。
     * 仅处理 ACTIVE 记录；每项写审计。
     */
    suspend fun cleanupExpired(
        retentionDays: Int = TrashRetentionPolicy.DEFAULT_DAYS
    ): BatchResult = withContext(ioDispatcher) {
        val cutoff = TrashRetentionPolicy.cutoff(clock(), retentionDays)
        val expired = trashStore.listActive().filter { it.deletedAt <= cutoff }
        val results = expired.map { entry ->
            deletePermanentlyInternal(entry.trashId).also { result ->
                auditTrash("AUTO_CLEANUP", result)
            }
        }
        BatchResult("AUTO_CLEANUP", results)
    }

    private suspend fun deletePermanentlyInternal(trashId: String): OperationResult {
        val entry = trashStore.get(trashId)
            ?: return OperationResult.failed(
                message = "回收站记录不存在",
                errorCode = "TRASH_NOT_FOUND"
            )
        if (entry.status != TrashStatus.ACTIVE) {
            return OperationResult.failed(
                message = "该回收站记录已恢复",
                errorCode = "TRASH_NOT_ACTIVE",
                sourcePath = entry.trashPath
            )
        }

        return try {
            val filesDir = ensureTrashFilesDir()
            val prefix = filesDir.canonicalPath + File.separator
            val guarded = try {
                guard.guardForInternalRead(entry.trashPath)
            } catch (e: FileGuardException) {
                if (e.code == GuardErrorCode.SOURCE_NOT_FOUND) {
                    null
                } else {
                    throw e
                }
            }
            if (guarded != null) {
                if (!guarded.canonicalPath.startsWith(prefix)) {
                    throw FileGuardException(
                        GuardErrorCode.INVALID_PATH,
                        guarded.canonicalPath,
                        "回收站路径越界"
                    )
                }
                if (guarded.isSymlink) {
                    throw FileGuardException(
                        GuardErrorCode.SYMLINK_FORBIDDEN,
                        guarded.canonicalPath
                    )
                }
                FileTransferSupport.deleteTree(guard, guarded)
            }

            if (!trashStore.delete(trashId)) {
                throw IOException("回收站数据库记录删除失败")
            }
            OperationResult.ok(
                message = "已永久删除",
                sourcePath = entry.trashPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, entry.trashPath, null)
        }
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private suspend fun softDeleteInternal(path: String): OperationResult {
        val trashId = idGenerator()
        var plan: MovePlan? = null
        var moved = false
        var targetPath: String? = null

        return try {
            val filesDir = ensureTrashFilesDir()
            val prepared = guard.prepareMoveAcrossRoots(
                sourcePath = path,
                targetDirectoryPath = filesDir.canonicalPath,
                newName = trashId,
                sourceInternal = false,
                targetInternal = true
            )
            plan = prepared
            targetPath = prepared.target.targetPath
            guard.verifyMovePlan(prepared)

            val sizeBytes = FileTransferSupport.measureSize(guard, prepared.source)
            FileTransferSupport.moveOrCopy(guard, prepared.source, prepared.target)
            moved = true
            val created = FileTransferSupport.verifyCreated(guard, prepared.target)
            if (created.isSymlink) {
                throw FileGuardException(GuardErrorCode.SYMLINK_FORBIDDEN, created.canonicalPath)
            }

            trashStore.insert(
                TrashEntry(
                    trashId = trashId,
                    originalPath = prepared.source.canonicalPath,
                    trashPath = prepared.target.targetPath,
                    name = prepared.source.name,
                    entryType = if (prepared.source.isDirectory) EntryType.DIRECTORY else EntryType.FILE,
                    sizeBytes = sizeBytes,
                    deletedAt = clock(),
                    status = TrashStatus.ACTIVE,
                    restoredAt = null
                )
            )
            OperationResult.ok(
                message = "已移入回收站",
                sourcePath = prepared.source.canonicalPath,
                targetPath = prepared.target.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (moved && plan != null) {
                rollbackSoftDelete(plan)
            }
            failure(e, path, targetPath)
        }
    }

    private suspend fun restoreInternal(trashId: String): OperationResult {
        var sourcePath: String? = null
        var targetPath: String? = null
        var plan: MovePlan? = null
        var moved = false

        return try {
            validatedTrashRoot()
            val entry = trashStore.get(trashId)
                ?: return OperationResult.failed(
                    message = "回收站记录不存在",
                    errorCode = "TRASH_NOT_FOUND"
                )
            if (entry.status != TrashStatus.ACTIVE) {
                return OperationResult.failed(
                    message = "该回收站记录已恢复",
                    errorCode = "TRASH_NOT_ACTIVE"
                )
            }

            sourcePath = entry.trashPath
            val original = File(entry.originalPath)
            val parent = original.parentFile
                ?: return OperationResult.failed(
                    message = "原路径缺少父目录，无法恢复",
                    errorCode = GuardErrorCode.INVALID_PATH.code,
                    sourcePath = sourcePath
                )

            val prepared = guard.prepareMoveAcrossRoots(
                sourcePath = entry.trashPath,
                targetDirectoryPath = parent.absolutePath,
                newName = entry.name,
                sourceInternal = true,
                targetInternal = false
            )
            plan = prepared
            targetPath = prepared.target.targetPath
            guard.verifyMovePlan(prepared)

            FileTransferSupport.moveOrCopy(guard, prepared.source, prepared.target)
            moved = true
            FileTransferSupport.verifyCreated(guard, prepared.target)

            if (!trashStore.markRestored(trashId, clock())) {
                throw IOException("回收站状态更新失败")
            }

            OperationResult.ok(
                message = "已恢复",
                sourcePath = entry.trashPath,
                targetPath = prepared.target.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (moved && plan != null) {
                rollbackRestore(plan)
            }
            failure(e, sourcePath, targetPath)
        }
    }

    private fun ensureTrashFilesDir(): GuardedPath {
        val trashDirectory = validatedTrashRoot()
        val existing = try {
            guard.guardInternalTargetDirectory(File(trashDirectory, "files").absolutePath)
        } catch (e: FileGuardException) {
            null
        }
        if (existing != null) {
            if (existing.isSymlink) {
                throw FileGuardException(
                    GuardErrorCode.SYMLINK_FORBIDDEN,
                    existing.canonicalPath,
                    "回收站物理目录不允许是符号链接"
                )
            }
            return existing
        }

        val plan = guard.prepareInternalCreate(trashDirectory.path, "files")
        guard.verifyCreationTarget(plan)
        try {
            Files.createDirectory(plan.target.toPath())
        } catch (e: FileAlreadyExistsException) {
            // 并发创建：随后由 guard 再次校验目录类型。
        }
        val created = guard.guardInternalTargetDirectory(plan.targetPath)
        if (created.isSymlink) {
            throw FileGuardException(GuardErrorCode.SYMLINK_FORBIDDEN, created.canonicalPath)
        }
        return created
    }

    private fun rollbackSoftDelete(plan: MovePlan) {
        try {
            val originalParent = plan.source.file.parentFile ?: return
            val rollback = guard.prepareMoveAcrossRoots(
                sourcePath = plan.target.targetPath,
                targetDirectoryPath = originalParent.absolutePath,
                newName = plan.source.name,
                sourceInternal = true,
                targetInternal = false
            )
            guard.verifyMovePlan(rollback)
            FileTransferSupport.moveOrCopy(guard, rollback.source, rollback.target)
            FileTransferSupport.verifyCreated(guard, rollback.target)
        } catch (_: Exception) {
            // 回滚失败时保留回收站副本，避免删除唯一数据。
        }
    }

    private fun rollbackRestore(plan: MovePlan) {
        try {
            val filesDir = ensureTrashFilesDir()
            val trashName = File(plan.source.canonicalPath).name
            val rollback = guard.prepareMoveAcrossRoots(
                sourcePath = plan.target.targetPath,
                targetDirectoryPath = filesDir.canonicalPath,
                newName = trashName,
                sourceInternal = false,
                targetInternal = true
            )
            guard.verifyMovePlan(rollback)
            FileTransferSupport.moveOrCopy(guard, rollback.source, rollback.target)
            FileTransferSupport.verifyCreated(guard, rollback.target)
        } catch (_: Exception) {
            // 回滚失败时保留已恢复的文件，避免删除用户数据。
        }
    }

    companion object {
        const val RETENTION_DAYS = TrashRetentionPolicy.DEFAULT_DAYS
    }

    private suspend fun auditTrash(operation: String, result: OperationResult) {
        audit.record(
            category = AuditCategory.TRASH,
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
}
