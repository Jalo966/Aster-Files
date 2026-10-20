package com.aster.files.integrity

import com.aster.files.data.IntegrityActionJob
import com.aster.files.data.Repository

/** 失效文件动作任务持久化抽象；生产实现写 integrity_action_jobs。 */
interface IntegrityActionStore {
    suspend fun insertJob(job: IntegrityActionJob)
    suspend fun updateJob(job: IntegrityActionJob): Boolean
    suspend fun getJob(actionJobId: String): IntegrityActionJob?
    suspend fun updateJobProgress(
        actionJobId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean

    suspend fun requestCancellation(actionJobId: String, requested: Boolean = true): Boolean
    suspend fun isCancellationRequested(actionJobId: String): Boolean
    suspend fun listJobs(taskId: String? = null): List<IntegrityActionJob>
    suspend fun markUnfinishedJobsFailed(error: String): Int
}

class RepositoryIntegrityActionStore(
    private val repository: Repository
) : IntegrityActionStore {

    override suspend fun insertJob(job: IntegrityActionJob) {
        repository.insertActionJob(job)
    }

    override suspend fun updateJob(job: IntegrityActionJob): Boolean =
        repository.updateActionJob(job)

    override suspend fun getJob(actionJobId: String): IntegrityActionJob? =
        repository.getActionJob(actionJobId)

    override suspend fun updateJobProgress(
        actionJobId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean = repository.updateActionJobProgress(actionJobId, processedCount, currentFile)

    override suspend fun requestCancellation(actionJobId: String, requested: Boolean): Boolean =
        repository.requestActionJobCancellation(actionJobId, requested)

    override suspend fun isCancellationRequested(actionJobId: String): Boolean =
        repository.isActionJobCancellationRequested(actionJobId)

    override suspend fun listJobs(taskId: String?): List<IntegrityActionJob> =
        repository.listActionJobs(taskId)

    override suspend fun markUnfinishedJobsFailed(error: String): Int =
        repository.markUnfinishedActionJobsFailed(error)
}
