package com.aster.files.services

/** 单个文件操作的结果。errorCode 使用稳定字符串，供 UI 与审计判断。 */
data class OperationResult(
    val success: Boolean,
    val message: String,
    val sourcePath: String? = null,
    val targetPath: String? = null,
    val errorCode: String? = null
) {
    companion object {
        fun ok(
            message: String,
            sourcePath: String? = null,
            targetPath: String? = null
        ): OperationResult = OperationResult(
            success = true,
            message = message,
            sourcePath = sourcePath,
            targetPath = targetPath
        )

        fun failed(
            message: String,
            errorCode: String,
            sourcePath: String? = null,
            targetPath: String? = null
        ): OperationResult = OperationResult(
            success = false,
            message = message,
            sourcePath = sourcePath,
            targetPath = targetPath,
            errorCode = errorCode
        )
    }
}

/** 批量操作结果；每个条目都带有独立失败原因。 */
data class BatchResult(
    val operation: String,
    val results: List<OperationResult>
) {
    val successCount: Int get() = results.count { it.success }
    val failureCount: Int get() = results.count { !it.success }
    val allSucceeded: Boolean get() = results.isNotEmpty() && results.all { it.success }

    fun summary(): String =
        "$operation：成功 $successCount 项，失败 $failureCount 项"
}
