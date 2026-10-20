package com.aster.files.integrity

import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityTask
import com.aster.files.data.Repository

/** 完整性任务与结果的持久化抽象；生产实现写 SQLite，测试使用内存实现。 */
interface IntegrityStore {
    suspend fun insertTask(task: IntegrityTask)
    suspend fun updateTask(task: IntegrityTask): Boolean
    suspend fun getTask(taskId: String): IntegrityTask?
    suspend fun updateTaskProgress(
        taskId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean

    suspend fun requestCancellation(taskId: String, requested: Boolean = true): Boolean
    suspend fun isCancellationRequested(taskId: String): Boolean
    suspend fun insertResult(result: IntegrityResult)
    suspend fun listResults(taskId: String): List<IntegrityResult>
    suspend fun listRecentTasks(limit: Int = 20): List<IntegrityTask>
    suspend fun markUnfinishedTasksFailed(error: String): Int
    suspend fun cleanupFinishedTasksBefore(createdBeforeMillis: Long): Int
    suspend fun clearFinishedTasks(): Int
}

/** 生产实现：复用 Phase 1 的 Repository，不新增 schema。 */
class RepositoryIntegrityStore(
    private val repository: Repository
) : IntegrityStore {

    override suspend fun insertTask(task: IntegrityTask) {
        repository.insertIntegrityTask(task)
    }

    override suspend fun updateTask(task: IntegrityTask): Boolean =
        repository.updateIntegrityTask(task)

    override suspend fun getTask(taskId: String): IntegrityTask? =
        repository.getIntegrityTask(taskId)

    override suspend fun updateTaskProgress(
        taskId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean = repository.updateTaskProgress(taskId, processedCount, currentFile)

    override suspend fun requestCancellation(taskId: String, requested: Boolean): Boolean =
        repository.requestTaskCancellation(taskId, requested)

    override suspend fun isCancellationRequested(taskId: String): Boolean =
        repository.isTaskCancellationRequested(taskId)

    override suspend fun insertResult(result: IntegrityResult) {
        repository.insertIntegrityResult(result)
    }

    override suspend fun listResults(taskId: String): List<IntegrityResult> =
        repository.listIntegrityResults(taskId)

    override suspend fun listRecentTasks(limit: Int): List<IntegrityTask> =
        repository.listRecentTasks(limit)

    override suspend fun markUnfinishedTasksFailed(error: String): Int =
        repository.markUnfinishedTasksFailed(error)

    override suspend fun cleanupFinishedTasksBefore(createdBeforeMillis: Long): Int =
        repository.cleanupFinishedIntegrityTasks(createdBeforeMillis)

    override suspend fun clearFinishedTasks(): Int =
        repository.clearFinishedIntegrityTasks()
}
