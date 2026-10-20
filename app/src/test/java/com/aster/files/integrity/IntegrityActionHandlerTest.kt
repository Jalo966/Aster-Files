package com.aster.files.integrity

import com.aster.files.data.ActionJobStatus
import com.aster.files.data.ActionOperation
import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.AuditCategory
import com.aster.files.data.CheckLevel
import com.aster.files.data.IntegrityActionRequest
import com.aster.files.data.IntegrityReason
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.IntegrityTask
import com.aster.files.data.MediaType
import com.aster.files.data.ReasonCode
import com.aster.files.data.ReasonLevel
import com.aster.files.data.TaskStatus
import com.aster.files.data.TriggerType
import com.aster.files.security.FileGuard
import com.aster.files.services.AuditTrail
import com.aster.files.services.TrashService
import com.aster.files.support.InMemoryIntegrityActionStore
import com.aster.files.support.InMemoryIntegrityStore
import com.aster.files.support.InMemoryTrashStore
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class IntegrityActionHandlerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard
    private lateinit var integrityStore: InMemoryIntegrityStore
    private lateinit var actionStore: InMemoryIntegrityActionStore
    private lateinit var trashStore: InMemoryTrashStore
    private lateinit var auditLogger: RecordingAuditLogger
    private lateinit var handler: IntegrityActionHandler

    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
        integrityStore = InMemoryIntegrityStore()
        actionStore = InMemoryIntegrityActionStore()
        trashStore = InMemoryTrashStore()
        auditLogger = RecordingAuditLogger()
        val audit = AuditTrail(
            logger = auditLogger,
            idGenerator = { "audit-" + sequence.incrementAndGet() },
            clock = { 1_000L }
        )
        val trashService = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = trashStore,
            audit = audit,
            clock = { 2_000L },
            idGenerator = { "trash-" + sequence.incrementAndGet() }
        )
        handler = IntegrityActionHandler(
            guard = guard,
            integrityStore = integrityStore,
            actionStore = actionStore,
            trashService = trashService,
            audit = audit,
            clock = { 3_000L },
            idGenerator = { "action-" + sequence.incrementAndGet() }
        )
    }

    @Test
    fun mixedNormalSelectionIsRejectedAsAWhole() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val normal = addResult(task, "normal.jpg", IntegrityStatus.NORMAL)
        val corrupt = addResult(task, "bad.jpg", IntegrityStatus.SUSPECTED_CORRUPT)

        val error = try {
            handler.preview(
                IntegrityActionRequest(
                    taskId = task.taskId,
                    resultIds = listOf(normal.resultId, corrupt.resultId),
                    operation = ActionOperation.DELETE
                )
            )
            null
        } catch (e: IntegrityActionException) {
            e
        }

        assertNotNull(error)
        assertEquals(IntegrityActionErrorCodes.MIXED_NORMAL, error!!.code)
        assertEquals("仅可对失效文件执行此操作", error.message)
        assertTrue(actionStore.snapshotJobs().isEmpty())
    }

    @Test
    fun deleteUsesSoftDeleteAndWritesAuditPerItem() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val first = addResult(task, "a.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        val second = addResult(task, "b.jpg", IntegrityStatus.UNREADABLE)

        val preview = handler.preview(
            IntegrityActionRequest(
                taskId = task.taskId,
                resultIds = listOf(first.resultId, second.resultId),
                operation = ActionOperation.DELETE,
                softDelete = true
            )
        )
        assertEquals(2, preview.items.size)
        assertEquals(2L, preview.totalBytes)
        assertEquals(trash.absolutePath + File.separator + "files", preview.targetDisplayPath)

        val result = handler.execute(preview, confirmed = true)

        assertEquals(ActionJobStatus.COMPLETED, result.status)
        assertEquals(2, result.processedCount)
        assertFalse(File(root, "a.jpg").exists())
        assertFalse(File(root, "b.jpg").exists())
        assertEquals(2, trashStore.snapshot().size)
        assertEquals(1, actionStore.snapshotJobs().size)
        assertEquals(
            2,
            auditLogger.entries.count { it.category == AuditCategory.INTEGRITY_ACTION }
        )
        val job = actionStore.snapshotJobs().single()
        val items = JSONArray(job.itemsJson)
        assertEquals(2, items.length())
        assertEquals("SUCCESS", items.getJSONObject(0).getString("status"))
    }

    @Test
    fun deleteRejectsPermanentFlag() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val result = addResult(task, "a.jpg", IntegrityStatus.SUSPECTED_CORRUPT)

        val error = try {
            handler.preview(
                IntegrityActionRequest(
                    taskId = task.taskId,
                    resultIds = listOf(result.resultId),
                    operation = ActionOperation.DELETE,
                    softDelete = false
                )
            )
            null
        } catch (e: IntegrityActionException) {
            e
        }
        assertNotNull(error)
        assertEquals(IntegrityActionErrorCodes.SOFT_DELETE_REQUIRED, error!!.code)
    }

    @Test
    fun copyRequiresPreviewConfirmationAndTargetValidation() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val result = addResult(task, "photo.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        val target = File(root, "target")
        Files.createDirectory(target.toPath())

        val preview = handler.preview(
            IntegrityActionRequest(
                taskId = task.taskId,
                resultIds = listOf(result.resultId),
                operation = ActionOperation.COPY,
                targetDirectory = target.absolutePath
            )
        )
        assertEquals(target.canonicalPath, preview.targetDisplayPath)
        assertTrue(preview.items.single().reasons.isNotEmpty())

        val unconfirmed = try {
            handler.execute(preview, confirmed = false)
            null
        } catch (e: IntegrityActionException) {
            e
        }
        assertNotNull(unconfirmed)
        assertEquals(IntegrityActionErrorCodes.CONFIRMATION_REQUIRED, unconfirmed!!.code)
        assertTrue(actionStore.snapshotJobs().isEmpty())

        val copied = handler.execute(preview, confirmed = true)
        assertEquals(ActionJobStatus.COMPLETED, copied.status)
        assertTrue(File(target, "photo.jpg").exists())
        assertTrue(File(root, "photo.jpg").exists())
    }

    @Test
    fun moveTargetMustBeDirectoryAndMovesFile() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val result = addResult(task, "move.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        val target = File(root, "target")
        Files.createDirectory(target.toPath())

        val invalidError = try {
            handler.preview(
                IntegrityActionRequest(
                    taskId = task.taskId,
                    resultIds = listOf(result.resultId),
                    operation = ActionOperation.MOVE,
                    targetDirectory = File(root, "move.jpg").absolutePath
                )
            )
            null
        } catch (e: IntegrityActionException) {
            e
        }
        assertNotNull(invalidError)
        assertEquals(IntegrityActionErrorCodes.TARGET_INVALID, invalidError!!.code)

        val preview = handler.preview(
            IntegrityActionRequest(
                taskId = task.taskId,
                resultIds = listOf(result.resultId),
                operation = ActionOperation.MOVE,
                targetDirectory = target.absolutePath
            )
        )
        val resultStatus = handler.execute(preview, confirmed = true)
        assertEquals(ActionJobStatus.COMPLETED, resultStatus.status)
        assertFalse(File(root, "move.jpg").exists())
        assertTrue(File(target, "move.jpg").exists())
    }

    @Test
    fun runningTaskAndForeignResultsAreRejected() = runBlocking {
        val running = createTask(TaskStatus.RUNNING)
        val runningResult = addResult(running, "running.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        val runningError = try {
            handler.preview(
                IntegrityActionRequest(
                    taskId = running.taskId,
                    resultIds = listOf(runningResult.resultId),
                    operation = ActionOperation.DELETE
                )
            )
            null
        } catch (e: IntegrityActionException) {
            e
        }
        assertNotNull(runningError)
        assertEquals(IntegrityActionErrorCodes.TASK_RUNNING, runningError!!.code)

        val task = createTask(TaskStatus.COMPLETED)
        val foreign = IntegrityResult(
            resultId = "foreign",
            taskId = "another-task",
            fileId = "foreign-file",
            filePath = File(root, "foreign.jpg").absolutePath,
            fileName = "foreign.jpg",
            mediaType = MediaType.PHOTO,
            status = IntegrityStatus.SUSPECTED_CORRUPT,
            reasonsJson = AsterJsonCodec.encodeReasons(emptyList()),
            checksPerformedJson = AsterJsonCodec.encodeChecks(emptyList()),
            detectedAt = 1L,
            sizeBytes = 0L,
            readOnlyVerified = true,
            durationMs = 0L
        )
        integrityStore.insertResult(foreign)
        val foreignError = try {
            handler.preview(
                IntegrityActionRequest(
                    taskId = task.taskId,
                    resultIds = listOf(foreign.resultId),
                    operation = ActionOperation.DELETE
                )
            )
            null
        } catch (e: IntegrityActionException) {
            e
        }
        assertNotNull(foreignError)
        assertEquals(IntegrityActionErrorCodes.RESULT_NOT_FOUND, foreignError!!.code)
    }

    @Test
    fun cancellationStopsAfterCurrentItemAndMarksRemainingCancelled() = runBlocking {
        val task = createTask(TaskStatus.COMPLETED)
        val first = addResult(task, "a.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        val second = addResult(task, "b.jpg", IntegrityStatus.SUSPECTED_CORRUPT)
        actionStore.cancelOnFirstProgress = true

        val preview = handler.preview(
            IntegrityActionRequest(
                taskId = task.taskId,
                resultIds = listOf(first.resultId, second.resultId),
                operation = ActionOperation.DELETE
            )
        )
        val result = handler.execute(preview, confirmed = true)

        assertEquals(ActionJobStatus.CANCELLED, result.status)
        assertEquals(1, result.processedCount)
        assertFalse(File(root, "a.jpg").exists())
        assertTrue(File(root, "b.jpg").exists())
        val job = actionStore.snapshotJobs().single()
        val items = JSONArray(job.itemsJson)
        assertEquals("SUCCESS", items.getJSONObject(0).getString("status"))
        assertEquals("CANCELLED", items.getJSONObject(1).getString("status"))
        assertTrue(auditLogger.entries.any { it.status == com.aster.files.data.AuditStatus.CANCELLED })
    }

    private suspend fun createTask(status: TaskStatus): IntegrityTask {
        val task = IntegrityTask(
            taskId = "task-" + sequence.incrementAndGet(),
            scopeJson = "{}",
            createdAt = 1L,
            triggerType = TriggerType.MANUAL,
            status = status,
            totalCount = 0,
            processedCount = 0
        )
        integrityStore.insertTask(task)
        return task
    }

    private suspend fun addResult(
        task: IntegrityTask,
        name: String,
        status: IntegrityStatus
    ): IntegrityResult {
        val file = File(root, name)
        TestFiles.write(file, "x")
        val reasons = if (status == IntegrityStatus.NORMAL) {
            emptyList()
        } else {
            listOf(
                IntegrityReason(
                    ReasonCode.TRUNCATED,
                    "测试损坏",
                    ReasonLevel.ERROR
                )
            )
        }
        val result = IntegrityResult(
            resultId = "result-" + sequence.incrementAndGet(),
            taskId = task.taskId,
            fileId = file.canonicalPath,
            filePath = file.canonicalPath,
            fileName = name,
            mediaType = MediaType.PHOTO,
            status = status,
            reasonsJson = AsterJsonCodec.encodeReasons(reasons),
            checksPerformedJson = AsterJsonCodec.encodeChecks(listOf(CheckLevel.L1_METADATA)),
            detectedAt = 1L,
            sizeBytes = file.length(),
            readOnlyVerified = true,
            durationMs = 1L
        )
        integrityStore.insertResult(result)
        return result
    }
}
