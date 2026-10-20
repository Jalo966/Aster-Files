package com.aster.files.services

import com.aster.files.security.FileGuard
import com.aster.files.support.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class DirectoryAnalysisServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
    }

    @Test
    fun findsLargeZeroEmptyAndDuplicateFiles() = runBlocking {
        TestFiles.write(File(root, "large.bin"), "L".repeat(4096))
        TestFiles.write(File(root, "dup1.txt"), "same-content")
        TestFiles.write(File(root, "dup2.txt"), "same-content")
        TestFiles.write(File(root, "different.txt"), "diff-content")
        TestFiles.write(File(root, "zero.txt"), "")
        File(root, "empty-dir").mkdirs()
        TestFiles.write(File(root, "non-empty/child.txt"), "x")

        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined,
            minLargeFileBytes = 1L
        ).analyze(root.absolutePath)

        assertTrue(result.largeFiles.any { it.name == "large.bin" })
        assertEquals(1, result.zeroByteFiles.size)
        assertEquals("zero.txt", result.zeroByteFiles.single().name)
        assertEquals(1, result.emptyDirectories.size)
        assertEquals("empty-dir", result.emptyDirectories.single().name)
        assertEquals(1, result.duplicateGroups.size)
        val group = result.duplicateGroups.single()
        assertEquals(12L, group.sizeBytes)
        assertEquals(
            listOf("dup1.txt", "dup2.txt"),
            group.files.map { it.name }.sorted()
        )
        assertEquals(2, group.files.size)
    }

    @Test
    fun largeFileLimitKeepsLargest() = runBlocking {
        TestFiles.write(File(root, "a.bin"), "a".repeat(10))
        TestFiles.write(File(root, "b.bin"), "b".repeat(100))
        TestFiles.write(File(root, "c.bin"), "c".repeat(50))

        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined,
            maxLargeFiles = 2,
            minLargeFileBytes = 1L
        ).analyze(root.absolutePath)

        assertEquals(2, result.largeFiles.size)
        assertEquals("b.bin", result.largeFiles[0].name)
        assertEquals("c.bin", result.largeFiles[1].name)
    }

    @Test
    fun defaultThresholdExcludesFilesAtOrBelowTenMegabytes() = runBlocking {
        TestFiles.writeBytes(File(root, "under.bin"), ByteArray(1024))
        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined
        ).analyze(root.absolutePath)
        assertTrue(result.largeFiles.isEmpty())
    }

    @Test
    fun defaultThresholdIncludesOnlyFilesStrictlyAboveTenMegabytes() = runBlocking {
        val exact = File(root, "exact-10mb.bin")
        RandomAccessFile(exact, "rw").use { it.setLength(10L * 1024L * 1024L) }
        val above = File(root, "above-10mb.bin")
        RandomAccessFile(above, "rw").use { it.setLength(10L * 1024L * 1024L + 1L) }

        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined
        ).analyze(root.absolutePath)

        assertEquals(listOf("above-10mb.bin"), result.largeFiles.map { it.name })
    }

    @Test
    fun reportsFolderUsageAndRecentFiles() = runBlocking {
        val now = 1_700_000_000_000L
        TestFiles.write(File(root, "big/a.txt"), "123456")
        TestFiles.write(File(root, "small/b.txt"), "12")
        File(root, "empty").mkdirs()
        TestFiles.write(File(root, "recent.txt"), "new")
        val old = File(root, "old.txt")
        TestFiles.write(old, "old")
        old.setLastModified(now - 8L * 24L * 60L * 60L * 1000L)

        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now }
        ).analyze(root.absolutePath)

        assertEquals(
            listOf("big", "small", "empty"),
            result.folderUsage.map { it.name }
        )
        assertEquals(6L, result.folderUsage[0].sizeBytes)
        assertEquals(1, result.folderUsage[0].fileCount)
        assertEquals(0, result.folderUsage[2].fileCount)
        assertTrue(result.recentFiles.none { it.name == "old.txt" })
        assertTrue(result.recentFiles.any { it.name == "a.txt" })
        assertTrue(result.recentFiles.any { it.name == "recent.txt" })
    }

    @Test
    fun itemLimitMarksTruncated() = runBlocking {
        repeat(5) { index ->
            TestFiles.write(File(root, "f" + index + ".txt"), "x")
        }
        val result = DirectoryAnalysisService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined,
            maxItems = 2
        ).analyze(root.absolutePath)
        assertTrue(result.truncated)
    }
}
