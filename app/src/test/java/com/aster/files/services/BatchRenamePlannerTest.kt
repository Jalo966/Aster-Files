package com.aster.files.services

import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenamePlannerTest {

    @Test
    fun prefixSequencePreservesEachExtension() {
        val entries = listOf(
            file("/root/photo.jpg"),
            file("/root/another.png"),
            file("/root/no-ext")
        )
        val plan = BatchRenamePlanner.plan(
            entries,
            BatchRenameOptions(
                mode = BatchRenameMode.PREFIX_SEQUENCE,
                prefix = "photo_"
            )
        )
        assertTrue(plan.conflicts.toString(), plan.canExecute)
        assertEquals(
            listOf("photo_001.jpg", "photo_002.png", "photo_003"),
            plan.requests.map { it.newName }
        )
    }

    @Test
    fun findReplaceOnlyChangesBaseName() {
        val entries = listOf(file("/root/my file.txt"))
        val plan = BatchRenamePlanner.plan(
            entries,
            BatchRenameOptions(
                mode = BatchRenameMode.FIND_REPLACE,
                find = " ",
                replace = "_"
            )
        )
        assertTrue(plan.canExecute)
        assertEquals("my_file.txt", plan.requests.single().newName)
    }

    @Test
    fun existingTargetAndDuplicateTargetsAbortWholePlan() {
        val entries = listOf(file("/root/a.txt"), file("/root/b.txt"))
        val conflict = BatchRenamePlanner.plan(
            entries,
            BatchRenameOptions(
                mode = BatchRenameMode.FIND_REPLACE,
                find = "a",
                replace = "x"
            ),
            existingPaths = setOf(java.io.File("/root/x.txt").absolutePath)
        )
        assertFalse(conflict.canExecute)
        assertTrue(conflict.conflicts.any { it.contains("已存在") })

        val duplicate = BatchRenamePlanner.plan(
            listOf(file("/root/a.txt"), file("/root/a.txt")),
            BatchRenameOptions(
                mode = BatchRenameMode.FIND_REPLACE,
                find = "a",
                replace = "x"
            )
        )
        assertFalse(duplicate.canExecute)
        assertTrue(duplicate.conflicts.any { it.contains("重复") })
    }

    @Test
    fun foldersAreRejected() {
        val dir = FileEntry(
            name = "folder",
            path = "/root/folder",
            isDirectory = true,
            isSymlink = false,
            sizeBytes = 0L,
            modifiedAt = 0L,
            extension = "",
            kind = FileKind.FOLDER
        )
        val plan = BatchRenamePlanner.plan(
            listOf(dir),
            BatchRenameOptions(mode = BatchRenameMode.PREFIX_SEQUENCE, prefix = "x_")
        )
        assertFalse(plan.canExecute)
        assertTrue(plan.conflicts.any { it.contains("文件夹") })
    }

    private fun file(path: String): FileEntry = FileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        isSymlink = false,
        sizeBytes = 1L,
        modifiedAt = 0L,
        extension = path.substringAfterLast('.', ""),
        kind = FileKind.OTHER
    )
}
