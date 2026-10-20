package com.aster.files.ui.sort

import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSorterTest {

    @Test
    fun foldersAlwaysComeBeforeFiles() {
        val entries = listOf(
            file("a.txt", size = 10),
            folder("z-folder"),
            file("b.txt", size = 20),
            folder("a-folder")
        )
        val result = FileSorter.sort(entries, FileSort(SortField.SIZE, SortDirection.DESC))
        assertEquals(listOf("a-folder", "z-folder", "b.txt", "a.txt"), result.map { it.name })
    }

    @Test
    fun sizeAndTypeSortFoldersByName() {
        val entries = listOf(
            file("small.bin", size = 1, kind = FileKind.IMAGE),
            folder("zeta"),
            folder("alpha"),
            file("big.bin", size = 100, kind = FileKind.VIDEO),
            folder("3folder"),
            folder("#folder")
        )
        val bySize = FileSorter.sort(entries, FileSort(SortField.SIZE, SortDirection.ASC))
        assertEquals(
            listOf("alpha", "zeta", "3folder", "#folder", "small.bin", "big.bin"),
            bySize.map { it.name }
        )
        val byType = FileSorter.sort(entries, FileSort(SortField.TYPE, SortDirection.ASC))
        assertEquals(
            listOf("alpha", "zeta", "3folder", "#folder", "small.bin", "big.bin"),
            byType.map { it.name }
        )
    }

    @Test
    fun timeSortUsesFolderModifiedTime() {
        val entries = listOf(
            folder("new-folder", modified = 200L),
            file("old-file", modified = 100L),
            folder("old-folder", modified = 50L),
            file("new-file", modified = 300L)
        )
        val result = FileSorter.sort(entries, FileSort(SortField.TIME, SortDirection.ASC))
        assertEquals(
            listOf("old-folder", "new-folder", "old-file", "new-file"),
            result.map { it.name }
        )
    }

    @Test
    fun nameSortUsesLetterDigitSymbolOrder() {
        val entries = listOf(
            folder("#symbol"),
            file("3number"),
            folder("Zebra"),
            file("apple")
        )
        val result = FileSorter.sort(entries, FileSort(SortField.NAME, SortDirection.ASC))
        assertEquals(listOf("Zebra", "#symbol", "apple", "3number"), result.map { it.name })
        assertTrue(FileSorter.compareNames("Alpha", "3file") < 0)
        assertTrue(FileSorter.compareNames("3file", "#file") < 0)
    }

    private fun folder(name: String, modified: Long = 0L): FileEntry = FileEntry(
        name = name,
        path = "/root/" + name,
        isDirectory = true,
        isSymlink = false,
        sizeBytes = 0L,
        modifiedAt = modified,
        extension = "",
        kind = FileKind.FOLDER
    )

    private fun file(
        name: String,
        size: Long = 0L,
        modified: Long = 0L,
        kind: FileKind = FileKind.OTHER
    ): FileEntry = FileEntry(
        name = name,
        path = "/root/" + name,
        isDirectory = false,
        isSymlink = false,
        sizeBytes = size,
        modifiedAt = modified,
        extension = name.substringAfterLast('.', ""),
        kind = kind
    )
}
