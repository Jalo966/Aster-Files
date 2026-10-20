package com.aster.files.services

/** 常规存储分析的类型分类。 */
enum class AnalysisCategory {
    PHOTO,
    VIDEO,
    DOCUMENT,
    AUDIO,
    ARCHIVE,
    CODE,
    OTHER
}

/** 分析结果中的大文件条目。 */
data class AnalyzedFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val category: AnalysisCategory
)

/** 常规存储分析结果。 */
data class AnalysisResult(
    val rootPath: String,
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
    val categoryCounts: Map<AnalysisCategory, Int>,
    val largestFiles: List<AnalyzedFile>,
    val scannedItems: Int,
    val maxItems: Int,
    val truncated: Boolean
) {
    val photoCount: Int get() = categoryCounts[AnalysisCategory.PHOTO] ?: 0
    val videoCount: Int get() = categoryCounts[AnalysisCategory.VIDEO] ?: 0
}
