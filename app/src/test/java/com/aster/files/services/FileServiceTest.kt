package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.FileKind
import com.aster.files.data.MediaType
import com.aster.files.security.FileGuard
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class FileServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var guard: FileGuard
    private lateinit var auditLogger: RecordingAuditLogger
    private lateinit var service: FileService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        guard = TestFiles.guard(root)
        auditLogger = RecordingAuditLogger()
        val sequence = AtomicInteger()
        val audit = AuditTrail(
            logger = auditLogger,
            idGenerator = { "audit-" + sequence.incrementAndGet() },
            clock = { 1_000L }
        )
        service = FileService(guard, audit)
    }


    @Test
    fun renameBatchMovesAllAndWritesAudit() = runBlocking {
        TestFiles.write(File(root, "a.txt"), "a")
        TestFiles.write(File(root, "b.txt"), "b")
        val result = service.renameBatch(
            listOf(
                BatchRenameRequest(File(root, "a.txt").absolutePath, "a.txt", root.absolutePath, "x.txt"),
                BatchRenameRequest(File(root, "b.txt").absolutePath, "b.txt", root.absolutePath, "y.txt")
            )
        )
        assertTrue(result.message, result.success)
        assertTrue(File(root, "x.txt").exists())
        assertTrue(File(root, "y.txt").exists())
        assertFalse(File(root, "a.txt").exists())
        assertFalse(File(root, "b.txt").exists())
        assertEquals(2, auditLogger.entries.count { it.operation == "BATCH_RENAME_ITEM" })
        assertTrue(auditLogger.entries.any {
            it.operation == "BATCH_RENAME" && it.status == AuditStatus.SUCCESS
        })
    }

    @Test
    fun renameBatchConflictAbortsBeforeAnyRename() = runBlocking {
        TestFiles.write(File(root, "first.txt"), "1")
        TestFiles.write(File(root, "second.txt"), "2")
        TestFiles.write(File(root, "taken.txt"), "3")
        val result = service.renameBatch(
            listOf(
                BatchRenameRequest(File(root, "first.txt").absolutePath, "first.txt", root.absolutePath, "new-first.txt"),
                BatchRenameRequest(File(root, "second.txt").absolutePath, "second.txt", root.absolutePath, "taken.txt")
            )
        )
        assertFalse(result.success)
        assertTrue(File(root, "first.txt").exists())
        assertFalse(File(root, "new-first.txt").exists())
        assertTrue(File(root, "second.txt").exists())
        assertTrue(File(root, "taken.txt").exists())
        assertTrue(auditLogger.entries.any {
            it.operation == "BATCH_RENAME" && it.status == AuditStatus.FAILED
        })
    }

    @Test
    fun listDirectoryReturnsKindsAndMediaTypes() = runBlocking {
        TestFiles.write(File(root, "photo.jpg"), "abc")
        TestFiles.write(File(root, "song.mp3"), "abc")
        Files.createDirectory(File(root, "sub").toPath())

        val entries = service.listDirectory(root.absolutePath)
        assertEquals(3, entries.size)
        assertEquals("sub", entries.first().name)
        assertTrue(entries.first().isDirectory)
        assertEquals(FileKind.FOLDER, entries.first().kind)

        val photo = entries.first { it.name == "photo.jpg" }
        assertEquals(FileKind.IMAGE, photo.kind)
        assertEquals(MediaType.PHOTO, photo.mediaType)
        assertEquals(3L, photo.sizeBytes)

        val song = entries.first { it.name == "song.mp3" }
        assertEquals(FileKind.AUDIO, song.kind)
        assertNull(song.mediaType)
    }

    @Test
    fun searchRespectsDepthAndResultLimit() = runBlocking {
        TestFiles.write(File(root, "a.txt"), "a")
        TestFiles.write(File(root, "sub/needle.txt"), "b")
        TestFiles.write(File(root, "sub/deep/needle.txt"), "c")

        val depthOne = service.search(root.absolutePath, "needle", maxDepth = 1)
        assertTrue(depthOne.isEmpty())

        val depthTwo = service.search(root.absolutePath, "needle", maxDepth = 2)
        assertEquals(1, depthTwo.size)
        assertTrue(depthTwo.first().path.endsWith("sub/needle.txt") ||
            depthTwo.first().path.endsWith("sub\\needle.txt"))

        val limited = service.search(root.absolutePath, "needle", maxDepth = 12, maxResults = 1)
        assertEquals(1, limited.size)
    }

    @Test
    fun createRenameAndRejectOverwrite() = runBlocking {
        assertTrue(service.createDirectory(root.absolutePath, "docs").success)
        assertTrue(service.createFile(root.absolutePath, "a.txt").success)

        val duplicate = service.createFile(root.absolutePath, "a.txt")
        assertFalse(duplicate.success)
        assertEquals("ALREADY_EXISTS", duplicate.errorCode)

        val renamed = service.rename(File(root, "a.txt").absolutePath, "b.txt")
        assertTrue(renamed.success)
        assertTrue(File(root, "b.txt").exists())
        assertFalse(File(root, "a.txt").exists())

        val conflict = service.rename(File(root, "b.txt").absolutePath, "docs")
        assertFalse(conflict.success)
        assertEquals("ALREADY_EXISTS", conflict.errorCode)
        assertTrue(File(root, "b.txt").exists())
    }

    @Test
    fun copyTreeAndRejectOverwriteAndSelfReference() = runBlocking {
        val source = File(root, "src")
        TestFiles.write(File(source, "nested/a.txt"), "hello")
        val target = File(root, "dst")
        Files.createDirectory(target.toPath())

        val copied = service.copy(source.absolutePath, target.absolutePath)
        assertTrue(copied.message, copied.success)
        assertEquals("hello", File(target, "src/nested/a.txt").readText())

        val duplicate = service.copy(source.absolutePath, target.absolutePath)
        assertFalse(duplicate.success)
        assertEquals("ALREADY_EXISTS", duplicate.errorCode)

        val self = service.copy(target.absolutePath, target.absolutePath)
        assertFalse(self.success)
        assertEquals("SELF_REFERENCE", self.errorCode)
    }

    @Test
    fun moveFileKeepsContentAndRemovesSource() = runBlocking {
        val source = File(root, "move.txt")
        TestFiles.write(source, "move-me")
        val targetDirectory = File(root, "moved")
        Files.createDirectory(targetDirectory.toPath())

        val moved = service.move(source.absolutePath, targetDirectory.absolutePath)
        assertTrue(moved.message, moved.success)
        assertFalse(source.exists())
        assertEquals("move-me", File(targetDirectory, "move.txt").readText())
    }

    @Test
    fun outsideRootIsRejectedAndAudited() = runBlocking {
        val outside = temporaryFolder.newFile("outside.txt")
        TestFiles.write(outside, "outside")

        val result = service.copy(outside.absolutePath, root.absolutePath)
        assertFalse(result.success)
        assertEquals("OUTSIDE_ROOT", result.errorCode)

        val audit = auditLogger.entries.last()
        assertEquals(AuditCategory.FILE, audit.category)
        assertEquals(AuditStatus.FAILED, audit.status)
        assertEquals("COPY", audit.operation)
    }

    @Test
    fun batchLimitRejectsWholeBatch() = runBlocking {
        val limited = FileService(
            guard = guard,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-limited" }, clock = { 1L }),
            maxBatchItems = 2
        )
        val files = (1..3).map { index ->
            File(root, "f$index.txt").also { TestFiles.write(it, "x") }
        }
        val target = File(root, "batch-target")
        Files.createDirectory(target.toPath())

        val batch = limited.copyBatch(files.map { it.absolutePath }, target.absolutePath)
        assertEquals(1, batch.results.size)
        assertEquals("BATCH_TOO_LARGE", batch.results.first().errorCode)
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun batchReturnsPerItemFailures() = runBlocking {
        val good = File(root, "good.txt").also { TestFiles.write(it, "good") }
        val missing = File(root, "missing.txt")
        val target = File(root, "batch-dst")
        Files.createDirectory(target.toPath())

        val batch = service.copyBatch(
            listOf(good.absolutePath, missing.absolutePath),
            target.absolutePath
        )
        assertEquals(2, batch.results.size)
        assertEquals(1, batch.successCount)
        assertEquals(1, batch.failureCount)
        assertTrue(File(target, "good.txt").exists())
        assertNotNull(batch.results.first { !it.success }.errorCode)
    }

    @Test
    fun searchDoesNotFollowSymlinks() = runBlocking {
        val outsideDirectory = temporaryFolder.newFolder("outside-dir")
        TestFiles.write(File(outsideDirectory, "needle.txt"), "outside")
        val link = File(root, "link")
        Assume.assumeTrue(
            "当前文件系统不支持创建符号链接",
            TestFiles.tryCreateSymlink(link, outsideDirectory)
        )

        val results = service.search(root.absolutePath, "needle", maxDepth = 12)
        assertTrue(results.isEmpty())
    }

    @Test
    fun listDirectoryRejectsFileAndOutsidePaths() = runBlocking {
        val file = File(root, "plain.txt")
        TestFiles.write(file, "x")
        val error = try {
            service.listDirectory(file.absolutePath)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(error)
    }

    @Test
    fun hiddenFilesAreFilteredByDefaultAndOperable() = runBlocking {
        TestFiles.write(File(root, ".secret.txt"), "secret")
        TestFiles.write(File(root, "visible.txt"), "visible")
        Files.createDirectory(File(root, ".hiddenDir").toPath())

        val defaultList = service.listDirectory(root.absolutePath)
        assertFalse(defaultList.any { it.name.startsWith(".") })
        assertTrue(defaultList.any { it.name == "visible.txt" })

        val all = service.listDirectory(root.absolutePath, showHidden = true)
        assertTrue(all.any { it.name == ".secret.txt" })
        assertTrue(all.any { it.name == ".hiddenDir" })

        assertTrue(service.createDirectory(root.absolutePath, ".config").success)
        val allAfterCreate = service.listDirectory(root.absolutePath, showHidden = true)
        assertTrue(allAfterCreate.any { it.name == ".config" })

        val target = File(root, "hidden-target")
        Files.createDirectory(target.toPath())
        val copied = service.copy(
            File(root, ".secret.txt").absolutePath,
            target.absolutePath
        )
        assertTrue(copied.message, copied.success)
        assertTrue(File(target, ".secret.txt").exists())

        val moved = service.move(
            sourcePath = File(root, ".secret.txt").absolutePath,
            targetDirectory = target.absolutePath,
            newName = ".moved.txt"
        )
        assertTrue(moved.message, moved.success)
        assertTrue(File(target, ".moved.txt").exists())

        val hiddenSearch = service.search(root.absolutePath, "secret", showHidden = false)
        assertTrue(hiddenSearch.isEmpty())
        val hiddenSearchAll = service.search(root.absolutePath, "secret", showHidden = true)
        assertTrue(hiddenSearchAll.any { it.name == ".secret.txt" })
    }

    @Test
    fun internalPathsAreInvisibleAndRejected() = runBlocking {
        val asterData = File(root, ".aster-data")
        val trash = File(root, ".trash")
        Files.createDirectory(asterData.toPath())
        Files.createDirectory(trash.toPath())
        val secret = File(asterData, "secret.jpg")
        TestFiles.write(secret, "secret")
        TestFiles.write(File(trash, "hidden.jpg"), "trash")

        val internalGuard = TestFiles.guardWithInternal(root, listOf(asterData, trash))
        val internalService = FileService(
            guard = internalGuard,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-internal" }, clock = { 1L })
        )

        val listed = internalService.listDirectory(root.absolutePath, showHidden = true)
        assertFalse(listed.any { it.name == ".aster-data" })
        assertFalse(listed.any { it.name == ".trash" })

        val copied = internalService.copy(secret.absolutePath, root.absolutePath)
        assertFalse(copied.success)
        assertEquals("INTERNAL_PATH_FORBIDDEN", copied.errorCode)

        val error = try {
            internalGuard.guardForRead(secret.absolutePath)
            null
        } catch (e: com.aster.files.security.FileGuardException) {
            e
        }
        assertNotNull(error)
        assertEquals("INTERNAL_PATH_FORBIDDEN", error!!.stableCode)
    }
}
