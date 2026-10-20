package com.aster.files.services

import com.aster.files.data.FileCategory
import com.aster.files.security.FileGuard
import com.aster.files.support.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CategoryServiceTest {

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
    fun scansSixCategoriesAndSkipsHiddenAndUnknownFiles() = runBlocking {
        TestFiles.write(File(root, "photo.jpg"), "jpg")
        TestFiles.write(File(root, "sub/movie.mp4"), "mp4")
        TestFiles.write(File(root, "sub/photo2.png"), "png")
        TestFiles.write(File(root, "song.mp3"), "mp3")
        TestFiles.write(File(root, "document.pdf"), "pdf")
        TestFiles.write(File(root, "installer.apk"), "apk")
        TestFiles.write(File(root, "bundle.zip"), "zip")
        TestFiles.write(File(root, "source.kt"), "code")
        TestFiles.write(File(root, "unknown.bin"), "bin")
        TestFiles.write(File(root, ".hidden.jpg"), "hidden")
        TestFiles.write(File(root, ".hiddenDir/movie.mp4"), "hidden")

        val result = CategoryService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined
        ).scan()

        assertEquals(2, result.count(FileCategory.IMAGE))
        assertEquals(1, result.count(FileCategory.VIDEO))
        assertEquals(1, result.count(FileCategory.AUDIO))
        assertEquals(1, result.count(FileCategory.DOCUMENT))
        assertEquals(1, result.count(FileCategory.APK))
        assertEquals(1, result.count(FileCategory.ARCHIVE))
        assertFalse(result.truncated)
        assertTrue(result.files(FileCategory.IMAGE).none { it.name.startsWith(".") })
        assertTrue(result.files(FileCategory.VIDEO).none { it.path.contains(".hiddenDir") })
        assertTrue(result.scannedItems >= 9)
    }

    @Test
    fun categoryCapMarksResultTruncated() = runBlocking {
        repeat(4) { index ->
            TestFiles.write(File(root, "photo" + index + ".jpg"), "x")
        }

        val result = CategoryService(
            guard = guard,
            ioDispatcher = Dispatchers.Unconfined,
            maxPerCategory = 2
        ).scan()

        assertEquals(2, result.count(FileCategory.IMAGE))
        assertTrue(result.truncated)
    }

    @Test
    fun scanRejectsNonDirectory() = runBlocking {
        val file = File(root, "not-a-dir.jpg")
        TestFiles.write(file, "x")

        val error = runCatching {
            CategoryService(guard, Dispatchers.Unconfined).scan(file.absolutePath)
        }.exceptionOrNull()

        assertTrue(error is com.aster.files.security.FileGuardException)
    }
}
