package com.aster.files.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aster Files 的全部持久化模型与领域枚举。
 *
 * 时间约定：所有 Long 时间字段均为 UTC epoch milliseconds。
 * Repository 负责与 SQLite TEXT 的 ISO-8601（固定毫秒精度）互转，
 * 从而既保证可读性，也保证字符串排序与时间顺序一致。
 *
 * 本文件不包含账户、权益、付费、集合或标记相关字段，符合需求 1 与 14/A2。
 */

/** 文件列表类型分类（需求 7.1）。 */
enum class FileKind {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    CODE,
    OTHER
}

/** 完整性任务触发方式；数据库 CHECK 约束只允许 MANUAL（需求 9.1）。 */
enum class TriggerType {
    MANUAL
}

/** 完整性任务状态（需求 9.8）。 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    CANCELLED,
    COMPLETED,
    FAILED
}

/** 媒体类型（需求 6.2、9.2）。 */
enum class MediaType {
    PHOTO,
    VIDEO
}

/** 完整性结论（需求 9.7）。 */
enum class IntegrityStatus {
    NORMAL,
    SUSPECTED_CORRUPT,
    UNREADABLE
}

/** 实际执行的检测层级（需求 9.3 - 9.5）。 */
enum class CheckLevel {
    L1_METADATA,
    L2_PARSE,
    L3_DECODE
}

/** 完整性原因码（需求 6.2、9.7）。 */
enum class ReasonCode {
    ZERO_BYTE,
    TRUNCATED,
    HEADER_MISMATCH,
    DECODE_FAILED,
    METADATA_ERROR,
    READ_ERROR,
    END_OF_STREAM_MISSING
}

/** 原因严重级别，用于结果详情展示与筛选。 */
enum class ReasonLevel {
    INFO,
    WARNING,
    ERROR
}

/** 失效文件专用操作类型（需求 10.1）。 */
enum class ActionOperation {
    DELETE,
    COPY,
    MOVE
}

/** 回收站条目类型。 */
enum class EntryType {
    FILE,
    DIRECTORY
}

/** 回收站条目状态；只允许软删除后的 ACTIVE 与恢复后的 RESTORED。 */
enum class TrashStatus {
    ACTIVE,
    RESTORED
}

/** 失效文件动作任务状态（需求 10.3）。 */
enum class ActionJobStatus {
    PENDING,
    RUNNING,
    CANCELLED,
    COMPLETED,
    FAILED
}

/** 审计分类。 */
enum class AuditCategory {
    FILE,
    TRASH,
    ARCHIVE,
    INTEGRITY_TASK,
    INTEGRITY_ACTION,
    COMPLIANCE,
    APP
}

/** 审计结果状态。 */
enum class AuditStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED
}

/** 文件浏览条目（需求 6.1）。 */
@Immutable
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val extension: String,
    val kind: FileKind,
    val mediaType: MediaType? = null
)

/** 存储根信息（需求 6.1）。 */
data class RootInfo(
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long
)

/**
 * 软删除记录（需求 6.1、7.7）。
 *
 * restoredAt 对应表字段 restored_at；恢复时同时写入该时间与 RESTORED 状态。
 */
@Immutable
data class TrashEntry(
    val trashId: String,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val entryType: EntryType,
    val sizeBytes: Long,
    val deletedAt: Long,
    val status: TrashStatus = TrashStatus.ACTIVE,
    val restoredAt: Long? = null
)

/** 完整性任务（需求 6.1、9.8）。 */
data class IntegrityTask(
    val taskId: String,
    val scopeJson: String,
    val createdAt: Long,
    val triggerType: TriggerType = TriggerType.MANUAL,
    val status: TaskStatus = TaskStatus.PENDING,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val totalCount: Int = 0,
    val processedCount: Int = 0,
    val currentFile: String? = null,
    val cancelRequested: Boolean = false,
    val error: String? = null
)

