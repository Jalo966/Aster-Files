package com.aster.files.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipOptionsTest {

    @Test
    fun gbUnitConvertsTo1024Mb() {
        assertEquals(
            1024L * 1024L * 1024L,
            ZipSizeUnit.GB.toBytes(1)
        )
        assertEquals(1024L * 1024L, ZipSizeUnit.MB.toBytes(1))
    }

    @Test
    fun splitSizeUpperBoundIsEnforced() {
        val options = ZipOptions(
            splitSizeBytes = ZipSizeUnit.MAX_SPLIT_BYTES
        )
        assertTrue(options.splitSizeBytes > 0)
        var rejected = false
        try {
            ZipOptions(splitSizeBytes = ZipSizeUnit.MAX_SPLIT_BYTES + 1L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun unsupportedFormatsAreMarkedAsPlaceholders() {
        assertTrue(ZipFormat.ZIP.supported)
        assertFalse(ZipFormat.SEVEN_Z.supported)
        assertFalse(ZipFormat.TAR_GZ.supported)
        assertTrue(ZipAlgorithm.DEFLATE.supported)
        assertFalse(ZipAlgorithm.BZIP2.supported)
    }
}
