package com.aster.files.integrity

import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.AuditStatus
import com.aster.files.data.CheckLevel
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.ReasonCode
import com.aster.files.data.TaskStatus
import com.aster.files.data.TriggerType
import com.aster.files.security.FileGuard
import com.aster.files.services.AuditTrail
import com.aster.files.support.InMemoryIntegrityStore
import com.aster.files.support.IntegrityFixtures
import com.aster.files.support.MockDecodeVerifier
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ManualIntegrityServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var guard: FileGuard
    private lateinit var store: InMemoryIntegrityStore
    private lateinit var auditLogger: RecordingAuditLogger
    private lateinit var verifier: MockDecodeVerifier
    private lateinit var service: ManualIntegrityService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        guard = TestFiles.guard(root)
        store = InMemoryIntegrityStore()
        auditLogger = RecordingAuditLogger()
        verifier = MockDecodeVerifier()
        val sequence = AtomicInteger()
        val audit = AuditTrail(
            logger = auditLogger,
            idGenerator = { "audit-" + sequence.incrementAndGet() },
            clock = { 1_000L }
        )
        service = ManualIntegrityService(
            guard = guard,
            store = store,
            decodeVerifier = verifier,
            audit = audit,
            clock = { 2_000L },
            idGenerator = { "id-" + sequence.incrementAndGet() }
        )
    }

    @Test
    fun emptyScopeDoesNotCreateTask() = runBlocking {
        val error = try {
            service.createTask(IntegrityScope())
            null
        } catch (e: IntegrityServiceException) {
            e
        }
        assertNotNull(error)
        assertEquals(IntegrityServiceErrorCodes.SCOPE_EMPTY, error!!.code)
        assertTrue(store.snapshotTasks().isEmpty())
    }

    @Test
    fun taskIsPendingAndManualUntilRun() = runBlocking {
        val file = File(root, "photo.png")
        IntegrityFixtures.writePng(file)

        val task = service.createTask(scopeOf(file))

        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(TriggerType.MANUAL, task.triggerType)
        assertEquals(1, task.totalCount)
        assertEquals(0, task.processedCount)
        assertNull(task.currentFile)
        assertTrue(task.scopeJson.contains("resolvedFiles"))
        assertEquals(TaskStatus.PENDING, store.getTask(task.taskId)!!.status)
    }

    @Test
    fun normalScanPersistsResultAndReadOnlyHash() = runBlocking {
        val file = File(root, "photo.png")
        IntegrityFixtures.writePng(file)

        val task = service.startTask(scopeOf(file))

        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(1, task.totalCount)
        assertEquals(1, task.processedCount)
        assertNull(task.currentFile)
        assertEquals(TaskStatus.COMPLETED, store.getTask(task.taskId)!!.status)

        val result = store.resultsFor(task.taskId).single()
        assertEquals(IntegrityStatus.NORMAL, result.status)
        assertTrue(decodeReasons(result).isEmpty())
        assertEquals(
            listOf(CheckLevel.L1_METADATA, CheckLevel.L2_PARSE, CheckLevel.L3_DECODE),
            decodeChecks(result)
        )
        assertNotNull(result.hashBefore)
        assertNotNull(result.hashAfter)
        assertEquals(result.hashBefore, result.hashAfter)
        assertTrue(result.readOnlyVerified)
        assertEquals(1, verifier.calls)
        assertEquals(file.canonicalPath, result.filePath)

        assertTrue(auditLogger.entries.any {
            it.operation == "INTEGRITY_SCAN" && it.status == AuditStatus.SUCCESS
        })
    }

    @Test
    fun zeroByteFileIsUnreadableAndStopsBeforeL2L3() = runBlocking {
        val file = File(root, "empty.jpg")
        IntegrityFixtures.writeEmpty(file)

        val task = service.startTask(scopeOf(file))
        val result = store.resultsFor(task.taskId).single()

        assertEquals(IntegrityStatus.UNREADABLE, result.status)
        assertTrue(decodeReasons(result).any { it.code == ReasonCode.ZERO_BYTE })
        assertEquals(listOf(CheckLevel.L1_METADATA), decodeChecks(result))
        assertEquals(0, verifier.calls)
        assertNotNull(result.hashBefore)
        assertEquals(result.hashBefore, result.hashAfter)
        assertTrue(result.readOnlyVerified)
    }

    @Test
    fun l1AndL2FailuresShortCircuitL3() = runBlocking {
        val truncatedJpeg = File(root, "truncated.jpg")
        IntegrityFixtures.writeJpeg(truncatedJpeg)
        IntegrityFixtures.truncate(truncatedJpeg, 2)

        val badCrcPng = File(root, "bad-crc.png")
        IntegrityFixtures.writePng(badCrcPng)
        IntegrityFixtures.corruptPngCrc(badCrcPng)

        val badExif = File(root, "bad-exif.jpg")
        IntegrityFixtures.writeJpegWithInvalidExif(badExif)

        val truncatedMp4 = File(root, "truncated.mp4")
        IntegrityFixtures.writeTruncatedMp4(truncatedMp4)

        val badMp4 = File(root, "bad-mp4.mp4")
        IntegrityFixtures.writeMp4WithBadStsz(badMp4)

        val task = service.startTask(
            scopeOf(truncatedJpeg, badCrcPng, badExif, truncatedMp4, badMp4)
        )
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(5, task.processedCount)
        assertEquals(0, verifier.calls)

        val results = store.resultsFor(task.taskId).associateBy { it.fileName }

        val jpegResult = results.getValue("truncated.jpg")
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, jpegResult.status)
        assertTrue(decodeReasons(jpegResult).any { it.code == ReasonCode.TRUNCATED })
        assertEquals(listOf(CheckLevel.L1_METADATA), decodeChecks(jpegResult))

        val pngResult = results.getValue("bad-crc.png")
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, pngResult.status)
        assertTrue(decodeReasons(pngResult).any { it.code == ReasonCode.METADATA_ERROR })
        assertTrue(decodeChecks(pngResult).contains(CheckLevel.L2_PARSE))
        assertFalse(decodeChecks(pngResult).contains(CheckLevel.L3_DECODE))

        val exifResult = results.getValue("bad-exif.jpg")
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, exifResult.status)
        assertTrue(decodeReasons(exifResult).any { it.code == ReasonCode.METADATA_ERROR })
        assertTrue(decodeChecks(exifResult).contains(CheckLevel.L2_PARSE))

        val truncatedMp4Result = results.getValue("truncated.mp4")
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, truncatedMp4Result.status)
        assertTrue(decodeReasons(truncatedMp4Result).any { it.code == ReasonCode.TRUNCATED })
        assertEquals(listOf(CheckLevel.L1_METADATA), decodeChecks(truncatedMp4Result))

        val badMp4Result = results.getValue("bad-mp4.mp4")
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, badMp4Result.status)
        assertTrue(decodeReasons(badMp4Result).any { it.code == ReasonCode.METADATA_ERROR })
        assertTrue(decodeChecks(badMp4Result).contains(CheckLevel.L2_PARSE))
    }

    @Test
    fun validMp4PassesL2() = runBlocking {
        val file = File(root, "video.mp4")
        IntegrityFixtures.writeValidMp4(file)

        val task = service.startTask(scopeOf(file))
        val result = store.resultsFor(task.taskId).single()

        assertEquals(IntegrityStatus.NORMAL, result.status)
        assertTrue(decodeChecks(result).containsAll(
            listOf(CheckLevel.L1_METADATA, CheckLevel.L2_PARSE, CheckLevel.L3_DECODE)
        ))
        assertEquals(1, verifier.calls)
    }

    @Test
    fun l3ExceptionMarksDecodeFailed() = runBlocking {
        val file = File(root, "photo.jpg")
        IntegrityFixtures.writeJpeg(file)
        verifier.failure = DecodeVerificationException("codec failed")

        val task = service.startTask(scopeOf(file))
        val result = store.resultsFor(task.taskId).single()

        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, result.status)
        assertTrue(decodeReasons(result).any { it.code == ReasonCode.DECODE_FAILED })
        assertTrue(decodeChecks(result).contains(CheckLevel.L3_DECODE))
        assertEquals(1, verifier.calls)
    }

    @Test
    fun hashMismatchMarksReadErrorAndNotReadOnly() = runBlocking {
        val file = File(root, "photo.jpg")
        IntegrityFixtures.writeJpeg(file)
        verifier.onVerify = { decodedFile ->
            Files.write(decodedFile.toPath(), decodedFile.readBytes() + byteArrayOf(0))
        }

        val task = service.startTask(scopeOf(file))
        val result = store.resultsFor(task.taskId).single()

        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, result.status)
        assertTrue(decodeReasons(result).any { it.code == ReasonCode.READ_ERROR })
        assertFalse(result.readOnlyVerified)
        assertNotNull(result.hashBefore)
        assertNotNull(result.hashAfter)
        assertFalse(result.hashBefore == result.hashAfter)
    }

    @Test
    fun cancelAfterFirstResultKeepsCompletedResults() = runBlocking {
        val files = (1..3).map { index ->
            File(root, "photo-$index.png").also { IntegrityFixtures.writePng(it) }
        }
        store.cancelOnFirstResult = true

        val task = service.startTask(scopeOf(*files.toTypedArray()))

        assertEquals(TaskStatus.CANCELLED, task.status)
        assertEquals(1, task.processedCount)
        assertEquals(1, store.resultsFor(task.taskId).size)
        assertEquals(1, verifier.calls)
        assertEquals(TaskStatus.CANCELLED, store.getTask(task.taskId)!!.status)
        assertTrue(task.cancelRequested)
        assertTrue(auditLogger.entries.any {
            it.operation == "INTEGRITY_SCAN" && it.status == AuditStatus.CANCELLED
        })
    }

    @Test
    fun cancelBeforeRunStopsWithoutResults() = runBlocking {
        val file = File(root, "photo.png")
        IntegrityFixtures.writePng(file)
        val task = service.createTask(scopeOf(file))

        assertTrue(service.requestCancel(task.taskId))
        val cancelled = service.runTask(task.taskId)

        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelled.processedCount)
        assertTrue(store.resultsFor(task.taskId).isEmpty())
        assertEquals(0, verifier.calls)
    }

    @Test
    fun directoryScopeRecursiveAndNonRecursive() = runBlocking {
        val album = File(root, "album")
        TestFiles.writeBytes(File(album, "a.jpg"), ByteArray(4))
        TestFiles.write(File(album, "notes.txt"), "x")
        TestFiles.writeBytes(File(album, "nested/b.png"), ByteArray(4))

        val recursive = service.createTask(
            IntegrityScope(
                directoryPaths = listOf(album.absolutePath),
                recursiveDirectories = true
            )
        )
        assertEquals(2, recursive.totalCount)

        val nonRecursive = service.createTask(
            IntegrityScope(
                directoryPaths = listOf(album.absolutePath),
                recursiveDirectories = false
            )
        )
        assertEquals(1, nonRecursive.totalCount)
    }

    @Test
    fun internalScopeIsRejected() = runBlocking {
        val asterData = File(root, ".aster-data")
        Files.createDirectory(asterData.toPath())
        val secret = File(asterData, "secret.jpg")
        IntegrityFixtures.writeJpeg(secret)

        val internalGuard = TestFiles.guardWithInternal(root, listOf(asterData))
        val internalService = ManualIntegrityService(
            guard = internalGuard,
            store = store,
            decodeVerifier = verifier,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-internal" }, clock = { 1L })
        )

        val error = try {
            internalService.createTask(scopeOf(secret))
            null
        } catch (e: IntegrityServiceException) {
            e
        }
        assertNotNull(error)
        assertEquals(IntegrityServiceErrorCodes.SCOPE_INVALID, error!!.code)
    }

    @Test
    fun progressIsPersistedPerFile() = runBlocking {
        val file = File(root, "photo.png")
        IntegrityFixtures.writePng(file)

        val task = service.startTask(scopeOf(file))

        assertTrue(store.progressHistory.any { it.first == 0 && it.second == file.canonicalPath })
        assertTrue(store.progressHistory.any { it.first == 1 && it.second == null })
        assertEquals(1, store.getTask(task.taskId)!!.processedCount)
    }

    @Test
    fun mkvHeaderBoundaryIsChecked() = runBlocking {
        val valid = File(root, "valid.mkv")
        IntegrityFixtures.writeValidMkv(valid)
        val truncated = File(root, "truncated.mkv")
        IntegrityFixtures.writeTruncatedMkv(truncated)

        val task = service.startTask(scopeOf(valid, truncated))
        val results = store.resultsFor(task.taskId).associateBy { it.fileName }

        assertEquals(IntegrityStatus.NORMAL, results.getValue("valid.mkv").status)
        assertTrue(
            decodeChecks(results.getValue("valid.mkv"))
                .containsAll(listOf(CheckLevel.L1_METADATA, CheckLevel.L3_DECODE))
        )
        assertEquals(IntegrityStatus.SUSPECTED_CORRUPT, results.getValue("truncated.mkv").status)
        assertTrue(
            decodeReasons(results.getValue("truncated.mkv"))
                .any { it.code == ReasonCode.TRUNCATED }
        )
        assertEquals(1, verifier.calls)
    }

    @Test
    fun unsupportedOnlyScopeIsRejected() = runBlocking {
        val text = File(root, "notes.txt")
        TestFiles.write(text, "hello")

        val error = try {
            service.createTask(scopeOf(text))
            null
        } catch (e: IntegrityServiceException) {
            e
        }
        assertNotNull(error)
        assertEquals(IntegrityServiceErrorCodes.SCOPE_EMPTY, error!!.code)
    }

    @Test
    fun interruptedTasksAreMarkedFailed() = runBlocking {
        val file = File(root, "pending.png")
        IntegrityFixtures.writePng(file)
        val task = service.createTask(scopeOf(file))

        val count = service.recoverInterruptedTasks("进程中断")

        assertEquals(1, count)
        val recovered = store.getTask(task.taskId)!!
        assertEquals(TaskStatus.FAILED, recovered.status)
        assertEquals("进程中断", recovered.error)
    }

    @Test
    fun runTaskRejectsMissingAndNonPendingTasks() = runBlocking {
        val missing = try {
            service.runTask("missing")
            null
        } catch (e: IntegrityServiceException) {
            e
        }
        assertNotNull(missing)
        assertEquals(IntegrityServiceErrorCodes.TASK_NOT_FOUND, missing!!.code)

        val file = File(root, "photo.png")
        IntegrityFixtures.writePng(file)
        val task = service.createTask(scopeOf(file))
        assertEquals(TaskStatus.COMPLETED, service.runTask(task.taskId).status)

        val repeated = try {
            service.runTask(task.taskId)
            null
        } catch (e: IntegrityServiceException) {
            e
        }
        assertNotNull(repeated)
        assertEquals(IntegrityServiceErrorCodes.TASK_NOT_PENDING, repeated!!.code)
    }

    private fun scopeOf(vararg files: File): IntegrityScope =
        IntegrityScope(filePaths = files.map { it.absolutePath })

    private fun decodeReasons(result: IntegrityResult) =
        AsterJsonCodec.decodeReasons(result.reasonsJson)

    private fun decodeChecks(result: IntegrityResult) =
        AsterJsonCodec.decodeChecks(result.checksPerformedJson)
}
