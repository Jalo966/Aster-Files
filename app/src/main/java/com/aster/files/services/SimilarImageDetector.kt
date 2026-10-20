package com.aster.files.services

data class SimilarImageInfo(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val modifiedAt: Long
)

/** 相似图片双重条件：文件名归一化 + 大小 5% 内 + 宽高完全一致。 */
object SimilarImageDetector {
    fun findDuplicates(images: List<SimilarImageInfo>): List<SimilarImageInfo> {
        val result = mutableListOf<SimilarImageInfo>()
        images.groupBy { normalizeName(it.name) }.values.forEach { group ->
            val kept = mutableListOf<SimilarImageInfo>()
            group.sortedBy { it.modifiedAt }.forEach { candidate ->
                if (candidate.width <= 0 || candidate.height <= 0) return@forEach
                val duplicate = kept.any { base ->
                    base.width == candidate.width &&
                        base.height == candidate.height &&
                        sizesClose(base.sizeBytes, candidate.sizeBytes)
                }
                if (duplicate) result.add(candidate) else kept.add(candidate)
            }
        }
        return result
    }

    fun sizesClose(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return false
        val larger = maxOf(a, b)
        return kotlin.math.abs(a - b).toDouble() / larger <= 0.05
    }

    fun normalizeName(name: String): String {
        return name.substringBeforeLast('.').lowercase()
            .replace(Regex("\\s*\\(\\d+\\)$"), "")
            .replace(Regex("[_\\- ](copy|副本|converted)$"), "")
            .replace(Regex("[_\\- ]\\d+$"), "")
            .trim()
    }
}
