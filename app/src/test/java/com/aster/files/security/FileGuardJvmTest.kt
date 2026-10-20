package com.aster.files.security

import com.aster.files.support.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileGuardJvmTest {

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
    fun rejectsRelativeTraversalAndOutsideRoot() {
        expectGuardError(GuardErrorCode.NOT_ABSOLUTE) {
            guard.guardForRead("relative.txt")
        }
        expectGuardError(GuardErrorCode.PATH_TRAVERSAL) {
            guard.guardForRead(File(root, "../outside.txt").absolutePath)
        }
        val outside = temporaryFolder.newFile("outside.txt")
        expectGuardError(GuardErrorCode.OUTSIDE_ROOT) {
            guard.guardForRead(outside.absolutePath)
        }
    }

    @Test
    fun validatesNamesAndReservedWords() {
        assertEquals("normal.txt", guard.validateName("normal.txt"))
        expectGuardError(GuardErrorCode.INVALID_NAME) { guard.validateName("") }
        expectGuardError(GuardErrorCode.INVALID_NAME) { guard.validateName("..") }
        expectGuardError(GuardErrorCode.INVALID_NAME) { guard.validateName("a.") }
        expectGuardError(GuardErrorCode.INVALID_NAME) { guard.validateName("a ") }
        expectGuardError(GuardErrorCode.PATH_SEPARATOR) { guard.validateName("a/b") }
        expectGuardError(GuardErrorCode.PATH_SEPARATOR) { guard.validateName("a\\b") }
        expectGuardError(GuardErrorCode.RESERVED_NAME) { guard.validateName("CON") }
        expectGuardError(GuardErrorCode.RESERVED_NAME) { guard.validateName("com1.txt") }
    }

    @Test
    fun rootCannotBeMutated() {
        expectGuardError(GuardErrorCode.ROOT_OPERATION_FORBIDDEN) {
            guard.guardForWrite(root.absolutePath)
        }
        expectGuardError(GuardErrorCode.ROOT_OPERATION_FORBIDDEN) {
            guard.prepareDelete(root.absolutePath)
        }
    }

    @Test
    fun targetOverwriteIsRejected() {
        val file = File(root, "a.txt")
        TestFiles.write(file, "a")
        expectGuardError(GuardErrorCode.ALREADY_EXISTS) {
            guard.prepareCreate(root.absolutePath, "a.txt")
        }
    }

    @Test
    fun internalRootIsNotBrowsable() {
        val trashFile = File(trash, "hidden.txt")
        TestFiles.write(trashFile, "hidden")

        assertTrue(guard.isWithinInternalRoot(trashFile.absolutePath))
        assertEquals(false, guard.isWithinRoot(trashFile.absolutePath))
        expectGuardError(GuardErrorCode.INTERNAL_PATH_FORBIDDEN) {
            guard.guardForRead(trashFile.absolutePath)
        }
        assertTrue(guard.guardForInternalRead(trashFile.absolutePath).canonicalPath.endsWith("hidden.txt"))
    }

    @Test
    fun internalCreateAndVerification() {
        val plan = guard.prepareInternalCreate(trash.absolutePath, "files")
        guard.verifyCreationTarget(plan)
        Files.createDirectory(plan.target.toPath())

        val created = guard.guardInternalTargetDirectory(plan.targetPath)
        assertTrue(created.isDirectory)
        assertTrue(created.isInternalPath)
        expectGuardError(GuardErrorCode.INTERNAL_PATH_FORBIDDEN) {
            guard.guardForRead(plan.targetPath)
        }
    }

    @Test
    fun dotFileNameIsAllowedForBrowsingAndMutation() {
        val hidden = File(root, ".hidden.txt")
        TestFiles.write(hidden, "hidden")

        assertEquals(".hidden.txt", guard.validateName(".hidden.txt"))
        assertEquals(false, guard.guardForRead(hidden.absolutePath).isDirectory)
        assertEquals(false, guard.guardForWrite(hidden.absolutePath).isSymlink)

        val plan = guard.prepareCreate(root.absolutePath, ".new-hidden")
        guard.verifyCreationTarget(plan)
        Files.createFile(plan.target.toPath())
        assertTrue(File(root, ".new-hidden").exists())
    }

    @Test
    fun nestedInternalPathIsForbiddenEvenInsideBrowsableRoot() {
        val asterData = File(root, ".aster-data")
        Files.createDirectory(asterData.toPath())
        val secret = File(asterData, "secret.jpg")
        TestFiles.write(secret, "secret")

        val nestedGuard = TestFiles.guardWithInternal(root, listOf(asterData))
        assertEquals(false, nestedGuard.isWithinRoot(secret.absolutePath))
        assertTrue(nestedGuard.isWithinInternalRoot(secret.absolutePath))

        expectGuardError(GuardErrorCode.INTERNAL_PATH_FORBIDDEN) {
            nestedGuard.guardForRead(secret.absolutePath)
        }
        expectGuardError(GuardErrorCode.INTERNAL_PATH_FORBIDDEN) {
            nestedGuard.guardForWrite(secret.absolutePath)
        }
        expectGuardError(GuardErrorCode.INTERNAL_PATH_FORBIDDEN) {
            nestedGuard.prepareCreate(asterData.absolutePath, "another.jpg")
        }
    }

    @Test
    fun symlinkIsRejectedForMutationButReadable() {
        val target = File(root, "target.txt")
        TestFiles.write(target, "target")
        val link = File(root, "link.txt")
        Assume.assumeTrue(
            "当前文件系统不支持创建符号链接",
            TestFiles.tryCreateSymlink(link, target)
        )

        val readable = guard.guardForRead(link.absolutePath)
        assertTrue(readable.isSymlink)
        assertEquals(target.canonicalPath, readable.canonicalPath)

        expectGuardError(GuardErrorCode.SYMLINK_FORBIDDEN) {
            guard.guardForWrite(link.absolutePath)
        }
    }

    @Test
    fun verifyUnchangedDetectsFileChange() {
        val file = File(root, "changing.txt")
        TestFiles.write(file, "one")
        val guarded = guard.guardForWrite(file.absolutePath)

        TestFiles.write(file, "a much longer content")
        expectGuardError(GuardErrorCode.PATH_CHANGED) {
            guard.verifyUnchanged(guarded)
        }
    }

    @Test
    fun crossRootMovePlanIsInternalTarget() {
        val source = File(root, "move.txt")
        TestFiles.write(source, "move")
        val trashFiles = File(trash, "files")
        Files.createDirectory(trashFiles.toPath())

        val plan = guard.prepareMoveAcrossRoots(
            sourcePath = source.absolutePath,
            targetDirectoryPath = trashFiles.absolutePath,
            newName = "trash-1",
            sourceInternal = false,
            targetInternal = true
        )
        guard.verifyMovePlan(plan)
        assertTrue(plan.target.isInternalPath)
        assertEquals(false, plan.source.isInternalPath)
    }

    private fun expectGuardError(expected: GuardErrorCode, block: () -> Unit) {
        val actual = try {
            block()
            null
        } catch (e: FileGuardException) {
            e
        }
        assertNotNull("应抛出 FileGuardException，期望 " + expected.code, actual)
        assertEquals(expected, actual!!.code)
    }
}
