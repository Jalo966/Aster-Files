package com.aster.files.ui.viewmodel

import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.CheckLevel
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.IntegrityTask
import com.aster.files.data.MediaType
import com.aster.files.data.ReasonCode
import com.aster.files.data.ReasonLevel
import com.aster.files.data.TaskStatus
import com.aster.files.data.TriggerType
import com.aster.files.integrity.IntegrityActionHandler
import com.aster.files.integrity.ManualIntegrityService
import com.aster.files.security.FileGuard
import com.aster.files.services.AuditTrail
import com.aster.files.services.TrashService
import com.aster.files.support.InMemoryIntegrityActionStore
import com.aster.files.support.InMemoryIntegrityStore
import com.aster.files.support.InMemoryTrashStore
import com.aster.files.support.MockDecodeVerifier
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrityViewModelTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard
    private lateinit var store: InMemoryIntegrityStore
    private lateinit var viewModel: IntegrityViewModel
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
        store = InMemoryIntegrityStore()
        val audit = AuditTrail(
            logger = RecordingAuditLogger(),
            idGenerator = { "audit" },
            clock = { 1L }
        )
        val trashService = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = InMemoryTrashStore(),
            audit = audit,
            ioDispatcher = testDispatcher,
            idGenerator = { "trash" }
        )
        val service = ManualIntegrityService(
            guard = guard,
            store = store,
            decodeVerifier = MockDecodeVerifier(),
            audit = audit,
            ioDispatcher = testDispatcher,
            idGenerator = { "id" }
        )
        val actionStore = InMemoryIntegrityActionStore()
        val actionHandler = IntegrityActionHandler(
            guard = guard,
            integrityStore = store,
            actionStore = actionStore,
            trashService = trashService,
            audit = audit,
            ioDispatcher = testDispatcher,
            idGenerator = { "action" }
        )
        viewModel = IntegrityViewModel(
            service = service,
            store = store,
            actionHandler = actionHandler,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun filterAndMixedNormalRejectionAreExposedToUi() = runTest(testDispatcher) {
        val task = IntegrityTask(
            taskId = "task-1",
            scopeJson = "{}",
            createdAt = 1L,
            triggerType = TriggerType.MANUAL,
            status = TaskStatus.COMPLETED,
            totalCount = 2,
            processedCount = 2
        )
        store.insertTask(task)
        store.insertResult(result(task, "normal.jpg", IntegrityStatus.NORMAL))
        store.insertResult(result(task, "bad.jpg", IntegrityStatus.SUSPECTED_CORRUPT))

        viewModel.loadTask(task.taskId)
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.results.size)

        viewModel.setFilter(IntegrityStatus.SUSPECTED_CORRUPT)
        assertEquals(1, viewModel.state.value.visibleResults.size)

        viewModel.setFilter(null)
        viewModel.selectAllVisible()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.normalSelected)

        viewModel.previewAction(com.aster.files.data.ActionOperation.DELETE)
        advanceUntilIdle()
        assertEquals("仅可对失效文件执行此操作", viewModel.state.value.message)
        assertEquals(null, viewModel.state.value.actionPreview)
    }

    private fun result(
        task: IntegrityTask,
        name: String,
        status: IntegrityStatus
    ): IntegrityResult {
        val reasons = if (status == IntegrityStatus.NORMAL) {
            emptyList()
        } else {
            listOf(
                com.aster.files.data.IntegrityReason(
                    ReasonCode.TRUNCATED,
                    "bad",
                    ReasonLevel.ERROR
                )
            )
        }
        return IntegrityResult(
            resultId = name,
            taskId = task.taskId,
            fileId = name,
            filePath = File(root, name).absolutePath,
            fileName = name,
            mediaType = MediaType.PHOTO,
            status = status,
            reasonsJson = AsterJsonCodec.encodeReasons(reasons),
            checksPerformedJson = AsterJsonCodec.encodeChecks(listOf(CheckLevel.L1_METADATA)),
            detectedAt = 1L,
            sizeBytes = 1L,
            readOnlyVerified = true,
            durationMs = 1L
        )
    }
}
