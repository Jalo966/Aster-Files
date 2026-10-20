package com.aster.files.services

import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateSmartSelectorTest {

    private val files = listOf(
        entry("/a/very-long-path/alpha-copy.bin", 100L, 30L),
        entry("/x/b-very-long-name.bin", 100L, 20L),
        entry("/y/s.bin", 100L, 10L)
    )
    private val groups = listOf(DuplicateGroup(100L, "hash", files))

    @Test
    fun smartKeepsShortestPathThenNewest() {
        val selected = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.SMART_KEEP_SHORTEST_PATH_NEWEST
        )
        assertEquals(setOf(files[0].path, files[1].path), selected)
    }

    @Test
    fun nameStrategiesKeepLongestOrShortest() {
        val longest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_LONGEST_NAME
        )
        assertTrue(files[1].path !in longest)
        assertEquals(2, longest.size)

        val shortest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_SHORTEST_NAME
        )
        assertTrue(files[2].path !in shortest)
    }

    @Test
    fun timeStrategiesKeepNewestOrOldest() {
        val newest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_NEWEST
        )
        assertTrue(files[0].path !in newest)

        val oldest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_OLDEST
        )
        assertTrue(files[2].path !in oldest)
    }

    @Test
    fun pathStrategiesKeepLongestOrShortest() {
        val longest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_LONGEST_PATH
        )
        assertTrue(files[0].path !in longest)

        val shortest = DuplicateSmartSelector.selectForDeletion(
            groups,
            DuplicateSelectionStrategy.KEEP_SHORTEST_PATH
        )
        assertTrue(files[2].path !in shortest)
    }

    private fun entry(path: String, size: Long, modified: Long): FileEntry = FileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        isSymlink = false,
        sizeBytes = size,
        modifiedAt = modified,
        extension = "bin",
        kind = FileKind.OTHER
    )
}
