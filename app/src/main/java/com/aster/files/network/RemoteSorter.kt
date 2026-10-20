package com.aster.files.network

import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.SortDirection
import com.aster.files.ui.sort.SortField
import java.util.Locale

/** 网络目录排序：文件夹始终置顶，其余复用 FileSort 字段。 */
object RemoteSorter {
    fun sort(entries: List<RemoteEntry>, sort: FileSort): List<RemoteEntry> {
        val direction = if (sort.direction == SortDirection.DESC) -1 else 1
        val comparator: Comparator<RemoteEntry> = when (sort.field) {
            SortField.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.TIME -> compareBy { it.modifiedAt }
            SortField.TYPE -> compareBy { it.extension.lowercase(Locale.ROOT) }
        }
        val directed = if (direction < 0) comparator.reversed() else comparator
        return entries.sortedWith(
            compareBy<RemoteEntry> { !it.isDirectory }.then(directed)
        )
    }
}
