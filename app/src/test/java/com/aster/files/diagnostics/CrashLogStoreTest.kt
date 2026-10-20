package com.aster.files.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class CrashLogStoreTest {

    @Test
    fun rotatesAtSizeLimitAndKeepsOnlyRecentFiles() {
        val directory = Files.createTempDirectory("crash-logs").toFile()
        try {
            val fixedTime = 1_697_688_000_000L
            val store = CrashLogStore(
                directory = directory,
                maxFileBytes = 300,
                maxFiles = 3,
                clock = { fixedTime }
            )
            repeat(6) { index ->
                val log = store.writeCrash(
                    "worker-$index",
                    IllegalStateException("crash-$index")
                )
                assertNotNull(log)
            }
            val logs = directory.listFiles { file -> file.name.endsWith(".log") }?.toList()
                .orEmpty()
            assertEquals(3, logs.size)
            assertTrue(logs.all { it.length() <= 300L })
            assertTrue(logs.all { it.name.startsWith("crash_") && it.name.endsWith(".log") })
            assertTrue(directory.listFiles()!!.none { it.length() > 300L })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportZipContainsCrashLogs() {
        val directory = Files.createTempDirectory("crash-export").toFile()
        try {
            val store = CrashLogStore(
                directory = directory,
                maxFileBytes = 10_000,
                maxFiles = 5
            )
            store.writeCrash("main", RuntimeException("boom"))
            val zip = File(directory, "export.zip")
            val result = store.exportZip(zip)
            assertNotNull(result)
            ZipFile(zip).use { archive ->
                assertTrue(archive.entries().asSequence().any { it.name.startsWith("crash_") })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyDirectoryExportsNothing() {
        val directory = Files.createTempDirectory("crash-empty").toFile()
        try {
            val store = CrashLogStore(directory)
            assertEquals(0, store.listLogs().size)
            assertEquals(null, store.exportZip(File(directory, "empty.zip")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
