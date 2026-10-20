package com.aster.files.support

import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityTask
import com.aster.files.data.TaskStatus
import com.aster.files.integrity.IntegrityStore

/** JVM 测试用完整性任务/结果存储。 */
class InMemoryIntegrityStore : IntegrityStore {
    private val tasks = LinkedHashMap<String, IntegrityTask>()
    private val results = LinkedHashMap<String, MutableList<IntegrityResult>>()
    private val cancellations = HashSet<String>()

    var cancelOnFirstResult = false
    val progressHistory = ArrayList<Pair<Int, String?>>()

    override suspend fun insertTask(task: IntegrityTask) {
        tasks[task.taskId] = task
    }

    override suspend fun updateTask(task: IntegrityTask): Boolean {
        if (!tasks.containsKey(task.taskId)) return false
        tasks[task.taskId] = task
        return true
    }

    override suspend fun getTask(taskId: String): IntegrityTask? = tasks[taskId]

    override suspend fun updateTaskProgress(
        taskId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean {
        val task = tasks[taskId] ?: return false
        tasks[taskId] = task.copy(
            processedCount = processedCount,
            currentFile = currentFile
        )
        progressHistory.add(processedCount to currentFile)
        return true
    }

    override suspend fun requestCancellation(taskId: String, requested: Boolean): Boolean {
        if (!tasks.containsKey(taskId)) return false
        if (requested) cancellations.add(taskId) else cancellations.remove(taskId)
        return true
    }

    override suspend fun isCancellationRequested(taskId: String): Boolean =
        taskId in cancellations

    override suspend fun insertResult(result: IntegrityResult) {
        results.getOrPut(result.taskId) { mutableListOf() }.add(result)
        if (cancelOnFirstResult) cancellations.add(result.taskId)
    }

    override suspend fun listResults(taskId: String): List<IntegrityResult> =
        results[taskId]?.toList() ?: emptyList()

    override suspend fun listRecentTasks(limit: Int): List<IntegrityTask> =
        tasks.values.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun cleanupFinishedTasksBefore(createdBeforeMillis: Long): Int {
        val ids = tasks.values
            .filter {
                (it.status == TaskStatus.COMPLETED || it.status == TaskStatus.CANCELLED) &&
                    it.createdAt < createdBeforeMillis
            }
            .map { it.taskId }
        ids.forEach {
            tasks.remove(it)
            results.remove(it)
        }
        return ids.size
    }

    override suspend fun clearFinishedTasks(): Int {
        val ids = tasks.values
            .filter {
                it.status == TaskStatus.COMPLETED ||
                    it.status == TaskStatus.CANCELLED ||
                    it.status == TaskStatus.FAILED
            }
            .map { it.taskId }
        ids.forEach {
            tasks.remove(it)
            results.remove(it)
        }
        return ids.size
    }

    override suspend fun markUnfinishedTasksFailed(error: String): Int {
        var count = 0
        for ((taskId, task) in tasks.entries.toList()) {
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                tasks[taskId] = task.copy(
                    status = TaskStatus.FAILED,
                    finishedAt = 1L,
                    error = error
                )
                count++
            }
        }
        return count
    }

    fun snapshotTasks(): List<IntegrityTask> = tasks.values.toList()

    fun resultsFor(taskId: String): List<IntegrityResult> =
        results[taskId]?.toList() ?: emptyList()
}
