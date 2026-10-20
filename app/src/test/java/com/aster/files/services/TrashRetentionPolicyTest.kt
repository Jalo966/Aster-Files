package com.aster.files.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRetentionPolicyTest {

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun cutoffUsesConfiguredDays() {
        val now = 100L * day
        assertTrue(TrashRetentionPolicy.isExpired(70L * day, now, 30))
        assertFalse(TrashRetentionPolicy.isExpired(71L * day, now, 30))
        assertFalse(TrashRetentionPolicy.isExpired(50L * day, now, 60))
        assertTrue(TrashRetentionPolicy.isExpired(9L * day, now, 90))
    }

    @Test
    fun retentionDaysMustBePositive() {
        var rejected = false
        try {
            TrashRetentionPolicy.cutoff(1_000L, 0)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
