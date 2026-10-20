package com.aster.files.ui.sort

import android.content.Context
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SortField(val label: String) {
    TIME("时间"),
    SIZE("大小"),
    NAME("名称"),
    TYPE("类型")
}

enum class SortDirection(val label: String) {
    ASC("升序"),
    DESC("降序")
}

data class FileSort(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASC
) {
    companion object {
        val DEFAULT = FileSort()
    }
}

/**
 * 统一文件排序：
 * 1. 文件夹永远在文件之前；
 * 2. 时间/名称排序对文件夹使用对应字段；
 * 3. 大小/类型排序时文件夹没有该属性，强制按名称排序。
 */
object FileSorter {

    fun sort(entries: List<FileEntry>, sort: FileSort): List<FileEntry> {
        val folders = entries.filter { it.isDirectory }
        val files = entries.filterNot { it.isDirectory }
        val direction = if (sort.direction == SortDirection.DESC) -1 else 1
        val folderComparator = when (sort.field) {
            SortField.SIZE, SortField.TYPE -> nameComparator
            SortField.TIME -> timeComparator
            SortField.NAME -> nameComparator
        }
        val fileComparator = when (sort.field) {
            SortField.TIME -> timeComparator
            SortField.SIZE -> sizeComparator
            SortField.NAME -> nameComparator
            SortField.TYPE -> typeComparator
        }
        val sortedFolders = when (sort.field) {
            SortField.SIZE, SortField.TYPE -> folders.sortedWith(nameComparator)
            else -> folders.sortedWith(comparatorWithDirection(folderComparator, direction))
        }
        val sortedFiles = files.sortedWith(comparatorWithDirection(fileComparator, direction))
        return sortedFolders + sortedFiles
    }

    private fun comparatorWithDirection(
        comparator: Comparator<FileEntry>,
        direction: Int
    ): Comparator<FileEntry> =
        if (direction >= 0) comparator else comparator.reversed()

    private val nameComparator = Comparator<FileEntry> { left, right ->
        val nameResult = compareNames(left.name, right.name)
        if (nameResult != 0) nameResult else left.path.compareTo(right.path)
    }

    private val timeComparator = Comparator<FileEntry> { left, right ->
        val timeResult = left.modifiedAt.compareTo(right.modifiedAt)
        if (timeResult != 0) timeResult else nameComparator.compare(left, right)
    }

    private val sizeComparator = Comparator<FileEntry> { left, right ->
        val sizeResult = left.sizeBytes.compareTo(right.sizeBytes)
        if (sizeResult != 0) sizeResult else nameComparator.compare(left, right)
    }

    private val typeComparator = Comparator<FileEntry> { left, right ->
        val typeResult = left.kind.ordinal.compareTo(right.kind.ordinal)
        if (typeResult != 0) typeResult else nameComparator.compare(left, right)
    }

    /** A-Z、0-9、#：字母组优先，其次数字，最后其他符号。 */
    fun compareNames(left: String, right: String): Int {
        val leftRank = nameRank(left)
        val rightRank = nameRank(right)
        if (leftRank != rightRank) return leftRank - rightRank
        val lowerResult = left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
        if (lowerResult != 0) return lowerResult
        return left.compareTo(right)
    }

    private fun nameRank(value: String): Int {
        val first = value.firstOrNull() ?: return 3
        return when {
            first.isLetter() -> 0
            first.isDigit() -> 1
            else -> 2
        }
    }
}

/** 全局排序偏好；文件页与分类页共用，按最后选择持久化。 */
class FileSortPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableSort = MutableStateFlow(read())
    val sort: StateFlow<FileSort> = mutableSort.asStateFlow()

    fun setSort(sort: FileSort) {
        preferences.edit()
            .putString(KEY_FIELD, sort.field.name)
            .putString(KEY_DIRECTION, sort.direction.name)
            .apply()
        mutableSort.value = sort
    }

    private fun read(): FileSort {
        val field = runCatching {
            SortField.valueOf(
                preferences.getString(KEY_FIELD, SortField.NAME.name) ?: SortField.NAME.name
            )
        }.getOrDefault(SortField.NAME)
        val direction = runCatching {
            SortDirection.valueOf(
                preferences.getString(KEY_DIRECTION, SortDirection.ASC.name)
                    ?: SortDirection.ASC.name
            )
        }.getOrDefault(SortDirection.ASC)
        return FileSort(field, direction)
    }

    private companion object {
        const val PREFERENCES_NAME = "aster_sort"
        const val KEY_FIELD = "sort_field"
        const val KEY_DIRECTION = "sort_direction"
    }
}
