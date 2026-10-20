package com.aster.files.services

import com.aster.files.security.FileGuard
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchivePreviewTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard
    private lateinit var service: ArchiveService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
        service = ArchiveService(
            guard = guard,
            audit = AuditTrail(RecordingAuditLogger()),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun listsEntriesAndReadsImageBytesWithoutExtracting() = runBlocking {
        val zip = File(root, "bundle.zip")
        TestFiles.zip(
            zip,
            listOf(
                "docs/readme.txt" to "hello".toByteArray(),
                "images/photo.png" to byteArrayOf(1, 2, 3, 4)
            )
        )

        val inspection = service.listEntries(zip.absolutePath)
        assertEquals(2, inspection.entries.size)
        assertTrue(inspection.entries.any { it.name == "images/photo.png" })
        assertFalse(File(root, "images/photo.png").exists())

        val bytes = service.readEntryBytes(zip.absolutePath, "images/photo.png")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
    }

    @Test
    fun readEntryBytesRejectsOverLimit() = runBlocking {
        val zip = File(root, "bundle.zip")
        TestFiles.zip(zip, listOf("big.bin" to ByteArray(100)))

        val error = runCatching {
            service.readEntryBytes(zip.absolutePath, "big.bin", maxBytes = 10)
        }.exceptionOrNull()

        assertTrue(error is ZipSecurityException)
    }

    @Test
    fun listEntriesRejectsMissingArchive() = runBlocking {
        val error = runCatching {
            service.listEntries(File(root, "missing.zip").absolutePath)
        }.exceptionOrNull()

        assertTrue(error is com.aster.files.security.FileGuardException)
    }
}
