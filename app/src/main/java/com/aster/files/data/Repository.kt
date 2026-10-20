package com.aster.files.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException

/**
 * 数据访问层：对 AsterDatabase 的薄封装，所有操作在 IO 调度器上执行。
 *
 * 只封装 CRUD，不包含业务规则（例如“仅失效文件可操作”由
 * IntegrityActionHandler 负责）。所有 SQL 参数均使用绑定参数。
 */
class Repository(
    private val database: AsterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ---------------------------------------------------------------------
    // meta / compliance_meta
    // ---------------------------------------------------------------------

    suspend fun getMeta(key: String): String? = withContext(ioDispatcher) {
        readKeyValue(AsterDatabase.TABLE_META, key)
    }

    suspend fun putMeta(key: String, value: String) {
        withContext(ioDispatcher) {
            writeKeyValue(AsterDatabase.TABLE_META, key, value)
        }
    }

    suspend fun getAllMeta(): Map<String, String> = withContext(ioDispatcher) {
        readAllKeyValues(AsterDatabase.TABLE_META)
    }

    suspend fun removeMeta(key: String): Boolean = withContext(ioDispatcher) {
        writableDatabase.delete(
            AsterDatabase.TABLE_META,
            "key = ?",
            arrayOf(key)
        ) > 0
    }

    suspend fun getComplianceMeta(key: String): String? = withContext(ioDispatcher) {
        readKeyValue(AsterDatabase.TABLE_COMPLIANCE_META, key)
    }

    suspend fun putComplianceMeta(key: String, value: String) {
        withContext(ioDispatcher) {
            writeKeyValue(AsterDatabase.TABLE_COMPLIANCE_META, key, value)
        }
    }

    suspend fun getAllComplianceMeta(): Map<String, String> = withContext(ioDispatcher) {
        readAllKeyValues(AsterDatabase.TABLE_COMPLIANCE_META)
    }

    // ---------------------------------------------------------------------
    // trash_entries
    // ---------------------------------------------------------------------

    suspend fun insertTrashEntry(entry: TrashEntry) {
        withContext(ioDispatcher) {
            writableDatabase.insertOrThrow(
                AsterDatabase.TABLE_TRASH_ENTRIES,
                null,
                entry.toContentValues()
            )
        }
    }

    suspend fun getTrashEntry(trashId: String): TrashEntry? = withContext(ioDispatcher) {
        readableDatabase.query(
            AsterDatabase.TABLE_TRASH_ENTRIES,
            null,
            "trash_id = ?",
            arrayOf(trashId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTrashEntry() else null
        }
    }

    /**
     * 列回收站记录。
     *
     * @param status null 表示不过滤；默认只看 ACTIVE，避免恢复记录混入回收站页。
     */
    suspend fun listTrashEntries(status: TrashStatus? = TrashStatus.ACTIVE): List<TrashEntry> =
        withContext(ioDispatcher) {
            val selection = status?.let { "status = ?" }
            val args = status?.let { arrayOf(it.name) }
            readableDatabase.query(
                AsterDatabase.TABLE_TRASH_ENTRIES,
                null,
                selection,
                args,
                null,
                null,
                "deleted_at DESC, rowid DESC"
            ).use { cursor ->
                cursor.readAll { it.toTrashEntry() }
            }
        }

    suspend fun findActiveTrashByOriginalPath(originalPath: String): TrashEntry? =
        withContext(ioDispatcher) {
            readableDatabase.query(
                AsterDatabase.TABLE_TRASH_ENTRIES,
                null,
                "original_path = ? AND status = ?",
                arrayOf(originalPath, TrashStatus.ACTIVE.name),
                null,
                null,
                "deleted_at DESC, rowid DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toTrashEntry() else null
            }
        }

    suspend fun updateTrashStatus(
        trashId: String,
        status: TrashStatus,
        restoredAt: Long? = null
    ): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("status", status.name)
            putNullable("restored_at", restoredAt)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_TRASH_ENTRIES,
            values,
            "trash_id = ?",
            arrayOf(trashId)
        ) > 0
    }

    suspend fun restoreTrashEntry(
        trashId: String,
        restoredAt: Long = System.currentTimeMillis()
    ): Boolean = updateTrashStatus(trashId, TrashStatus.RESTORED, restoredAt)

    suspend fun deleteTrashEntry(trashId: String): Boolean = withContext(ioDispatcher) {
        writableDatabase.delete(
            AsterDatabase.TABLE_TRASH_ENTRIES,
            "trash_id = ?",
            arrayOf(trashId)
        ) > 0
    }

    suspend fun getTrashStats(): TrashStats = withContext(ioDispatcher) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) " +
                "FROM trash_entries WHERE status = ?",
            arrayOf(TrashStatus.ACTIVE.name)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                TrashStats(cursor.getInt(0), cursor.getLong(1))
            } else {
                TrashStats(0, 0L)
            }
        }
    }

    // ---------------------------------------------------------------------
    // integrity_tasks
    // ---------------------------------------------------------------------

    suspend fun insertIntegrityTask(task: IntegrityTask) {
        withContext(ioDispatcher) {
            writableDatabase.insertOrThrow(
                AsterDatabase.TABLE_INTEGRITY_TASKS,
                null,
                task.toContentValues()
            )
        }
    }

    suspend fun getIntegrityTask(taskId: String): IntegrityTask? = withContext(ioDispatcher) {
        readableDatabase.query(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toIntegrityTask() else null
        }
    }

    /**
     * 更新任务的非取消字段。
     *
     * cancel_requested 单独由 requestTaskCancellation 更新，避免工作协程用旧对象
     * 覆盖用户在 UI 上刚发出的取消请求。
     */
    suspend fun updateIntegrityTask(task: IntegrityTask): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("scope_json", task.scopeJson)
            put("trigger_type", task.triggerType.name)
            put("status", task.status.name)
            put("created_at", DbTime.encode(task.createdAt))
            putNullable("started_at", task.startedAt)
            putNullable("finished_at", task.finishedAt)
            put("total_count", task.totalCount)
            put("processed_count", task.processedCount)
            putNullable("current_file", task.currentFile)
            putNullable("error", task.error)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            values,
            "task_id = ?",
            arrayOf(task.taskId)
        ) > 0
    }

    suspend fun updateTaskProgress(
        taskId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("processed_count", processedCount)
            putNullable("current_file", currentFile)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            values,
            "task_id = ?",
            arrayOf(taskId)
        ) > 0
    }

    suspend fun requestTaskCancellation(taskId: String, requested: Boolean = true): Boolean =
        withContext(ioDispatcher) {
            val values = ContentValues().apply {
                put("cancel_requested", if (requested) 1 else 0)
            }
            writableDatabase.update(
                AsterDatabase.TABLE_INTEGRITY_TASKS,
                values,
                "task_id = ?",
                arrayOf(taskId)
            ) > 0
        }

    suspend fun isTaskCancellationRequested(taskId: String): Boolean =
        withContext(ioDispatcher) {
            readableDatabase.query(
                AsterDatabase.TABLE_INTEGRITY_TASKS,
                arrayOf("cancel_requested"),
                "task_id = ?",
                arrayOf(taskId),
                null,
                null,
                null
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) != 0
            }
        }

    /**
     * 进程重启时调用：把所有 PENDING/RUNNING 任务标记为 FAILED。
     *
     * 需求 9.8：不得自动重扫，只提示进程中断。返回受影响行数。
     */
    suspend fun markUnfinishedTasksFailed(
        error: String = "进程中断，任务未完成"
    ): Int = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("status", TaskStatus.FAILED.name)
            put("finished_at", DbTime.encode(System.currentTimeMillis()))
            put("error", error)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            values,
            "status IN (?, ?)",
            arrayOf(TaskStatus.PENDING.name, TaskStatus.RUNNING.name)
        )
    }

    /**
     * 自动清理：仅删除超过截止时间的 COMPLETED/CANCELLED 任务；
     * integrity_results 通过外键 ON DELETE CASCADE 一并删除。
     */
    suspend fun cleanupFinishedIntegrityTasks(createdBeforeMillis: Long): Int =
        withContext(ioDispatcher) {
            writableDatabase.delete(
                AsterDatabase.TABLE_INTEGRITY_TASKS,
                "status IN (?, ?) AND created_at < ?",
                arrayOf(
                    TaskStatus.COMPLETED.name,
                    TaskStatus.CANCELLED.name,
                    DbTime.encode(createdBeforeMillis)
                )
            )
        }

    /** 手动清除历史：删除所有已结束任务（完成/取消/失败），结果级联删除。 */
    suspend fun clearFinishedIntegrityTasks(): Int = withContext(ioDispatcher) {
        writableDatabase.delete(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            "status IN (?, ?, ?)",
            arrayOf(
                TaskStatus.COMPLETED.name,
                TaskStatus.CANCELLED.name,
                TaskStatus.FAILED.name
            )
        )
    }

    suspend fun listRecentTasks(limit: Int = 20): List<IntegrityTask> = withContext(ioDispatcher) {
        val safeLimit = limit.coerceIn(1, 200)
        readableDatabase.query(
            AsterDatabase.TABLE_INTEGRITY_TASKS,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC, rowid DESC",
            safeLimit.toString()
        ).use { cursor ->
            cursor.readAll { it.toIntegrityTask() }
        }
    }

    // ---------------------------------------------------------------------
    // integrity_results
    // ---------------------------------------------------------------------

    suspend fun insertIntegrityResult(result: IntegrityResult) {
        withContext(ioDispatcher) {
            writableDatabase.insertOrThrow(
                AsterDatabase.TABLE_INTEGRITY_RESULTS,
                null,
                result.toContentValues()
            )
        }
    }

    /** 批量写入结果；单事务保证要么全部写入，要么全部回滚。 */
    suspend fun insertIntegrityResults(results: List<IntegrityResult>) {
        if (results.isEmpty()) return
        withContext(ioDispatcher) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                results.forEach { result ->
                    db.insertOrThrow(
                        AsterDatabase.TABLE_INTEGRITY_RESULTS,
                        null,
                        result.toContentValues()
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun getIntegrityResult(resultId: String): IntegrityResult? = withContext(ioDispatcher) {
        readableDatabase.query(
            AsterDatabase.TABLE_INTEGRITY_RESULTS,
            null,
            "result_id = ?",
            arrayOf(resultId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toIntegrityResult() else null
        }
    }

    suspend fun listIntegrityResults(
        taskId: String,
        status: IntegrityStatus? = null,
        limit: Int? = null
    ): List<IntegrityResult> = withContext(ioDispatcher) {
        val selection = StringBuilder("task_id = ?")
        val args = mutableListOf(taskId)
        if (status != null) {
            selection.append(" AND status = ?")
            args.add(status.name)
        }
        readableDatabase.query(
            AsterDatabase.TABLE_INTEGRITY_RESULTS,
            null,
            selection.toString(),
            args.toTypedArray(),
            null,
            null,
            "detected_at DESC, rowid DESC",
            limit?.coerceIn(1, 5000)?.toString()
        ).use { cursor ->
            cursor.readAll { it.toIntegrityResult() }
        }
    }

    suspend fun getIntegrityStats(taskId: String): IntegrityStats = withContext(ioDispatcher) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN status = ? THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN status = ? THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN status = ? THEN 1 ELSE 0 END), 0) " +
                "FROM integrity_results WHERE task_id = ?",
            arrayOf(
                IntegrityStatus.NORMAL.name,
                IntegrityStatus.SUSPECTED_CORRUPT.name,
                IntegrityStatus.UNREADABLE.name,
                taskId
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                IntegrityStats(
                    total = cursor.getInt(0),
                    normal = cursor.getInt(1),
                    suspectedCorrupt = cursor.getInt(2),
                    unreadable = cursor.getInt(3)
                )
            } else {
                IntegrityStats()
            }
        }
    }

    // ---------------------------------------------------------------------
    // integrity_action_jobs
    // ---------------------------------------------------------------------

    suspend fun insertActionJob(job: IntegrityActionJob) {
        withContext(ioDispatcher) {
            writableDatabase.insertOrThrow(
                AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
                null,
                job.toContentValues()
            )
        }
    }

    suspend fun getActionJob(actionJobId: String): IntegrityActionJob? = withContext(ioDispatcher) {
        readableDatabase.query(
            AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
            null,
            "action_job_id = ?",
            arrayOf(actionJobId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toActionJob() else null
        }
    }

    /** 更新动作任务的非取消字段；cancel_requested 不在此更新。 */
    suspend fun updateActionJob(job: IntegrityActionJob): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("task_id", job.taskId)
            put("operation", job.operation.name)
            put("status", job.status.name)
            put("total_count", job.totalCount)
            put("processed_count", job.processedCount)
            putNullable("current_file", job.currentFile)
            putNullable("target_directory", job.targetDirectory)
            put("started_at", DbTime.encode(job.startedAt))
            putNullable("finished_at", job.finishedAt)
            putNullable("error", job.error)
            put("items_json", job.itemsJson)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
            values,
            "action_job_id = ?",
            arrayOf(job.actionJobId)
        ) > 0
    }

    suspend fun updateActionJobProgress(
        actionJobId: String,
        processedCount: Int,
        currentFile: String?
    ): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("processed_count", processedCount)
            putNullable("current_file", currentFile)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
            values,
            "action_job_id = ?",
            arrayOf(actionJobId)
        ) > 0
    }

    suspend fun requestActionJobCancellation(
        actionJobId: String,
        requested: Boolean = true
    ): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("cancel_requested", if (requested) 1 else 0)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
            values,
            "action_job_id = ?",
            arrayOf(actionJobId)
        ) > 0
    }

    suspend fun isActionJobCancellationRequested(actionJobId: String): Boolean =
        withContext(ioDispatcher) {
            readableDatabase.query(
                AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
                arrayOf("cancel_requested"),
                "action_job_id = ?",
                arrayOf(actionJobId),
                null,
                null,
                null
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) != 0
            }
        }

    /** 进程重启时把运行中的动作任务标记为 FAILED（需求 10.3）。 */
    suspend fun markUnfinishedActionJobsFailed(
        error: String = "进程中断，动作未完成"
    ): Int = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put("status", ActionJobStatus.FAILED.name)
            put("finished_at", DbTime.encode(System.currentTimeMillis()))
            put("error", error)
        }
        writableDatabase.update(
            AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
            values,
            "status IN (?, ?)",
            arrayOf(ActionJobStatus.PENDING.name, ActionJobStatus.RUNNING.name)
        )
    }

    suspend fun listActionJobs(taskId: String? = null): List<IntegrityActionJob> =
        withContext(ioDispatcher) {
            readableDatabase.query(
                AsterDatabase.TABLE_INTEGRITY_ACTION_JOBS,
                null,
                taskId?.let { "task_id = ?" },
                taskId?.let { arrayOf(it) },
                null,
                null,
                "started_at DESC, rowid DESC"
            ).use { cursor ->
                cursor.readAll { it.toActionJob() }
            }
        }

    // ---------------------------------------------------------------------
    // audit_log
    // ---------------------------------------------------------------------

    suspend fun insertAuditEntry(entry: AuditEntry) {
        withContext(ioDispatcher) {
            writableDatabase.insertOrThrow(
                AsterDatabase.TABLE_AUDIT_LOG,
                null,
                entry.toContentValues()
            )
        }
    }

    suspend fun listAuditEntries(
        limit: Int = 200,
        offset: Int = 0,
        category: AuditCategory? = null,
        taskId: String? = null
    ): List<AuditEntry> = withContext(ioDispatcher) {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (category != null) {
            clauses.add("category = ?")
            args.add(category.name)
        }
        if (taskId != null) {
            clauses.add("task_id = ?")
            args.add(taskId)
        }
        val safeLimit = limit.coerceIn(1, 1000)
        val safeOffset = offset.coerceAtLeast(0)
        readableDatabase.query(
            AsterDatabase.TABLE_AUDIT_LOG,
            null,
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            "timestamp DESC, rowid DESC",
            "$safeOffset,$safeLimit"
        ).use { cursor ->
            cursor.readAll { it.toAuditEntry() }
        }
    }

    /**
     * 轻量审计清理：只保留最近 1000 条且不早于 7 天的记录。
     * 返回删除条数，启动/后台超过 1 小时时调用。
     */
    suspend fun cleanupAuditEntries(
        nowMillis: Long,
        maxAgeDays: Int = 7,
        keepCount: Int = 1000
    ): Int = withContext(ioDispatcher) {
        val safeKeep = keepCount.coerceIn(1, 1000)
        val cutoff = DbTime.encode(
            nowMillis - maxAgeDays.coerceAtLeast(1) * 86_400_000L
        )
        writableDatabase.delete(
            AsterDatabase.TABLE_AUDIT_LOG,
            "timestamp < ? OR rowid NOT IN (" +
                "SELECT rowid FROM audit_log ORDER BY timestamp DESC, rowid DESC LIMIT ?" +
                ")",
            arrayOf(cutoff, safeKeep.toString())
        )
    }

    suspend fun countAuditEntries(category: AuditCategory? = null): Long =
        withContext(ioDispatcher) {
            val selection = category?.let { " WHERE category = ?" } ?: ""
            val args = category?.let { arrayOf(it.name) }
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM audit_log" + selection,
                args
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }

    // ---------------------------------------------------------------------
    // schema
    // ---------------------------------------------------------------------

    /** 导出当前 schema（sqlite_master + PRAGMA），供设置页分享 JSON。 */
    suspend fun exportSchemaJson(): String = withContext(ioDispatcher) {
        database.exportSchemaJson()
    }

    /** 释放底层数据库连接；由容器在进程退出/测试结束时调用。 */
    fun close() {
        database.close()
    }

    // ---------------------------------------------------------------------
    // meta 内部实现
    // ---------------------------------------------------------------------

    private fun readKeyValue(table: String, key: String): String? =
        readableDatabase.query(
            table,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun writeKeyValue(table: String, key: String, value: String) {
        require(key.isNotBlank()) { "key 不能为空" }
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(
            table,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun readAllKeyValues(table: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        readableDatabase.query(
            table,
            arrayOf("key", "value"),
            null,
            null,
            null,
            null,
            "key ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Cursor 映射
    // ---------------------------------------------------------------------

    private fun Cursor.toTrashEntry(): TrashEntry = TrashEntry(
        trashId = getString(getColumnIndexOrThrow("trash_id")),
        originalPath = getString(getColumnIndexOrThrow("original_path")),
        trashPath = getString(getColumnIndexOrThrow("trash_path")),
        name = getString(getColumnIndexOrThrow("name")),
        entryType = EntryType.valueOf(getString(getColumnIndexOrThrow("entry_type"))),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        deletedAt = DbTime.decodeRequired(getString(getColumnIndexOrThrow("deleted_at"))),
        status = TrashStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        restoredAt = DbTime.decode(getStringOrNull("restored_at"))
    )

    private fun Cursor.toIntegrityTask(): IntegrityTask = IntegrityTask(
        taskId = getString(getColumnIndexOrThrow("task_id")),
        scopeJson = getString(getColumnIndexOrThrow("scope_json")),
        triggerType = TriggerType.valueOf(getString(getColumnIndexOrThrow("trigger_type"))),
        status = TaskStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        createdAt = DbTime.decodeRequired(getString(getColumnIndexOrThrow("created_at"))),
        startedAt = DbTime.decode(getStringOrNull("started_at")),
        finishedAt = DbTime.decode(getStringOrNull("finished_at")),
        totalCount = getInt(getColumnIndexOrThrow("total_count")),
        processedCount = getInt(getColumnIndexOrThrow("processed_count")),
        currentFile = getStringOrNull("current_file"),
        cancelRequested = getInt(getColumnIndexOrThrow("cancel_requested")) != 0,
        error = getStringOrNull("error")
    )

    private fun Cursor.toIntegrityResult(): IntegrityResult = IntegrityResult(
        resultId = getString(getColumnIndexOrThrow("result_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        fileId = getString(getColumnIndexOrThrow("file_id")),
        filePath = getString(getColumnIndexOrThrow("file_path")),
        fileName = getString(getColumnIndexOrThrow("file_name")),
        mediaType = MediaType.valueOf(getString(getColumnIndexOrThrow("media_type"))),
        status = IntegrityStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        reasonsJson = getString(getColumnIndexOrThrow("reasons_json")),
        checksPerformedJson = getString(getColumnIndexOrThrow("checks_performed_json")),
        detectedAt = DbTime.decodeRequired(getString(getColumnIndexOrThrow("detected_at"))),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        hashBefore = getStringOrNull("hash_before"),
        hashAfter = getStringOrNull("hash_after"),
        readOnlyVerified = getInt(getColumnIndexOrThrow("read_only_verified")) != 0,
        durationMs = getLong(getColumnIndexOrThrow("duration_ms"))
    )

    private fun Cursor.toActionJob(): IntegrityActionJob = IntegrityActionJob(
        actionJobId = getString(getColumnIndexOrThrow("action_job_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        operation = ActionOperation.valueOf(getString(getColumnIndexOrThrow("operation"))),
        status = ActionJobStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        totalCount = getInt(getColumnIndexOrThrow("total_count")),
        processedCount = getInt(getColumnIndexOrThrow("processed_count")),
        currentFile = getStringOrNull("current_file"),
        targetDirectory = getStringOrNull("target_directory"),
        cancelRequested = getInt(getColumnIndexOrThrow("cancel_requested")) != 0,
        startedAt = DbTime.decodeRequired(getString(getColumnIndexOrThrow("started_at"))),
        finishedAt = DbTime.decode(getStringOrNull("finished_at")),
        error = getStringOrNull("error"),
        itemsJson = getString(getColumnIndexOrThrow("items_json"))
    )

    private fun Cursor.toAuditEntry(): AuditEntry = AuditEntry(
        auditId = getString(getColumnIndexOrThrow("audit_id")),
        timestamp = DbTime.decodeRequired(getString(getColumnIndexOrThrow("timestamp"))),
        category = AuditCategory.valueOf(getString(getColumnIndexOrThrow("category"))),
        operation = getString(getColumnIndexOrThrow("operation")),
        actor = getString(getColumnIndexOrThrow("actor")),
        taskId = getStringOrNull("task_id"),
        fileId = getStringOrNull("file_id"),
        sourcePath = getStringOrNull("source_path"),
        targetPath = getStringOrNull("target_path"),
        reason = getStringOrNull("reason"),
        status = AuditStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        detailJson = getStringOrNull("detail_json")
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private inline fun <T> Cursor.readAll(mapper: (Cursor) -> T): List<T> {
        val items = ArrayList<T>()
        while (moveToNext()) {
            items.add(mapper(this))
        }
        return items
    }

    // ---------------------------------------------------------------------
    // ContentValues 映射
    // ---------------------------------------------------------------------

    private fun TrashEntry.toContentValues(): ContentValues = ContentValues().apply {
        put("trash_id", trashId)
        put("original_path", originalPath)
        put("trash_path", trashPath)
        put("name", name)
        put("entry_type", entryType.name)
        put("size_bytes", sizeBytes)
        put("deleted_at", DbTime.encode(deletedAt))
        putNullable("restored_at", restoredAt)
        put("status", status.name)
    }

    private fun IntegrityTask.toContentValues(): ContentValues = ContentValues().apply {
        put("task_id", taskId)
        put("scope_json", scopeJson)
        put("trigger_type", triggerType.name)
        put("status", status.name)
        put("created_at", DbTime.encode(createdAt))
        putNullable("started_at", startedAt)
        putNullable("finished_at", finishedAt)
        put("total_count", totalCount)
        put("processed_count", processedCount)
        putNullable("current_file", currentFile)
        put("cancel_requested", if (cancelRequested) 1 else 0)
        putNullable("error", error)
    }

    private fun IntegrityResult.toContentValues(): ContentValues = ContentValues().apply {
        put("result_id", resultId)
        put("task_id", taskId)
        put("file_id", fileId)
        put("file_path", filePath)
        put("file_name", fileName)
        put("media_type", mediaType.name)
        put("status", status.name)
        put("reasons_json", reasonsJson)
        put("checks_performed_json", checksPerformedJson)
        put("detected_at", DbTime.encode(detectedAt))
        put("size_bytes", sizeBytes)
        putNullable("hash_before", hashBefore)
        putNullable("hash_after", hashAfter)
        put("read_only_verified", if (readOnlyVerified) 1 else 0)
        put("duration_ms", durationMs)
    }

    private fun IntegrityActionJob.toContentValues(): ContentValues = ContentValues().apply {
        put("action_job_id", actionJobId)
        put("task_id", taskId)
        put("operation", operation.name)
        put("status", status.name)
        put("total_count", totalCount)
        put("processed_count", processedCount)
        putNullable("current_file", currentFile)
        putNullable("target_directory", targetDirectory)
        put("cancel_requested", if (cancelRequested) 1 else 0)
        put("started_at", DbTime.encode(startedAt))
        putNullable("finished_at", finishedAt)
        putNullable("error", error)
        put("items_json", itemsJson)
    }

    private fun AuditEntry.toContentValues(): ContentValues = ContentValues().apply {
        put("audit_id", auditId)
        put("timestamp", DbTime.encode(timestamp))
        put("category", category.name)
        put("operation", operation)
        put("actor", actor)
        putNullable("task_id", taskId)
        putNullable("file_id", fileId)
        putNullable("source_path", sourcePath)
        putNullable("target_path", targetPath)
        putNullable("reason", reason)
        put("status", status.name)
        putNullable("detail_json", detailJson)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private val readableDatabase: SQLiteDatabase get() = database.readableDatabase
    private val writableDatabase: SQLiteDatabase get() = database.writableDatabase

    private object DbTime {
        private val formatter: DateTimeFormatter =
            DateTimeFormatterBuilder().appendInstant(3).toFormatter()

        fun encode(epochMillis: Long): String =
            formatter.format(Instant.ofEpochMilli(epochMillis))

        fun decode(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                Instant.parse(value).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }

        fun decodeRequired(value: String?): Long =
            decode(value) ?: error("数据库时间字段非法或为空")
    }
}
