package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditEntry
import com.aster.files.data.AuditStatus
import com.aster.files.data.Repository
import java.util.UUID

/** 审计写入抽象；生产实现落 SQLite，JVM 测试使用内存记录器。 */
interface AuditLogger {
    suspend fun log(entry: AuditEntry)
}

/** 生产环境：写入 Repository.audit_log。 */
class RepositoryAuditLogger(private val repository: Repository) : AuditLogger {
    override suspend fun log(entry: AuditEntry) {
        repository.insertAuditEntry(entry)
    }
}

/**
 * 审计记录器。
 *
 * 统一填充 auditId、timestamp 与默认 actor，服务层只描述业务字段。
 * 该类型只依赖模型与 [AuditLogger]，可在 JVM 单元测试中直接使用。
 */
class AuditTrail(
    private val logger: AuditLogger,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun record(
        category: AuditCategory,
        operation: String,
        status: AuditStatus,
        actor: String = "local-user",
        taskId: String? = null,
        fileId: String? = null,
        sourcePath: String? = null,
        targetPath: String? = null,
        reason: String? = null,
        detailJson: String? = null
    ) {
        logger.log(
            AuditEntry(
                auditId = idGenerator(),
                timestamp = clock(),
                category = category,
                operation = operation,
                actor = actor,
                taskId = taskId,
                fileId = fileId,
                sourcePath = sourcePath,
                targetPath = targetPath,
                reason = reason,
                status = status,
                detailJson = detailJson
            )
        )
    }
}
