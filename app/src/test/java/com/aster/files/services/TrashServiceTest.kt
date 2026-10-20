package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.data.EntryType
import com.aster.files.data.TrashStatus
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.support.InMemoryTrashStore
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TrashServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard
    private lateinit var store: InMemoryTrashStore
    private lateinit var auditLogger: RecordingAuditLogger
    private lateinit var service: TrashService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
        store = InMemoryTrashStore()
        auditLogger = RecordingAuditLogger()
        val sequence = AtomicInteger()
        val audit = AuditTrail(
            logger = auditLogger,
            idGenerator = { "audit-" + sequence.incrementAndGet() },
            clock = { 1_000L }
        )
        service = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = store,
            audit = audit,
            clock = { 1_234L },
            idGenerator = { "trash-" + sequence.incrementAndGet() }
        )
    }

    @Test
    fun softDeleteAndRestoreFile() = runBlocking {
        val file = File(root, "a.txt")
        TestFiles.write(file, "hello")
        val originalCanonical = file.canonicalPath

        val deleted = service.softDelete(file.absolutePath)
        assertTrue(deleted.message, deleted.success)
        assertFalse(file.exists())
        assertTrue(service.trashFilesPath.startsWith(trash.canonicalPath))

        val entry = store.snapshot().single()
        assertEquals(originalCanonical, entry.originalPath)
        assertEquals("a.txt", entry.name)
        assertEquals(EntryType.FILE, entry.entryType)
        assertEquals(5L, entry.sizeBytes)
        assertEquals(TrashStatus.ACTIVE, entry.status)
        assertEquals(1_234L, entry.deletedAt)
        assertTrue(File(entry.trashPath).exists())
        assertEquals(1, service.list().size)

        val restored = service.restore(entry.trashId)
        assertTrue(restored.message, restored.success)
        assertTrue(file.exists())
        assertEquals("hello", file.readText())
        assertFalse(File(entry.trashPath).exists())
        assertEquals(TrashStatus.RESTORED, store.get(entry.trashId)!!.status)
        assertEquals(1_234L, store.get(entry.trashId)!!.restoredAt)
        assertTrue(service.list().isEmpty())

        assertEquals(
            listOf("SOFT_DELETE", "RESTORE"),
            auditLogger.entries.map { it.operation }
        )
        assertTrue(auditLogger.entries.all { it.category == AuditCategory.TRASH })
        assertTrue(auditLogger.entries.all { it.status == AuditStatus.SUCCESS })
    }

    @Test
    fun softDeleteAndRestoreDirectory() = runBlocking {
        val directory = File(root, "folder")
        TestFiles.write(File(directory, "nested/a.txt"), "abc")
        TestFiles.write(File(directory, "b.txt"), "de")

        val deleted = service.softDelete(directory.absolutePath)
        assertTrue(deleted.message, deleted.success)
        assertFalse(directory.exists())

        val entry = store.snapshot().single()
        assertEquals(EntryType.DIRECTORY, entry.entryType)
        assertEquals(5L, entry.sizeBytes)
        assertTrue(File(entry.trashPath).isDirectory)

        val restored = service.restore(entry.trashId)
        assertTrue(restored.message, restored.success)
        assertEquals("abc", File(directory, "nested/a.txt").readText())
        assertEquals("de", File(directory, "b.txt").readText())
    }

    @Test
    fun restoreRefusesExistingOriginalPath() = runBlocking {
        val file = File(root, "a.txt")
        TestFiles.write(file, "old")

        assertTrue(service.softDelete(file.absolutePath).success)
        val entry = store.snapshot().single()
        TestFiles.write(file, "new-content")

        val restored = service.restore(entry.trashId)
        assertFalse(restored.success)
        assertEquals("ALREADY_EXISTS", restored.errorCode)
        assertEquals("new-content", file.readText())
        assertTrue(File(entry.trashPath).exists())
        assertEquals(TrashStatus.ACTIVE, store.get(entry.trashId)!!.status)
    }

    @Test
    fun restoreMissingRecordFails() = runBlocking {
        val result = service.restore("missing")
        assertFalse(result.success)
        assertEquals("TRASH_NOT_FOUND", result.errorCode)
        assertTrue(auditLogger.entries.last().operation == "RESTORE")
        assertTrue(auditLogger.entries.last().status == AuditStatus.FAILED)
    }

    @Test
    fun softDeleteOutsideRootIsRejected() = runBlocking {
        val outside = temporaryFolder.newFile("outside.txt")
        TestFiles.write(outside, "outside")

        val result = service.softDelete(outside.absolutePath)
        assertFalse(result.success)
        assertEquals("OUTSIDE_ROOT", result.errorCode)
        assertTrue(outside.exists())
        assertTrue(store.snapshot().isEmpty())
        assertEquals(AuditStatus.FAILED, auditLogger.entries.last().status)
    }

    @Test
    fun batchLimitRejectsWholeBatch() = runBlocking {
        val limited = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = store,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-limited" }, clock = { 1L }),
            maxBatchItems = 2,
            idGenerator = { "trash-limited" }
        )
        val files = (1..3).map { index ->
            File(root, "f$index.txt").also { TestFiles.write(it, "x") }
        }

        val batch = limited.softDeleteBatch(files.map { it.absolutePath })
        assertEquals(1, batch.results.size)
        assertEquals("BATCH_TOO_LARGE", batch.results.first().errorCode)
        assertTrue(files.all { it.exists() })
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun trashPathIsNotBrowsable() = runBlocking {
        val file = File(root, "a.txt")
        TestFiles.write(file, "x")
        assertTrue(service.softDelete(file.absolutePath).success)
        val trashPath = store.snapshot().single().trashPath

        assertTrue(guard.isWithinInternalRoot(trashPath))
        val error = try {
            guard.guardForRead(trashPath)
            null
        } catch (e: FileGuardException) {
            e
        }
        assertNotNull(error)
        assertEquals("INTERNAL_PATH_FORBIDDEN", error!!.stableCode)
    }

    @Test
    fun permanentDeleteRemovesPhysicalFileAndDatabaseRow() = runBlocking {
        val file = File(root, "purge-me.txt")
        TestFiles.write(file, "content")
        assertTrue(service.softDelete(file.absolutePath).success)
        val entry = store.snapshot().single()

        val result = service.deletePermanently(entry.trashId)

        assertTrue(result.message, result.success)
        assertFalse(File(entry.trashPath).exists())
        assertEquals(null, store.get(entry.trashId))
        assertEquals("PERMANENT_DELETE", auditLogger.entries.last().operation)
        assertEquals(AuditStatus.SUCCESS, auditLogger.entries.last().status)
    }

    @Test
    fun emptyTrashDeletesAllPhysicalFilesAndRows() = runBlocking {
        val first = File(root, "one.txt")
        val second = File(root, "two.txt")
        TestFiles.write(first, "one")
        TestFiles.write(second, "two")
        assertTrue(service.softDelete(first.absolutePath).success)
        assertTrue(service.softDelete(second.absolutePath).success)
        val entries = store.snapshot()

        val batch = service.emptyTrash()

        assertEquals(2, batch.results.size)
        assertTrue(batch.results.all { it.success })
        assertTrue(entries.none { File(it.trashPath).exists() })
        assertTrue(store.snapshot().isEmpty())
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun cleanupExpiredOnlyDeletesEntriesOlderThanRetention() = runBlocking {
        var now = 1_000L
        val sequence = AtomicInteger()
        val cleanupService = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = store,
            audit = AuditTrail(
                logger = auditLogger,
                idGenerator = { "audit-cleanup-" + sequence.incrementAndGet() },
                clock = { now }
            ),
            clock = { now },
            idGenerator = { "trash-cleanup-" + sequence.incrementAndGet() }
        )

        val old = File(root, "old.txt")
        TestFiles.write(old, "old")
        assertTrue(cleanupService.softDelete(old.absolutePath).success)
        val oldEntry = store.snapshot().single()

        now += 29L * 24L * 60L * 60L * 1000L
        val young = File(root, "young.txt")
        TestFiles.write(young, "young")
        assertTrue(cleanupService.softDelete(young.absolutePath).success)
        assertEquals(2, store.snapshot().size)

        now += 2L * 24L * 60L * 60L * 1000L
        val batch = cleanupService.cleanupExpired(TrashService.RETENTION_DAYS)

        assertEquals(1, batch.results.size)
        assertTrue(batch.results.single().success)
        assertFalse(File(oldEntry.trashPath).exists())
        assertEquals(1, store.snapshot().size)
        assertTrue(store.snapshot().single().name == "young.txt")
        assertTrue(auditLogger.entries.any { it.operation == "AUTO_CLEANUP" })
    }

    @Test
    fun restoreDirectoryPreservesNestedContent() = runBlocking {
        val directory = File(root, "folder")
        TestFiles.write(File(directory, "a.txt"), "a")
        assertTrue(service.softDelete(directory.absolutePath).success)
        val entry = store.snapshot().single()

        val restored = service.restore(entry.trashId)
        assertTrue(restored.success)
        assertEquals("a", File(directory, "a.txt").readText())
    }

    @Test
    fun hiddenFileCanBeSoftDeletedAndRestored() = runBlocking {
        val file = File(root, ".hidden.txt")
        TestFiles.write(file, "hidden-content")

        val deleted = service.softDelete(file.absolutePath)
        assertTrue(deleted.message, deleted.success)
        assertFalse(file.exists())
        val entry = store.snapshot().single()
        assertEquals(".hidden.txt", entry.name)

        val restored = service.restore(entry.trashId)
        assertTrue(restored.message, restored.success)
        assertTrue(file.exists())
        assertEquals("hidden-content", file.readText())
    }
}
