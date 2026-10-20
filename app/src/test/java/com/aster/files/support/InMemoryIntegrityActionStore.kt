package com.aster.files.support

import com.aster.files.data.ActionJobStatus
import com.aster.files.data.IntegrityActionJob
import com.aster.files.integrity.IntegrityActionStore

/** JVM 测试用动作任务存储。 */
class InMemoryIntegrityActionStore : IntegrityActionStore {
    private val jobs = LinkedHashMap<String, IntegrityActionJob>()
    private val cancellations = HashSet<String>()

    var cancelOnFirstProgress = false
    val progressHistory = ArrayList<Pair<Int, String?>>()

    override suspend fun insertJob(job: IntegrityActionJob) {
        jobs[job.actionJobId] = job
    }

    override suspend fun updateJob(job: IntegrityActionJob): Boolean {
        if (!jobs.containsKey(job.actionJobId)) return false
        jobs[job.actionJobId] = job
        return true
    }

    override suspend fun getJob(actionJobId: String): IntegrityActionJob? = jobs[actionJobId]

    override suspend fun updateJobProgress(
        actionJobId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean {
        val job = jobs[actionJobId] ?: return false
        jobs[actionJobId] = job.copy(
            processedCount = processedCount,
            currentFile = currentFile
        )
        progressHistory.add(processedCount to currentFile)
        if (cancelOnFirstProgress) cancellations.add(actionJobId)
        return true
    }

    override suspend fun requestCancellation(actionJobId: String, requested: Boolean): Boolean {
        if (!jobs.containsKey(actionJobId)) return false
        if (requested) cancellations.add(actionJobId) else cancellations.remove(actionJobId)
        return true
    }

    override suspend fun isCancellationRequested(actionJobId: String): Boolean =
        actionJobId in cancellations

    override suspend fun listJobs(taskId: String?): List<IntegrityActionJob> =
        jobs.values.filter { taskId == null || it.taskId == taskId }

    override suspend fun markUnfinishedJobsFailed(error: String): Int {
        var count = 0
        for ((id, job) in jobs.entries.toList()) {
            if (job.status == ActionJobStatus.PENDING || job.status == ActionJobStatus.RUNNING) {
                jobs[id] = job.copy(
                    status = ActionJobStatus.FAILED,
                    finishedAt = 1L,
                    error = error
                )
                count++
            }
        }
        return count
    }

    fun snapshotJobs(): List<IntegrityActionJob> = jobs.values.toList()
}
