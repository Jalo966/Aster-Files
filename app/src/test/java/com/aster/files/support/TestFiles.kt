package com.aster.files.support

import com.aster.files.security.FileGuard
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试文件构造工具。 */
object TestFiles {

    fun guard(root: File, trash: File? = null): FileGuard = FileGuard(
        roots = listOf(root),
        internalRoots = listOfNotNull(trash),
        fileSystem = JvmGuardFileSystem()
    )

    fun guardWithInternal(root: File, internalRoots: List<File>): FileGuard = FileGuard(
        roots = listOf(root),
        internalRoots = internalRoots,
        fileSystem = JvmGuardFileSystem()
    )

    fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), content.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(file: File, content: ByteArray) {
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), content)
    }

    fun zip(file: File, entries: List<Pair<String, ByteArray>>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/") && bytes.isNotEmpty()) {
                    output.write(bytes)
                }
                output.closeEntry()
            }
        }
    }

    fun tryCreateSymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: java.nio.file.FileSystemException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
