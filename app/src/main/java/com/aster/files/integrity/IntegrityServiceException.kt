package com.aster.files.integrity

/** 完整性任务创建/执行阶段的稳定错误。 */
class IntegrityServiceException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object IntegrityServiceErrorCodes {
    const val SCOPE_EMPTY = "SCOPE_EMPTY"
    const val SCOPE_INVALID = "SCOPE_INVALID"
    const val SCOPE_TOO_LARGE = "SCOPE_TOO_LARGE"
    const val TASK_NOT_FOUND = "TASK_NOT_FOUND"
    const val TASK_NOT_PENDING = "TASK_NOT_PENDING"
    const val TASK_TRIGGER_INVALID = "TASK_TRIGGER_INVALID"
    const val TASK_INVALID = "TASK_INVALID"
}