/** 单个文件的完整性检测结果（需求 6.1、9.7）。 */
data class IntegrityResult(
    val resultId: String,
    val taskId: String,
    val fileId: String,
    val filePath: String,
    val fileName: String,
    val mediaType: MediaType,
    val status: IntegrityStatus,
    val reasonsJson: String,
    val checksPerformedJson: String,
    val detectedAt: Long,
    val sizeBytes: Long,
    val readOnlyVerified: Boolean,
    val durationMs: Long,
    val hashBefore: String? = null,
    val hashAfter: String? = null
)

/** 完整性原因（需求 6.1）。 */
data class IntegrityReason(
    val code: ReasonCode,
    val detail: String,
    val level: ReasonLevel = ReasonLevel.ERROR
)

/** 失效文件动作请求（需求 10.1）。 */
data class IntegrityActionRequest(
    val taskId: String,
    val resultIds: List<String>,
    val operation: ActionOperation,
    val targetDirectory: String? = null,
    val softDelete: Boolean = true
)

/**
 * 审计记录（需求 6.1、10.3）。
 *
 * operation 使用稳定字符串（如 DELETE / RESTORE / COPY / MOVE / RENAME /
 * INTEGRITY_SCAN / INTEGRITY_DELETE），便于后续服务扩展且不改变表结构。
 */
data class AuditEntry(
    val auditId: String,
    val timestamp: Long,
    val category: AuditCategory,
    val operation: String,
    val status: AuditStatus,
    val actor: String = "local-user",
    val taskId: String? = null,
    val fileId: String? = null,
    val sourcePath: String? = null,
    val targetPath: String? = null,
    val reason: String? = null,
    val detailJson: String? = null
)

/**
 * 失效文件动作任务。
 *
 * 需求 6.1 未单独列出该类，但 6.3 的 integrity_action_jobs 表需要持久化模型，
 * 故在此补充；字段与表列一一对应，不增加语义。
 */
data class IntegrityActionJob(
    val actionJobId: String,
    val taskId: String,
    val operation: ActionOperation,
    val status: ActionJobStatus,
    val totalCount: Int,
    val startedAt: Long,
    val processedCount: Int = 0,
    val currentFile: String? = null,
    val targetDirectory: String? = null,
    val cancelRequested: Boolean = false,
    val finishedAt: Long? = null,
    val error: String? = null,
    val itemsJson: String = "[]"
)

/** 完整性结果统计，用于任务页筛选栏。 */
data class IntegrityStats(
    val total: Int = 0,
    val normal: Int = 0,
    val suspectedCorrupt: Int = 0,
    val unreadable: Int = 0
)

/** 回收站统计。 */
data class TrashStats(
    val count: Int,
    val totalBytes: Long
)

/**
 * JSON 列编解码工具。
 *
 * 数据库中的 scope_json、reasons_json、checks_performed_json、items_json
 * 均以字符串保存；这里集中处理，避免各服务各自拼 JSON。
 */
object AsterJsonCodec {

    fun encodeReasons(reasons: List<IntegrityReason>): String {
        val array = JSONArray()
        reasons.forEach { reason ->
            array.put(
                JSONObject().apply {
                    put("code", reason.code.name)
                    put("detail", reason.detail)
                    put("level", reason.level.name)
                }
            )
        }
        return array.toString()
    }

    fun decodeReasons(json: String?): List<IntegrityReason> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = ArrayList<IntegrityReason>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val code = enumOrNull<ReasonCode>(item.optString("code")) ?: continue
            val level = enumOrNull<ReasonLevel>(item.optString("level")) ?: ReasonLevel.ERROR
            result.add(IntegrityReason(code, item.optString("detail"), level))
        }
        return result
    }

    fun encodeChecks(checks: List<CheckLevel>): String {
        val array = JSONArray()
        checks.forEach { array.put(it.name) }
        return array.toString()
    }

    fun decodeChecks(json: String?): List<CheckLevel> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = ArrayList<CheckLevel>(array.length())
        for (index in 0 until array.length()) {
            val level = enumOrNull<CheckLevel>(array.optString(index)) ?: continue
            result.add(level)
        }
        return result
    }

    fun encodeStringList(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            if (!array.isNull(index)) {
                result.add(array.optString(index))
            }
        }
        return result
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? {
    val name = value ?: return null
    if (name.isBlank()) return null
    return runCatching { enumValueOf<T>(name) }.getOrNull()
}
