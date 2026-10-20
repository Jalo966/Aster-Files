package com.aster.files.diagnostics

import com.aster.files.integrity.IntegrityRetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPolicyTest {

    @Test
    fun integrityCutoffIsThreeDays() {
        val now = 10_000_000_000L
        assertEquals(
            now - 3L * IntegrityRetentionPolicy.DAY_MILLIS,
            IntegrityRetentionPolicy.cutoff(now)
        )
    }

    @Test
    fun auditKeepsSevenDaysAndOnlyCleansAfterOneHourBackground() {
        val now = 10_000_000_000L
        assertEquals(
            now - 7L * AuditRetentionPolicy.DAY_MILLIS,
            AuditRetentionPolicy.cutoff(now)
        )
        assertFalse(AuditRetentionPolicy.shouldCleanAfterBackground(now, 0L))
        assertFalse(
            AuditRetentionPolicy.shouldCleanAfterBackground(
                now,
                now - 59L * 60L * 1000L
            )
        )
        assertTrue(
            AuditRetentionPolicy.shouldCleanAfterBackground(
                now,
                now - 61L * 60L * 1000L
            )
        )
    }

    @Test
    fun cacheManagerCountsAndClearsDirectory() {
        val directory = java.nio.file.Files.createTempDirectory("aster-cache").toFile()
        try {
            val nested = java.io.File(directory, "thumbnails").apply { mkdirs() }
            java.io.File(directory, "a.tmp").writeBytes(ByteArray(1024))
            java.io.File(nested, "b.tmp").writeBytes(ByteArray(2048))
            assertTrue(CacheManager.sizeBytes(directory) >= 3072L)
            val freed = CacheManager.clear(directory)
            assertTrue(freed >= 3072L)
            assertEquals(0L, CacheManager.sizeBytes(directory))
            assertTrue(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
