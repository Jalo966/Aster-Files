package com.aster.files.services

import com.aster.files.security.FileGuard
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
import java.nio.file.Files

class AnalysisServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var guard: FileGuard
    private lateinit var service: AnalysisService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        guard = TestFiles.guard(root)
        service = AnalysisService(guard)
    }

    @Test
    fun countsFilesDirectoriesCategoriesAndSize() = runBlocking {
        TestFiles.writeBytes(File(root, "photo.jpg"), ByteArray(10))
        TestFiles.writeBytes(File(root, "video.mp4"), ByteArray(20))
        TestFiles.writeBytes(File(root, "doc.txt"), ByteArray(30))
        TestFiles.writeBytes(File(root, "audio.mp3"), ByteArray(40))
        TestFiles.writeBytes(File(root, "archive.zip"), ByteArray(50))
        TestFiles.writeBytes(File(root, "code.kt"), ByteArray(60))
        TestFiles.writeBytes(File(root, "other.bin"), ByteArray(70))
        Files.createDirectory(File(root, "sub").toPath())
        TestFiles.writeBytes(File(root, "sub/nested.jpg"), ByteArray(80))

        val result = service.analyze(root.absolutePath)

        assertEquals(8, result.fileCount)
        assertEquals(1, result.directoryCount)
        assertEquals(360L, result.totalBytes)
        assertEquals(2, result.photoCount)
        assertEquals(1, result.videoCount)
        assertEquals(2, result.categoryCounts[AnalysisCategory.PHOTO])
        assertEquals(1, result.categoryCounts[AnalysisCategory.VIDEO])
        assertEquals(1, result.categoryCounts[AnalysisCategory.DOCUMENT])
        assertEquals(1, result.categoryCounts[AnalysisCategory.AUDIO])
        assertEquals(1, result.categoryCounts[AnalysisCategory.ARCHIVE])
        assertEquals(1, result.categoryCounts[AnalysisCategory.CODE])
        assertEquals(1, result.categoryCounts[AnalysisCategory.OTHER])
        assertEquals(9, result.scannedItems)
        assertEquals(250_000, result.maxItems)
        assertFalse(result.truncated)
    }

    @Test
    fun returnsOnlyTwentyLargestFiles() = runBlocking {
        val big = File(root, "big")
        Files.createDirectory(big.toPath())
        for (size in 1..25) {
            TestFiles.writeBytes(
                File(big, "f" + size.toString().padStart(2, '0') + ".bin"),
                ByteArray(size)
            )
        }

        val result = service.analyze(root.absolutePath)

        assertEquals(25, result.fileCount)
        assertEquals(20, result.largestFiles.size)
        assertEquals(25L, result.largestFiles.first().sizeBytes)
        assertEquals(6L, result.largestFiles.last().sizeBytes)
        assertTrue(result.largestFiles.zipWithNext().all { (a, b) -> a.sizeBytes >= b.sizeBytes })
    }

    @Test
    fun truncatesAtConfiguredLimit() = runBlocking {
        val limited = AnalysisService(guard, maxItems = 3)
        for (index in 1..5) {
            TestFiles.write(File(root, "f$index.txt"), "x")
        }

        val result = limited.analyze(root.absolutePath)

        assertEquals(3, result.scannedItems)
        assertEquals(3, result.fileCount)
        assertTrue(result.truncated)
        assertEquals(3, result.maxItems)
    }

    @Test
    fun internalPathsAreSkippedButHiddenFilesAreCounted() = runBlocking {
        val asterData = File(root, ".aster-data")
        Files.createDirectory(asterData.toPath())
        TestFiles.writeBytes(File(asterData, "secret.jpg"), ByteArray(100))
        TestFiles.writeBytes(File(root, ".hidden.jpg"), ByteArray(10))
        val internalGuard = TestFiles.guardWithInternal(root, listOf(asterData))
        val internalService = AnalysisService(internalGuard)

        val result = internalService.analyze(root.absolutePath)

        assertEquals(1, result.fileCount)
        assertEquals(1, result.photoCount)
        assertEquals(10L, result.totalBytes)
    }

    @Test
    fun analysisClassHasNoIntegrityDependencies() {
        val stream = AnalysisService::class.java.getResourceAsStream(
            "/com/aster/files/services/AnalysisService.class"
        )
        assertNotNull("找不到 AnalysisService.class", stream)
        val text = stream!!.use { String(it.readBytes(), Charsets.ISO_8859_1) }

        assertFalse(text.contains("Integrity"))
        assertFalse(text.contains("ManualIntegrityService"))
        assertFalse(text.contains("DecodeVerifier"))
        assertFalse(text.contains("MediaProbe"))
        assertFalse(text.contains("NativeDecodeVerifier"))
        assertFalse(text.contains("Repository"))
    }
}
