package com.aster.files.ui.selection

import com.aster.files.support.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SelectionStatsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recursivelyCountsFoldersFilesAndBytes() {
        val root = temporaryFolder.newFolder("root")
        TestFiles.write(File(root, "a.txt"), "12345")
        TestFiles.write(File(root, "sub/b.bin"), "123")
        TestFiles.write(File(root, "sub/deep/c.bin"), "12")
        val outside = temporaryFolder.newFile("outside.txt")
        TestFiles.write(outside, "1234")

        val stats = SelectionStatsCalculator.calculateBlocking(
            listOf(root.absolutePath, outside.absolutePath)
        )

        assertEquals(3, stats.folderCount)
        assertEquals(4, stats.fileCount)
        assertEquals(14L, stats.totalBytes)
    }

    @Test
    fun parentAndChildSelectionDoesNotDoubleCount() {
        val root = temporaryFolder.newFolder("root2")
        TestFiles.write(File(root, "child/file.txt"), "123456")

        val stats = SelectionStatsCalculator.calculateBlocking(
            listOf(root.absolutePath, File(root, "child/file.txt").absolutePath)
        )

        assertEquals(2, stats.folderCount)
        assertEquals(1, stats.fileCount)
        assertEquals(6L, stats.totalBytes)
    }
}
