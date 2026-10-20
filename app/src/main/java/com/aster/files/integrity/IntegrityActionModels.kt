package com.aster.files.integrity

import com.aster.files.data.ActionJobStatus
import com.aster.files.data.ActionOperation
import com.aster.files.data.IntegrityActionRequest
import com.aster.files.data.IntegrityReason
import com.aster.files.data.IntegrityStatus

data class IntegrityActionPreviewItem(
    val resultId: String,
    val filePath: String,
    val fileName: String,
    val status: IntegrityStatus,
    val sizeBytes: Long,
    val reasons: List<IntegrityReason>
)

data class IntegrityActionPreview(
    val request: IntegrityActionRequest,
    val items: List<IntegrityActionPreviewItem>,
    val totalBytes: Long,
    val targetDirectory: String?,
    val targetDisplayPath: String,
    val message: String
)

data class IntegrityActionResult(
    val actionJobId: String,
    val status: ActionJobStatus,
    val operation: ActionOperation,
    val totalCount: Int,
    val processedCount: Int,
    val error: String? = null
)

class IntegrityActionException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object IntegrityActionErrorCodes {
    const val INVALID_REQUEST = "ACTION_INVALID_REQUEST"
    const val MIXED_NORMAL = "ACTION_MIXED_NORMAL"
    const val TASK_NOT_FOUND = "ACTION_TASK_NOT_FOUND"
    const val TASK_RUNNING = "ACTION_TASK_RUNNING"
    const val TASK_INVALID = "ACTION_TASK_INVALID"
    const val RESULT_NOT_FOUND = "ACTION_RESULT_NOT_FOUND"
    const val TOO_MANY_ITEMS = "ACTION_TOO_MANY_ITEMS"
    const val TARGET_INVALID = "ACTION_TARGET_INVALID"
    const val SOFT_DELETE_REQUIRED = "ACTION_SOFT_DELETE_REQUIRED"
    const val CONFIRMATION_REQUIRED = "ACTION_CONFIRMATION_REQUIRED"
    const val JOB_FAILED = "ACTION_JOB_FAILED"
    const val MIXED_NORMAL_MESSAGE = "仅可对失效文件执行此操作"
}
