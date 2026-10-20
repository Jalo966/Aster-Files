package com.aster.files.ui.viewmodel

import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.sort.SortDirection
import com.aster.files.ui.sort.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {

    @Test
    fun addTabActivatesNewRootTab() {
        val vm = MainViewModel("/root", FileSort.DEFAULT)
        val first = vm.state.value.tabs.single()
        val second = vm.addTab("/root", FileSort.DEFAULT)
        assertEquals(2, vm.state.value.tabs.size)
        assertEquals(second.id, vm.state.value.activeTabId)
        assertEquals("/root", vm.state.value.activeTab.path)
        assertEquals(first.id, vm.state.value.tabs.first().id)
    }

    @Test
    fun tabsKeepIndependentPathsSelectionAndSort() {
        val vm = MainViewModel("/root", FileSort.DEFAULT)
        val first = vm.state.value.tabs.single()
        val second = vm.addTab("/root/Pictures", FileSort(SortField.NAME, SortDirection.ASC))
        vm.updateTab(
            first.id,
            path = "/root/Download",
            selectedPaths = setOf("/root/Download/a.txt"),
            sort = FileSort(SortField.SIZE, SortDirection.DESC)
        )
        vm.updateTab(
            second.id,
            path = "/root/Pictures",
            selectedPaths = setOf("/root/Pictures/b.jpg"),
            sort = FileSort(SortField.NAME, SortDirection.ASC)
        )
        val firstTab = vm.state.value.tabs.first { it.id == first.id }
        val secondTab = vm.state.value.tabs.first { it.id == second.id }
        assertEquals("/root/Download", firstTab.path)
        assertEquals(setOf("/root/Download/a.txt"), firstTab.selectedPaths)
        assertEquals(FileSort(SortField.SIZE, SortDirection.DESC), firstTab.sort)
        assertEquals("/root/Pictures", secondTab.path)
        assertEquals(setOf("/root/Pictures/b.jpg"), secondTab.selectedPaths)
        assertEquals(FileSort(SortField.NAME, SortDirection.ASC), secondTab.sort)
    }

    @Test
    fun closeActiveTabActivatesNeighborAndLastTabCannotClose() {
        val vm = MainViewModel("/root", FileSort.DEFAULT)
        val first = vm.state.value.tabs.single()
        val second = vm.addTab("/root/Download", FileSort.DEFAULT)
        val next = vm.closeTab(second.id)
        assertNotNull(next)
        assertEquals(first.id, next!!.id)
        assertEquals(first.id, vm.state.value.activeTabId)
        assertEquals(1, vm.state.value.tabs.size)
        assertNull(vm.closeTab(first.id))
        assertTrue(vm.state.value.tabs.isNotEmpty())
    }
}
