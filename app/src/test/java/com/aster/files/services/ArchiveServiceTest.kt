package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.security.FileGuard
import com.aster.files.support.RecordingAuditLogger
import com.aster.files.support.TestFiles
import com.aster.files.support.ZipPatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ArchiveServiceTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var guard: FileGuard
    private lateinit var auditLogger: RecordingAuditLogger
    private lateinit var service: ArchiveService

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        guard = TestFiles.guard(root)
        auditLogger = RecordingAuditLogger()
        val audit = AuditTrail(auditLogger, idGenerator = { "audit-zip" }, clock = { 2_000L })
        service = ArchiveService(guard, audit, idGenerator = { "tmp" })
    }


    @Test
    fun advancedEncryptedZipCanBeDetectedExtractedAndPasswordSaved() = runBlocking {
        TestFiles.write(File(root, "secret.txt"), "advanced-secret")
        val passwordStore = object : ZipPasswordStore {
            val values = HashMap<String, String>()
            override suspend fun save(zipPath: String, password: String) {
                values[zipPath] = password
            }
            override suspend fun find(zipPath: String): String? = values[zipPath]
        }
        val advanced = ArchiveService(
            guard,
            AuditTrail(auditLogger, idGenerator = { "audit-advanced" }, clock = { 3_000L }),
            idGenerator = { "advanced" },
            passwordStore = passwordStore
        )
        val archive = File(root, "encrypted.zip")
        val compressionProgress = ArrayList<Float>()
        val result = advanced.zipAdvanced(
            sources = listOf(File(root, "secret.txt").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "encrypted.zip",
            options = ZipOptions(
                compressionLevel = 9,
                password = "s3cret",
                encryption = ZipEncryptionMode.AES_256
            ),
            onProgress = { compressionProgress.add(it) }
        )
        assertTrue(result.message, result.success)
        assertTrue(archive.exists())
        assertTrue(compressionProgress.isNotEmpty())
        assertEquals(1f, compressionProgress.last())
        assertEquals("s3cret", passwordStore.find(archive.absolutePath))
        val info = advanced.inspectZip(archive.absolutePath)
        assertTrue(info.encrypted)
        assertEquals(1, info.entryCount)

        val wrongTarget = File(root, "wrong-out").apply { mkdirs() }
        val wrong = advanced.unzipAdvanced(
            archive.absolutePath,
            wrongTarget.absolutePath,
            password = "wrong",
            deleteSource = false
        )
        assertFalse(wrong.success)

        val target = File(root, "encrypted-out").apply { mkdirs() }
        val extractionProgress = ArrayList<Float>()
        val extracted = advanced.unzipAdvanced(
            archive.absolutePath,
            target.absolutePath,
            password = "s3cret",
            deleteSource = false,
            onProgress = { extractionProgress.add(it) }
        )
        assertTrue(extracted.message, extracted.success)
        assertTrue(extractionProgress.isNotEmpty())
        assertEquals(1f, extractionProgress.last())
        assertEquals("advanced-secret", File(target, "secret.txt").readText())
        assertTrue(auditLogger.entries.any {
            it.operation == "UNZIP_ADVANCED" && it.status == AuditStatus.SUCCESS
        })
    }



    @Test
    fun advancedZipPreservesFolderContents() = runBlocking {
        TestFiles.write(File(root, "folder/one.txt"), "one")
        TestFiles.write(File(root, "folder/nested/two.txt"), "two")
        val archive = File(root, "folder.zip")
        val created = service.zipAdvanced(
            sources = listOf(File(root, "folder").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "folder.zip",
            options = ZipOptions(compressionLevel = 5)
        )
        assertTrue(created.message, created.success)
        val target = File(root, "folder-out").apply { mkdirs() }
        val extracted = service.unzipAdvanced(archive.absolutePath, target.absolutePath)
        assertTrue(extracted.message, extracted.success)
        assertEquals("one", File(target, "folder/one.txt").readText())
        assertEquals("two", File(target, "folder/nested/two.txt").readText())
    }

    @Test
    fun advancedSplitArchiveCanBeCreatedAndExtracted() = runBlocking {
        val content = ByteArray(200 * 1024) { (it % 251).toByte() }
        val source = File(root, "split-source.bin")
        source.writeBytes(content)
        val archive = File(root, "split.zip")
        val created = service.zipAdvanced(
            sources = listOf(source.absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "split.zip",
            options = ZipOptions(compressionLevel = 0, splitSizeBytes = 64L * 1024L)
        )
        assertTrue(created.message, created.success)
        assertTrue(archive.exists())
        assertTrue(service.inspectZip(archive.absolutePath).split)
        val target = File(root, "split-out").apply { mkdirs() }
        val extracted = service.unzipAdvanced(
            archive.absolutePath,
            target.absolutePath,
            deleteSource = true
        )
        assertTrue(extracted.message, extracted.success)
        assertTrue(content.contentEquals(File(target, "split-source.bin").readBytes()))
        assertFalse(archive.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.matches(Regex("split\\.z\\d+")) })
    }


    @Test
    fun advancedSplitArchiveSupportsFolderSource() = runBlocking {
        TestFiles.write(File(root, "splitfolder/one.txt"), "one")
        val created = service.zipAdvanced(
            sources = listOf(File(root, "splitfolder").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "splitfolder.zip",
            options = ZipOptions(compressionLevel = 0, splitSizeBytes = 64L * 1024L)
        )
        assertTrue(created.message, created.success)
        val target = File(root, "splitfolder-out").apply { mkdirs() }
        val extracted = service.unzipAdvanced(
            File(root, "splitfolder.zip").absolutePath,
            target.absolutePath
        )
        assertTrue(extracted.message, extracted.success)
        assertEquals("one", File(target, "splitfolder/one.txt").readText())
    }

    @Test
    fun advancedZipDeletesSourcesAndUnzipDeletesArchive() = runBlocking {
        TestFiles.write(File(root, "victim.txt"), "delete-me")
        val archive = File(root, "with-delete.zip")
        val created = service.zipAdvanced(
            sources = listOf(File(root, "victim.txt").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "with-delete.zip",
            options = ZipOptions(compressionLevel = 5, deleteSources = true)
        )
        assertTrue(created.message, created.success)
        assertFalse(File(root, "victim.txt").exists())
        assertTrue(archive.exists())

        val target = File(root, "delete-out").apply { mkdirs() }
        val extracted = service.unzipAdvanced(
            archive.absolutePath,
            target.absolutePath,
            deleteSource = true
        )
        assertTrue(extracted.message, extracted.success)
        assertFalse(archive.exists())
        assertEquals("delete-me", File(target, "victim.txt").readText())
        assertTrue(auditLogger.entries.any {
            it.operation == "ZIP_DELETE_SOURCE" && it.status == AuditStatus.SUCCESS
        })
        assertTrue(auditLogger.entries.any {
            it.operation == "UNZIP_DELETE_SOURCE" && it.status == AuditStatus.SUCCESS
        })
    }

    @Test
    fun zipCreatesArchiveAndRejectsOverwrite() = runBlocking {
        TestFiles.write(File(root, "a.txt"), "alpha")
        TestFiles.write(File(root, "dir/b.txt"), "beta")

        val result = service.zip(
            sources = listOf(File(root, "a.txt").absolutePath, File(root, "dir").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "archive.zip"
        )
        assertTrue(result.message, result.success)

        val archive = File(root, "archive.zip")
        assertTrue(archive.exists())
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("a.txt"))
            assertTrue(names.contains("dir/"))
            assertTrue(names.contains("dir/b.txt"))
            assertEquals("alpha", zip.getInputStream(zip.getEntry("a.txt")).bufferedReader().readText())
            assertEquals("beta", zip.getInputStream(zip.getEntry("dir/b.txt")).bufferedReader().readText())
        }
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".aster-zip-") })

        val duplicate = service.zip(
            sources = listOf(File(root, "a.txt").absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "archive.zip"
        )
        assertFalse(duplicate.success)
        assertEquals("ALREADY_EXISTS", duplicate.errorCode)
        assertTrue(archive.exists())

        assertTrue(auditLogger.entries.any {
            it.category == AuditCategory.ARCHIVE &&
                it.operation == "ZIP" &&
                it.status == AuditStatus.SUCCESS
        })
    }

    @Test
    fun unzipExtractsSafelyAndRejectsExistingTargets() = runBlocking {
        val archive = File(root, "safe.zip")
        TestFiles.zip(
            archive,
            listOf(
                "a.txt" to "alpha".toByteArray(),
                "dir/" to ByteArray(0),
                "dir/b.txt" to "beta".toByteArray()
            )
        )
        val target = File(root, "out")
        Files.createDirectory(target.toPath())

        val result = service.unzip(archive.absolutePath, target.absolutePath)
        assertTrue(result.message, result.success)
        assertEquals("alpha", File(target, "a.txt").readText())
        assertEquals("beta", File(target, "dir/b.txt").readText())

        val duplicate = service.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(duplicate.success)
        assertEquals("ALREADY_EXISTS", duplicate.errorCode)
        assertEquals("alpha", File(target, "a.txt").readText())
        assertTrue(auditLogger.entries.any {
            it.operation == "UNZIP" && it.status == AuditStatus.SUCCESS
        })
    }

    @Test
    fun unzipRejectsPathTraversalAbsoluteAndBackslash() = runBlocking {
        val cases = listOf(
            "../evil.txt" to "ZIP_PATH_TRAVERSAL",
            "/absolute.txt" to "ZIP_ABSOLUTE_PATH",
            "..\\evil.txt" to "ZIP_BACKSLASH"
        )
        for ((name, expectedCode) in cases) {
            val archive = File(root, "bad-" + expectedCode + ".zip")
            TestFiles.zip(archive, listOf(name to "evil".toByteArray()))
            val target = File(root, "out-" + expectedCode)
            Files.createDirectory(target.toPath())

            val result = service.unzip(archive.absolutePath, target.absolutePath)
            assertFalse("$name should be rejected", result.success)
            assertEquals(expectedCode, result.errorCode)
            assertFalse(File(root, "evil.txt").exists())
            assertEquals(0, target.listFiles()!!.size)
        }
    }

    @Test
    fun unzipRejectsTooManyEntries() = runBlocking {
        val limited = ArchiveService(
            guard = guard,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-limited" }, clock = { 1L }),
            maxEntries = 2
        )
        val archive = File(root, "many.zip")
        TestFiles.zip(
            archive,
            (1..3).map { "f$it.txt" to "x".toByteArray() }
        )
        val target = File(root, "out-many")
        Files.createDirectory(target.toPath())

        val result = limited.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(result.success)
        assertEquals("ZIP_TOO_MANY_ENTRIES", result.errorCode)
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun unzipRejectsTotalSizeBomb() = runBlocking {
        val limited = ArchiveService(
            guard = guard,
            audit = AuditTrail(auditLogger, idGenerator = { "audit-limited" }, clock = { 1L }),
            maxTotalUncompressedBytes = 10
        )
        val archive = File(root, "big.zip")
        TestFiles.zip(archive, listOf("big.txt" to ByteArray(20) { 1 }))
        val target = File(root, "out-big")
        Files.createDirectory(target.toPath())

        val result = limited.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(result.success)
        assertEquals("ZIP_TOO_LARGE", result.errorCode)
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun unzipRejectsSymlinkEntry() = runBlocking {
        val archive = File(root, "symlink.zip")
        TestFiles.zip(archive, listOf("link" to "target".toByteArray()))
        ZipPatcher.setSymlinkEntry(archive, "link")
        val target = File(root, "out-symlink")
        Files.createDirectory(target.toPath())

        val result = service.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(result.success)
        assertEquals("ZIP_SYMLINK", result.errorCode)
        assertFalse(File(target, "link").exists())
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun unzipRejectsEncryptedEntry() = runBlocking {
        val archive = File(root, "encrypted.zip")
        TestFiles.zip(archive, listOf("secret.txt" to "secret".toByteArray()))
        ZipPatcher.setEncryptedFlag(archive, "secret.txt")
        val target = File(root, "out-encrypted")
        Files.createDirectory(target.toPath())

        val result = service.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(result.success)
        assertEquals("ZIP_ENCRYPTED", result.errorCode)
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun unzipCleansUpPartialOutputOnFailure() = runBlocking {
        val archive = File(root, "partial.zip")
        TestFiles.zip(
            archive,
            listOf(
                "a.txt" to "new".toByteArray(),
                "b.txt" to "new".toByteArray()
            )
        )
        val target = File(root, "out-partial")
        Files.createDirectory(target.toPath())
        TestFiles.write(File(target, "b.txt"), "existing")

        val result = service.unzip(archive.absolutePath, target.absolutePath)
        assertFalse(result.success)
        assertEquals("ALREADY_EXISTS", result.errorCode)
        assertFalse(File(target, "a.txt").exists())
        assertEquals("existing", File(target, "b.txt").readText())
    }

    @Test
    fun zipRejectsSymlinkSource() = runBlocking {
        val targetFile = File(root, "target.txt")
        TestFiles.write(targetFile, "target")
        val link = File(root, "link.txt")
        Assume.assumeTrue(
            "当前文件系统不支持创建符号链接",
            TestFiles.tryCreateSymlink(link, targetFile)
        )

        val result = service.zip(
            sources = listOf(link.absolutePath),
            targetDirectory = root.absolutePath,
            zipName = "link.zip"
        )
        assertFalse(result.success)
        assertEquals("SYMLINK_FORBIDDEN", result.errorCode)
        assertFalse(File(root, "link.zip").exists())
    }

    @Test
    fun zipRejectsDuplicateEntryNames() = runBlocking {
        TestFiles.write(File(root, "one/same.txt"), "one")
        TestFiles.write(File(root, "two/same.txt"), "two")

        val result = service.zip(
            sources = listOf(
                File(root, "one/same.txt").absolutePath,
                File(root, "two/same.txt").absolutePath
            ),
            targetDirectory = root.absolutePath,
            zipName = "duplicate.zip"
        )
        assertFalse(result.success)
        assertEquals("ZIP_DUPLICATE_ENTRY", result.errorCode)
        assertFalse(File(root, "duplicate.zip").exists())
    }

    @Test
    fun unzipRejectsZipFileAtTargetDirectory() = runBlocking {
        val archive = File(root, "safe.zip")
        TestFiles.zip(archive, listOf("a.txt" to "a".toByteArray()))

        val result = service.unzip(archive.absolutePath, root.absolutePath)
        assertTrue(result.success)
        assertTrue(File(root, "a.txt").exists())
    }
}
