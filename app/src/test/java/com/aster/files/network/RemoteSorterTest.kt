package com.aster.files.network

import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.SortDirection
import com.aster.files.ui.sort.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSorterTest {

    private val entries = listOf(
        RemoteEntry("b.txt", "/b.txt", false, 30, 100),
        RemoteEntry("folder", "/folder", true, 0, 50),
        RemoteEntry("a.txt", "/a.txt", false, 10, 200)
    )

    @Test
    fun foldersAlwaysComeFirst() {
        val result = RemoteSorter.sort(
            entries,
            FileSort(SortField.SIZE, SortDirection.DESC)
        )
        assertEquals("folder", result.first().name)
        assertEquals(listOf("b.txt", "a.txt"), result.drop(1).map { it.name })
    }

    @Test
    fun sortsByNameAndTime() {
        val byName = RemoteSorter.sort(
            entries,
            FileSort(SortField.NAME, SortDirection.ASC)
        )
        assertEquals(listOf("folder", "a.txt", "b.txt"), byName.map { it.name })
        val byTime = RemoteSorter.sort(
            entries,
            FileSort(SortField.TIME, SortDirection.DESC)
        )
        assertEquals(listOf("folder", "a.txt", "b.txt"), byTime.map { it.name })
    }
}
