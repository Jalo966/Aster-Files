package com.aster.files.integrity

import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.CheckLevel
import com.aster.files.data.IntegrityReason
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.IntegrityTask
import com.aster.files.data.MediaType
import com.aster.files.data.ReasonCode
import com.aster.files.data.ReasonLevel
import com.aster.files.data.TaskStatus
import com.aster.files.data.TriggerType
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.services.AuditTrail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 手动照片/视频完整性分析。
 *
 * 只有显式传入用户确认范围 [IntegrityScope] 才会创建任务，trigger_type 固定 MANUAL。
 * 扫描逐文件持久化结果，支持协作式取消；L3 解码通过 [DecodeVerifier] 抽象，
 * 生产使用 AndroidMediaDecodeVerifier，JVM 测试使用 Mock。
 */
class ManualIntegrityService(
    private val guard: FileGuard,
    private val store: IntegrityStore,
    private val decodeVerifier: DecodeVerifier,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val maxScopeFiles: Int = DEFAULT_MAX_SCOPE_FILES
) {

    init {
        require(maxScopeFiles > 0) { "maxScopeFiles 必须大于 0" }
    }

    // ---------------------------------------------------------------------
    // 任务创建与执行
    // ---------------------------------------------------------------------

    /** 创建 PENDING 任务；必须传入非空且经过用户确认的范围。 */
    suspend fun createTask(scope: IntegrityScope): IntegrityTask = withContext(ioDispatcher) {
        if (scope.isEmpty) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_EMPTY,
                "请选择文件或目录后再开始扫描"
            )
        }
        val resolvedFiles = resolveScope(scope)
        if (resolvedFiles.isEmpty()) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_EMPTY,
                "所选范围内没有支持的照片或视频"
            )
        }
        if (resolvedFiles.size > maxScopeFiles) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_TOO_LARGE,
                "单次最多扫描 " + maxScopeFiles + " 个文件"
            )
        }

        val task = IntegrityTask(
            taskId = idGenerator(),
            scopeJson = IntegrityScopeCodec.encode(scope, resolvedFiles),
            createdAt = clock(),
            triggerType = TriggerType.MANUAL,
            status = TaskStatus.PENDING,
            startedAt = null,
            finishedAt = null,
            totalCount = resolvedFiles.size,
            processedCount = 0,
            currentFile = null,
            cancelRequested = false,
            error = null
        )
        store.insertTask(task)
        task
    }

    /** 便捷入口：创建后立即执行。 */
    suspend fun startTask(scope: IntegrityScope): IntegrityTask {
        val task = createTask(scope)
        return runTask(task.taskId)
    }

    /** 执行 PENDING 任务；逐文件持久化结果并更新 processedCount/currentFile。 */
    suspend fun runTask(taskId: String): IntegrityTask = withContext(ioDispatcher) {
        val loaded = store.getTask(taskId)
            ?: throw IntegrityServiceException(
                IntegrityServiceErrorCodes.TASK_NOT_FOUND,
                "完整性任务不存在"
            )
        if (loaded.triggerType != TriggerType.MANUAL) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.TASK_TRIGGER_INVALID,
                "仅支持手动触发的完整性任务"
            )
        }
        if (loaded.status != TaskStatus.PENDING) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.TASK_NOT_PENDING,
                "任务不是待执行状态"
            )
        }

        val persisted = try {
            IntegrityScopeCodec.decode(loaded.scopeJson)
        } catch (e: Exception) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.TASK_INVALID,
                "任务范围数据损坏",
                e
            )
        }
        if (persisted.resolvedFiles.isEmpty()) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.TASK_INVALID,
                "任务没有可扫描文件"
            )
        }

        var task = loaded.copy(
            status = TaskStatus.RUNNING,
            startedAt = clock(),
            finishedAt = null,
            totalCount = persisted.resolvedFiles.size,
            processedCount = 0,
            currentFile = null,
            cancelRequested = false,
            error = null
        )
        store.updateTask(task)

        var processed = 0
        var cancelled = false

        try {
            for (path in persisted.resolvedFiles) {
                if (store.isCancellationRequested(taskId)) {
                    cancelled = true
                    break
                }
                task = task.copy(currentFile = path)
                store.updateTaskProgress(taskId, processed, path)

                val result = processFile(taskId, path)
                store.insertResult(result)
                processed++
                task = task.copy(processedCount = processed)
                store.updateTaskProgress(taskId, processed, null)

                if (store.isCancellationRequested(taskId)) {
                    cancelled = true
                    break
                }
            }

            if (cancelled) {
                store.requestCancellation(taskId, true)
            }
            val finalStatus = if (cancelled) TaskStatus.CANCELLED else TaskStatus.COMPLETED
            task = task.copy(
                status = finalStatus,
                finishedAt = clock(),
                processedCount = processed,
                currentFile = null,
                cancelRequested = cancelled,
                error = null
            )
            store.updateTask(task)
            auditScan(task, finalStatus, null)
            task
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.requestCancellation(taskId, true)
                val cancelledTask = task.copy(
                    status = TaskStatus.CANCELLED,
                    finishedAt = clock(),
                    processedCount = processed,
                    currentFile = null,
                    cancelRequested = true
                )
                store.updateTask(cancelledTask)
                auditScan(cancelledTask, TaskStatus.CANCELLED, "任务被取消")
            }
            throw e
        } catch (e: Exception) {
            val failedTask = task.copy(
                status = TaskStatus.FAILED,
                finishedAt = clock(),
                processedCount = processed,
                currentFile = null,
                cancelRequested = false,
                error = e.message ?: "任务执行失败"
            )
            store.updateTask(failedTask)
            auditScan(failedTask, TaskStatus.FAILED, failedTask.error)
            if (e is IntegrityServiceException) throw e
            throw IntegrityServiceException(
                "TASK_FAILED",
                e.message ?: "任务执行失败",
                e
            )
        }
    }

    /** UI 取消入口：运行中的扫描会在当前文件结束后停止，已完成结果保留。 */
    suspend fun requestCancel(taskId: String): Boolean = withContext(ioDispatcher) {
        store.requestCancellation(taskId, true)
    }

    suspend fun listResults(taskId: String): List<IntegrityResult> = withContext(ioDispatcher) {
        store.listResults(taskId)
    }

    /** 进程重启恢复：把 PENDING/RUNNING 任务标记为 FAILED，不自动重扫。 */
    suspend fun recoverInterruptedTasks(
        error: String = "进程中断，任务未完成"
    ): Int = withContext(ioDispatcher) {
        store.markUnfinishedTasksFailed(error)
    }

    // ---------------------------------------------------------------------
    // 范围解析
    // ---------------------------------------------------------------------

    private fun resolveScope(scope: IntegrityScope): List<String> {
        val resolved = LinkedHashSet<String>()
        try {
            for (path in scope.filePaths) {
                val guarded = guard.guardForRead(path)
                if (guarded.isSymlink) continue
                if (guarded.isDirectory) {
                    collectDirectory(
                        guarded.file,
                        scope.recursiveDirectories,
                        resolved,
                        scope.mediaTypes
                    )
                } else {
                    addSupportedFile(guarded, resolved, scope.mediaTypes)
                }
            }
            for (path in scope.directoryPaths) {
                val guarded = guard.guardForRead(path)
                if (!guarded.isDirectory) {
                    throw IntegrityServiceException(
                        IntegrityServiceErrorCodes.SCOPE_INVALID,
                        "扫描范围中的目录不存在或不是目录"
                    )
                }
                if (guarded.isSymlink) continue
                collectDirectory(
                    guarded.file,
                    scope.recursiveDirectories,
                    resolved,
                    scope.mediaTypes
                )
            }
        } catch (e: FileGuardException) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_INVALID,
                e.message ?: "扫描范围非法",
                e
            )
        }
        return resolved.sorted()
    }

    private fun collectDirectory(
        directory: File,
        recursive: Boolean,
        resolved: MutableSet<String>,
        mediaTypes: Set<MediaType>
    ) {
        val queue = ArrayDeque<File>()
        queue.add(directory)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                val guarded = try {
                    guard.guardForRead(child.absolutePath)
                } catch (e: FileGuardException) {
                    // 目录扫描跳过符号链接、内部路径与断链。
                    continue
                }
                if (guarded.isSymlink) continue
                if (guarded.isDirectory) {
                    if (recursive) queue.add(guarded.file)
                } else {
                    addSupportedFile(guarded, resolved, mediaTypes)
                }
            }
        }
    }

    private fun addSupportedFile(
        guarded: com.aster.files.security.GuardedPath,
        resolved: MutableSet<String>,
        mediaTypes: Set<MediaType>
    ) {
        val mediaType = MediaFormats.mediaTypeFor(guarded.file.name) ?: return
        if (mediaTypes.isNotEmpty() && mediaType !in mediaTypes) return
        resolved.add(guarded.canonicalPath)
        if (resolved.size > maxScopeFiles) {
            throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_TOO_LARGE,
                "单次最多扫描 " + maxScopeFiles + " 个文件"
            )
        }
    }

    // ---------------------------------------------------------------------
    // 单文件检测
    // ---------------------------------------------------------------------

    private fun processFile(taskId: String, filePath: String): IntegrityResult {
        val startedNanos = System.nanoTime()
        val fileName = File(filePath).name
        val mediaType = MediaFormats.mediaTypeFor(fileName)
            ?: throw IntegrityServiceException(
                IntegrityServiceErrorCodes.SCOPE_INVALID,
                "不支持的文件类型"
            )

        var sizeBytes = 0L
        var hashBefore: String? = null
        var hashAfter: String? = null
        var readOnlyVerified = false
        var status = IntegrityStatus.NORMAL
        val reasons = ArrayList<IntegrityReason>()
        val checks = LinkedHashSet<CheckLevel>()

        try {
            val guarded = guard.guardForRead(filePath)
            sizeBytes = guarded.sizeBytes
            hashBefore = sha256(guarded.file)

            if (sizeBytes == 0L) {
                checks.add(CheckLevel.L1_METADATA)
                reasons.add(reason(ReasonCode.ZERO_BYTE, "文件为 0 字节"))
                status = IntegrityStatus.UNREADABLE
                hashAfter = sha256(guarded.file)
            } else {
                val probe = MediaProbe.inspect(guarded.file, mediaType)
                checks.addAll(probe.checksPerformed)
                reasons.addAll(probe.reasons)

                if (probe.reasons.isEmpty()) {
                    checks.add(CheckLevel.L3_DECODE)
                    val decoded = try {
                        decodeVerifier.verify(guarded.file)
                    } catch (e: Exception) {
                        reasons.add(reason(ReasonCode.DECODE_FAILED, e.message ?: "解码失败"))
                        false
                    }
                    if (!decoded) {
                        if (reasons.none { it.code == ReasonCode.DECODE_FAILED }) {
                            reasons.add(reason(ReasonCode.DECODE_FAILED, "解码未解出有效帧"))
                        }
                        status = IntegrityStatus.SUSPECTED_CORRUPT
                    }
                } else {
                    status = if (probe.reasons.any { it.code == ReasonCode.ZERO_BYTE }) {
                        IntegrityStatus.UNREADABLE
                    } else {
                        IntegrityStatus.SUSPECTED_CORRUPT
                    }
                }

                hashAfter = sha256(guarded.file)
            }

            if (hashBefore != null && hashAfter != null) {
                readOnlyVerified = hashBefore == hashAfter
                if (!readOnlyVerified) {
                    if (reasons.none { it.code == ReasonCode.READ_ERROR }) {
                        reasons.add(reason(ReasonCode.READ_ERROR, "扫描前后哈希不一致"))
                    }
                    status = IntegrityStatus.SUSPECTED_CORRUPT
                }
            } else {
                readOnlyVerified = false
                if (reasons.none { it.code == ReasonCode.READ_ERROR }) {
                    reasons.add(reason(ReasonCode.READ_ERROR, "无法完成只读哈希校验"))
                }
                status = IntegrityStatus.UNREADABLE
            }
        } catch (e: Exception) {
            if (reasons.none { it.code == ReasonCode.READ_ERROR }) {
                reasons.add(reason(ReasonCode.READ_ERROR, e.message ?: "读取文件失败"))
            }
            status = IntegrityStatus.UNREADABLE
            readOnlyVerified = false
        }

        if (status == IntegrityStatus.SUSPECTED_CORRUPT && reasons.isEmpty()) {
            reasons.add(reason(ReasonCode.READ_ERROR, "检测失败"))
        }

        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
        return IntegrityResult(
            resultId = idGenerator(),
            taskId = taskId,
            fileId = filePath,
            filePath = filePath,
            fileName = fileName,
            mediaType = mediaType,
            status = status,
            reasonsJson = AsterJsonCodec.encodeReasons(reasons),
            checksPerformedJson = AsterJsonCodec.encodeChecks(checks.toList()),
            detectedAt = clock(),
            sizeBytes = sizeBytes,
            hashBefore = hashBefore,
            hashAfter = hashAfter,
            readOnlyVerified = readOnlyVerified,
            durationMs = durationMs
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest()
        val digits = "0123456789abcdef"
        val result = CharArray(hash.size * 2)
        for (index in hash.indices) {
            val value = hash[index].toInt() and 0xFF
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(result)
    }

    private fun reason(code: ReasonCode, detail: String): IntegrityReason =
        IntegrityReason(code, detail, ReasonLevel.ERROR)

    private suspend fun auditScan(
        task: IntegrityTask,
        status: TaskStatus,
        error: String?
    ) {
        val auditStatus = when (status) {
            TaskStatus.COMPLETED -> AuditStatus.SUCCESS
            TaskStatus.CANCELLED -> AuditStatus.CANCELLED
            else -> AuditStatus.FAILED
        }
        try {
            audit.record(
                category = AuditCategory.INTEGRITY_TASK,
                operation = "INTEGRITY_SCAN",
                status = auditStatus,
                taskId = task.taskId,
                reason = error
            )
        } catch (_: Exception) {
            // 审计失败不改变已经持久化的任务结果。
        }
    }

    companion object {
        const val DEFAULT_MAX_SCOPE_FILES = 100_000
    }
}
