package com.aster.files.ui.viewmodel

import com.aster.files.data.FileEntry
import com.aster.files.security.FileGuard
import com.aster.files.services.ArchiveService
import com.aster.files.services.AuditTrail
import com.aster.files.services.FileService
import com.aster.files.services.TrashService
import com.aster.files.support.InMemoryTrashStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trash: File
    private lateinit var guard: FileGuard
    private lateinit var audit: AuditTrail
    private lateinit var fileService: FileService
    private lateinit var trashService: TrashService
    private lateinit var viewModel: FilesViewModel
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        root = temporaryFolder.newFolder("root")
        trash = temporaryFolder.newFolder("trash")
        guard = TestFiles.guard(root, trash)
        audit = AuditTrail(
            RecordingAuditLogger(),
            idGenerator = { "audit" },
            clock = { 1L }
        )
        fileService = FileService(guard, audit, ioDispatcher = testDispatcher)
        trashService = TrashService(
            guard = guard,
            trashRoot = trash,
            trashStore = InMemoryTrashStore(),
            audit = audit,
            ioDispatcher = testDispatcher,
            idGenerator = { "trash" }
        )
        val archiveService = ArchiveService(guard, audit, ioDispatcher = testDispatcher)
        viewModel = FilesViewModel(
            fileService = fileService,
            trashService = trashService,
            archiveService = archiveService,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun hiddenFilesAreFilteredAndCanBeShown() = runTest(testDispatcher) {
        TestFiles.write(File(root, ".hidden.txt"), "hidden")
        TestFiles.write(File(root, "visible.txt"), "visible")

        viewModel.setShowHidden(false)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.entries.any { it.name == ".hidden.txt" })
        assertTrue(viewModel.state.value.entries.any { it.name == "visible.txt" })

        viewModel.setShowHidden(true)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.entries.any { it.name == ".hidden.txt" })
    }

    @Test
    fun hiddenFileCanBeSelectedAndSoftDeleted() = runTest(testDispatcher) {
        val hidden = File(root, ".hidden.txt")
        TestFiles.write(hidden, "hidden")
        viewModel.setShowHidden(true)
        advanceUntilIdle()

        val entry = viewModel.state.value.entries.first { it.name == ".hidden.txt" }
        viewModel.toggleSelection(entry.path)
        assertEquals(1, viewModel.state.value.selectedCount)

        viewModel.delete(listOf(entry.path))
        advanceUntilIdle()
        assertFalse(hidden.exists())
        assertFalse(viewModel.state.value.selectedPaths.contains(entry.path))
    }

    @Test
    fun renameAndTextPreviewUpdateState() = runTest(testDispatcher) {
        val file = File(root, "notes.txt")
        TestFiles.write(file, "hello preview")
        viewModel.setShowHidden(false)
        advanceUntilIdle()

        val entry = viewModel.state.value.entries.first { it.name == "notes.txt" }
        viewModel.rename(entry.path, "renamed.txt")
        advanceUntilIdle()
        assertTrue(File(root, "renamed.txt").exists())

        val renamed = viewModel.state.value.entries.first { it.name == "renamed.txt" }
        viewModel.openPreview(renamed)
        advanceUntilIdle()
        val preview = viewModel.state.value.preview
        assertTrue(preview is FilePreview.Text)
        assertEquals("hello preview", (preview as FilePreview.Text).content)
    }
}
