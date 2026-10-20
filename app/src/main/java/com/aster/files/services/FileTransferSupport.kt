package com.aster.files.services

import com.aster.files.security.CreationPlan
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.security.GuardedPath
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal enum class TransferMode {
    RENAMED,
    COPIED_AND_DELETED
}

/**
 * 文件树复制/删除的共享实现。
 *
 * 所有路径都先经过 FileGuard；本类只负责实际 IO，不重复实现路径仲裁。
 * 调用方必须已持有 FileGuard 计划并在执行前完成 verify。
 */
internal object FileTransferSupport {

    fun copyTree(guard: FileGuard, source: GuardedPath, target: CreationPlan) {
        guard.verifyCreationTarget(target)
        if (source.isDirectory) {
            Files.createDirectory(target.target.toPath())
            val children = source.file.listFiles()?.sortedBy { it.name }
                ?: throw FileGuardException(
                    GuardErrorCode.IO_ERROR,
                    source.canonicalPath,
                    "无法读取目录内容"
                )
            for (child in children) {
                val childSource = readGuarded(guard, child.absolutePath, source.isInternalPath)
                if (childSource.isSymlink) {
                    throw FileGuardException(
                        GuardErrorCode.SYMLINK_FORBIDDEN,
                        child.absolutePath,
                        "目录内包含符号链接，禁止复制"
                    )
                }
                val childTarget = createGuarded(
                    guard,
                    target.target.absolutePath,
                    child.name,
                    target.isInternalPath
                )
                guard.verifyCreationTarget(childTarget)
                if (childSource.isDirectory) {
                    copyTree(guard, childSource, childTarget)
                } else {
                    copyFile(guard, childSource, childTarget)
                }
            }
        } else {
            copyFile(guard, source, target)
        }
    }

    private fun copyFile(guard: FileGuard, source: GuardedPath, target: CreationPlan) {
        Files.copy(source.file.toPath(), target.target.toPath())
        guard.verifyUnchanged(source)
        val copiedSize = Files.size(target.target.toPath())
        if (copiedSize != source.sizeBytes) {
            throw FileGuardException(
                GuardErrorCode.IO_ERROR,
                target.targetPath,
                "复制后文件大小不一致"
            )
        }
    }

    fun deleteTree(guard: FileGuard, path: GuardedPath) {
        if (path.isDirectory) {
            val children = path.file.listFiles()?.sortedBy { it.name }
                ?: throw FileGuardException(
                    GuardErrorCode.IO_ERROR,
                    path.canonicalPath,
                    "无法读取目录内容"
                )
            for (child in children) {
                val childPath = writeGuarded(guard, child.absolutePath, path.isInternalPath)
                if (childPath.isDirectory) {
                    deleteTree(guard, childPath)
                } else {
                    Files.delete(childPath.file.toPath())
                }
            }
        }
        Files.delete(path.file.toPath())
    }

    fun measureSize(guard: FileGuard, path: GuardedPath): Long {
        if (!path.isDirectory) return path.sizeBytes
        var total = 0L
        val children = path.file.listFiles()?.sortedBy { it.name }
            ?: throw FileGuardException(
                GuardErrorCode.IO_ERROR,
                path.canonicalPath,
                "无法读取目录内容"
            )
        for (child in children) {
            val childPath = readGuarded(guard, child.absolutePath, path.isInternalPath)
            if (childPath.isSymlink) continue
            total += if (childPath.isDirectory) measureSize(guard, childPath) else childPath.sizeBytes
        }
        return total
    }

    /**
     * 优先同卷 rename；失败时安全复制再删除源。
     *
     * 复制失败会清理目标；复制成功但删除源失败时保留目标，避免两侧都丢失数据。
     */
    fun moveOrCopy(guard: FileGuard, source: GuardedPath, target: CreationPlan): TransferMode {
        return try {
            Files.move(source.file.toPath(), target.target.toPath())
            TransferMode.RENAMED
        } catch (e: FileAlreadyExistsException) {
            throw FileGuardException(GuardErrorCode.ALREADY_EXISTS, target.targetPath)
        } catch (e: IOException) {
            fallbackMove(guard, source, target, e)
        }
    }

    private fun fallbackMove(
        guard: FileGuard,
        source: GuardedPath,
        target: CreationPlan,
        moveError: IOException
    ): TransferMode {
        try {
            if (source.isDirectory) {
                guard.assertNoSymlinksInTree(source)
            }
            copyTree(guard, source, target)
        } catch (copyError: Exception) {
            cleanupTarget(guard, target)
            if (copyError is FileGuardException) throw copyError
            throw FileGuardException(
                GuardErrorCode.IO_ERROR,
                source.canonicalPath,
                "跨卷复制失败",
                copyError
            )
        }

        try {
            guard.verifyUnchanged(source)
        } catch (e: FileGuardException) {
            cleanupTarget(guard, target)
            throw e
        }

        try {
            deleteTree(guard, source)
        } catch (deleteError: Exception) {
            throw FileGuardException(
                GuardErrorCode.IO_ERROR,
                source.canonicalPath,
                "已复制到目标，但删除源失败",
                if (deleteError is IOException) deleteError else moveError
            )
        }
        return TransferMode.COPIED_AND_DELETED
    }

    fun verifyCreated(guard: FileGuard, target: CreationPlan): GuardedPath {
        val created = readGuarded(guard, target.targetPath, target.isInternalPath)
        if (created.isSymlink) {
            throw FileGuardException(GuardErrorCode.SYMLINK_FORBIDDEN, target.targetPath)
        }
        return created
    }

    fun cleanupTarget(guard: FileGuard, target: CreationPlan) {
        val guarded = try {
            readGuarded(guard, target.targetPath, target.isInternalPath)
        } catch (e: Exception) {
            return
        }
        try {
            deleteTree(guard, guarded)
        } catch (_: Exception) {
            // 清理失败不覆盖原始错误。
        }
    }

    private fun readGuarded(guard: FileGuard, path: String, internal: Boolean): GuardedPath =
        if (internal) guard.guardForInternalRead(path) else guard.guardForRead(path)

    private fun writeGuarded(guard: FileGuard, path: String, internal: Boolean): GuardedPath =
        if (internal) guard.guardForInternalWrite(path) else guard.guardForWrite(path)

    private fun createGuarded(
        guard: FileGuard,
        parentPath: String,
        name: String,
        internal: Boolean
    ): CreationPlan =
        if (internal) guard.prepareInternalCreate(parentPath, name) else guard.prepareCreate(parentPath, name)
}
