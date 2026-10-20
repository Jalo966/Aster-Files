package com.aster.files.integrity

import com.aster.files.data.ActionJobStatus
import com.aster.files.data.ActionOperation
import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.IntegrityActionJob
import com.aster.files.data.IntegrityActionRequest
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.IntegrityTask
import com.aster.files.data.TaskStatus
import com.aster.files.data.TriggerType
import com.aster.files.security.FileGuard
import com.aster.files.services.AuditTrail
import com.aster.files.services.FileTransferSupport
import com.aster.files.services.TrashService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 失效文件专用操作处理器。
 *
 * 只接受完整性任务的结果；NORMAL 混选整体拒绝。DELETE 仅走 TrashService 软删除，
 * COPY/MOVE 目标必须经过 FileGuard。执行前必须 preview + confirmed=true，
 * 逐项执行并审计；取消时当前项完成后停止，已完成项不回滚。
 */
class IntegrityActionHandler(
    private val guard: FileGuard,
    private val integrityStore: IntegrityStore,
    private val actionStore: IntegrityActionStore,
    private val trashService: TrashService,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxItems: Int = MAX_ITEMS
) {

    init {
        require(maxItems > 0) { "maxItems 必须大于 0" }
    }

    suspend fun preview(request: IntegrityActionRequest): IntegrityActionPreview =
        withContext(ioDispatcher) {
            buildPreview(request, validate(request))
        }

    /**
     * 执行动作。
     *
     * @param confirmed 必须为 true；UI 在用户二次确认后传入。
     * @param onJobStarted 任务持久化后回调 actionJobId，供 UI 取消。
     */
    suspend fun execute(
        preview: IntegrityActionPreview,
        confirmed: Boolean,
        onJobStarted: ((String) -> Unit)? = null
    ): IntegrityActionResult = withContext(ioDispatcher) {
        if (!confirmed) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.CONFIRMATION_REQUIRED,
                "请先确认操作预览"
            )
        }
        val validated = validate(preview.request)
        val actionJobId = idGenerator()
        val actionItems = validated.results.map {
            ActionItem(it.resultId, it.filePath, ITEM_PENDING, null)
        }.toMutableList()

        var job = IntegrityActionJob(
            actionJobId = actionJobId,
            taskId = validated.task.taskId,
            operation = preview.request.operation,
            status = ActionJobStatus.PENDING,
            totalCount = validated.results.size,
            processedCount = 0,
            currentFile = null,
            targetDirectory = validated.targetDisplay,
            cancelRequested = false,
            startedAt = clock(),
            finishedAt = null,
            error = null,
            itemsJson = encodeItems(actionItems)
        )
        actionStore.insertJob(job)
        job = job.copy(status = ActionJobStatus.RUNNING)
        actionStore.updateJob(job)
        runCatching { onJobStarted?.invoke(actionJobId) }

        var processed = 0
        var cancelled = false
        var failedCount = 0

        try {
            for (index in validated.results.indices) {
                if (actionStore.isCancellationRequested(actionJobId)) {
                    cancelled = true
                    break
                }
                val result = validated.results[index]
                actionStore.updateJobProgress(actionJobId, processed, result.filePath)

                val outcome = perform(validated, result)
                actionItems[index].status = if (outcome.success) ITEM_SUCCESS else ITEM_FAILED
                actionItems[index].error = outcome.error
                processed++
                if (!outcome.success) failedCount++

                job = job.copy(
                    processedCount = processed,
                    currentFile = null,
                    itemsJson = encodeItems(actionItems)
                )
                actionStore.updateJob(job)
                auditItem(validated, result, outcome)

                if (actionStore.isCancellationRequested(actionJobId)) {
                    cancelled = true
                    break
                }
            }

            if (cancelled) {
                for (index in actionItems.indices) {
                    if (actionItems[index].status == ITEM_PENDING) {
                        actionItems[index].status = ITEM_CANCELLED
                        actionItems[index].error = "操作已取消"
                        auditCancelled(validated, validated.results[index])
                    }
                }
            }

            val status = if (cancelled) ActionJobStatus.CANCELLED else ActionJobStatus.COMPLETED
            val error = if (failedCount > 0) "有 " + failedCount + " 项失败" else null
            job = job.copy(
                status = status,
                processedCount = processed,
                currentFile = null,
                finishedAt = clock(),
                cancelRequested = cancelled,
                error = error,
                itemsJson = encodeItems(actionItems)
            )
            actionStore.updateJob(job)
            IntegrityActionResult(
                actionJobId = actionJobId,
                status = status,
                operation = preview.request.operation,
                totalCount = validated.results.size,
                processedCount = processed,
                error = error
            )
        } catch (e: Exception) {
            val failedJob = job.copy(
                status = ActionJobStatus.FAILED,
                processedCount = processed,
                currentFile = null,
                finishedAt = clock(),
                error = e.message ?: "动作执行失败",
                itemsJson = encodeItems(actionItems)
            )
            runCatching { actionStore.updateJob(failedJob) }
            IntegrityActionResult(
                actionJobId = actionJobId,
                status = ActionJobStatus.FAILED,
                operation = preview.request.operation,
                totalCount = validated.results.size,
                processedCount = processed,
                error = failedJob.error
            )
        }
    }

    suspend fun requestCancel(actionJobId: String): Boolean = withContext(ioDispatcher) {
        actionStore.requestCancellation(actionJobId, true)
    }

    suspend fun listJobs(taskId: String? = null): List<IntegrityActionJob> =
        withContext(ioDispatcher) {
            actionStore.listJobs(taskId)
        }

    suspend fun recoverInterruptedActions(
        error: String = "进程中断，动作未完成"
    ): Int = withContext(ioDispatcher) {
        actionStore.markUnfinishedJobsFailed(error)
    }

    // ---------------------------------------------------------------------
    // 校验与预览
    // ---------------------------------------------------------------------

    private suspend fun validate(request: IntegrityActionRequest): Validated {
        if (request.resultIds.isEmpty()) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.INVALID_REQUEST,
                "请选择要操作的文件"
            )
        }
        val ids = request.resultIds.distinct()
        if (ids.size > maxItems) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.TOO_MANY_ITEMS,
                "单次最多操作 " + maxItems + " 项"
            )
        }

        val task = integrityStore.getTask(request.taskId)
            ?: throw IntegrityActionException(
                IntegrityActionErrorCodes.TASK_NOT_FOUND,
                "完整性任务不存在"
            )
        if (task.triggerType != TriggerType.MANUAL) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.TASK_INVALID,
                "仅支持手动完整性任务"
            )
        }
        if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.TASK_RUNNING,
                "任务运行中，暂不能操作失效文件"
            )
        }

        val allResults = integrityStore.listResults(task.taskId)
            .associateBy { it.resultId }
        val results = ids.map { resultId ->
            allResults[resultId]
                ?: throw IntegrityActionException(
                    IntegrityActionErrorCodes.RESULT_NOT_FOUND,
                    "完整性结果不存在或不属于该任务"
                )
        }
        if (results.any { it.status == IntegrityStatus.NORMAL }) {
            throw IntegrityActionException(
                IntegrityActionErrorCodes.MIXED_NORMAL,
                IntegrityActionErrorCodes.MIXED_NORMAL_MESSAGE
            )
        }

        var targetDirectory: String? = null
        val targetDisplay: String
        when (request.operation) {
            ActionOperation.DELETE -> {
                if (!request.softDelete) {
                    throw IntegrityActionException(
                        IntegrityActionErrorCodes.SOFT_DELETE_REQUIRED,
                        "仅支持软删除"
                    )
                }
                targetDisplay = trashService.trashFilesPath
            }
            ActionOperation.COPY, ActionOperation.MOVE -> {
                val target = request.targetDirectory
                    ?: throw IntegrityActionException(
                        IntegrityActionErrorCodes.TARGET_INVALID,
                        "请选择目标目录"
                    )
                val guardedTarget = try {
                    guard.guardTargetDirectory(target)
                } catch (e: Exception) {
                    throw IntegrityActionException(
                        IntegrityActionErrorCodes.TARGET_INVALID,
                        e.message ?: "目标目录非法",
                        e
                    )
                }
                if (guardedTarget.isSymlink) {
                    throw IntegrityActionException(
                        IntegrityActionErrorCodes.TARGET_INVALID,
                        "目标目录不能是符号链接"
                    )
                }
                targetDirectory = guardedTarget.canonicalPath
                targetDisplay = guardedTarget.canonicalPath
            }
        }

        return Validated(
            task = task,
            results = results,
            operation = request.operation,
            targetDirectory = targetDirectory,
            targetDisplay = targetDisplay
        )
    }

    private fun buildPreview(
        request: IntegrityActionRequest,
        validated: Validated
    ): IntegrityActionPreview {
        val items = validated.results.map { result ->
            IntegrityActionPreviewItem(
                resultId = result.resultId,
                filePath = result.filePath,
                fileName = result.fileName,
                status = result.status,
                sizeBytes = result.sizeBytes,
                reasons = AsterJsonCodec.decodeReasons(result.reasonsJson)
            )
        }
        var totalBytes = 0L
        for (item in items) {
            totalBytes = if (Long.MAX_VALUE - totalBytes < item.sizeBytes) {
                Long.MAX_VALUE
            } else {
                totalBytes + item.sizeBytes
            }
        }
        val message = when (validated.operation) {
            ActionOperation.DELETE ->
                "将 " + items.size + " 项移入回收站，共 " + totalBytes + " 字节"
            ActionOperation.COPY ->
                "复制 " + items.size + " 项到 " + validated.targetDisplay
            ActionOperation.MOVE ->
                "移动 " + items.size + " 项到 " + validated.targetDisplay
        }
        return IntegrityActionPreview(
            request = request.copy(
                resultIds = validated.results.map { it.resultId },
                targetDirectory = validated.targetDirectory
            ),
            items = items,
            totalBytes = totalBytes,
            targetDirectory = validated.targetDirectory,
            targetDisplayPath = validated.targetDisplay,
            message = message
        )
    }

    // ---------------------------------------------------------------------
    // 执行单项
    // ---------------------------------------------------------------------

    private suspend fun perform(validated: Validated, result: IntegrityResult): ItemOutcome {
        return try {
            when (validated.operation) {
                ActionOperation.DELETE -> {
                    val operationResult = trashService.softDelete(result.filePath)
                    if (operationResult.success) {
                        ItemOutcome(true, operationResult.targetPath, null)
                    } else {
                        ItemOutcome(false, operationResult.targetPath, operationResult.message)
                    }
                }
                ActionOperation.COPY -> {
                    val target = validated.targetDirectory
                        ?: return ItemOutcome(false, null, "缺少目标目录")
                    val plan = guard.prepareCopy(result.filePath, target, result.fileName)
                    guard.verifyCopyPlan(plan)
                    try {
                        FileTransferSupport.copyTree(guard, plan.source, plan.target)
                        FileTransferSupport.verifyCreated(guard, plan.target)
                        ItemOutcome(true, plan.target.targetPath, null)
                    } catch (e: Exception) {
                        FileTransferSupport.cleanupTarget(guard, plan.target)
                        ItemOutcome(false, plan.target.targetPath, e.message ?: "复制失败")
                    }
                }
                ActionOperation.MOVE -> {
                    val target = validated.targetDirectory
                        ?: return ItemOutcome(false, null, "缺少目标目录")
                    val plan = guard.prepareMove(result.filePath, target, result.fileName)
                    guard.verifyMovePlan(plan)
                    FileTransferSupport.moveOrCopy(guard, plan.source, plan.target)
                    FileTransferSupport.verifyCreated(guard, plan.target)
                    ItemOutcome(true, plan.target.targetPath, null)
                }
            }
        } catch (e: Exception) {
            ItemOutcome(false, null, e.message ?: "操作失败")
        }
    }

    // ---------------------------------------------------------------------
    // 审计与 itemsJson
    // ---------------------------------------------------------------------

    private suspend fun auditItem(
        validated: Validated,
        result: IntegrityResult,
        outcome: ItemOutcome
    ) {
        try {
            audit.record(
                category = AuditCategory.INTEGRITY_ACTION,
                operation = validated.operation.name,
                status = if (outcome.success) AuditStatus.SUCCESS else AuditStatus.FAILED,
                taskId = validated.task.taskId,
                fileId = result.fileId,
                sourcePath = result.filePath,
                targetPath = outcome.targetPath ?: validated.targetDisplay,
                reason = outcome.error ?: firstReason(result)
            )
        } catch (_: Exception) {
            // 审计失败不中止文件操作。
        }
    }

    private suspend fun auditCancelled(validated: Validated, result: IntegrityResult) {
        try {
            audit.record(
                category = AuditCategory.INTEGRITY_ACTION,
                operation = validated.operation.name,
                status = AuditStatus.CANCELLED,
                taskId = validated.task.taskId,
                fileId = result.fileId,
                sourcePath = result.filePath,
                targetPath = validated.targetDisplay,
                reason = "操作已取消"
            )
        } catch (_: Exception) {
            // 忽略审计写入失败。
        }
    }

    private fun firstReason(result: IntegrityResult): String? =
        AsterJsonCodec.decodeReasons(result.reasonsJson).firstOrNull()?.let {
            it.code.name + ": " + it.detail
        }

    private fun encodeItems(items: List<ActionItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("resultId", item.resultId)
                    put("path", item.path)
                    put("status", item.status)
                    put("error", item.error ?: JSONObject.NULL)
                }
            )
        }
        return array.toString()
    }

    private data class ActionItem(
        val resultId: String,
        val path: String,
        var status: String,
        var error: String?
    )

    private data class ItemOutcome(
        val success: Boolean,
        val targetPath: String?,
        val error: String?
    )

    private data class Validated(
        val task: IntegrityTask,
        val results: List<IntegrityResult>,
        val operation: ActionOperation,
        val targetDirectory: String?,
        val targetDisplay: String
    )

    private companion object {
        const val MAX_ITEMS = 200
        const val ITEM_PENDING = "PENDING"
        const val ITEM_SUCCESS = "SUCCESS"
        const val ITEM_FAILED = "FAILED"
        const val ITEM_CANCELLED = "CANCELLED"
    }
}
