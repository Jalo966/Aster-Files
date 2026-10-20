package com.aster.files.services

import com.aster.files.data.FileCategory
import com.aster.files.data.FileEntry

/** 一次分类扫描的结果；所有类别都会存在，空类别为空列表。 */
data class CategoryScanResult(
    val categories: Map<FileCategory, List<FileEntry>>,
    val scannedItems: Int,
    val truncated: Boolean
) {
    fun files(category: FileCategory): List<FileEntry> =
        categories[category].orEmpty()

    fun count(category: FileCategory): Int = files(category).size

    fun totalBytes(category: FileCategory): Long =
        files(category).sumOf { it.sizeBytes }
}
