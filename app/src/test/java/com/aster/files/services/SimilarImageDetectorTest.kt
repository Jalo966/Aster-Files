package com.aster.files.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarImageDetectorTest {
    private fun info(path: String, size: Long, w: Int, h: Int, t: Long = 0) =
        SimilarImageInfo(path, path.substringAfterLast('/'), size, w, h, t)

    @Test
    fun matchesNameSizeAndExactResolution() {
        val images = listOf(
            info("/a/IMG_001.jpg", 1_000_000, 4000, 3000, 1),
            info("/a/IMG_001(1).jpg", 1_020_000, 4000, 3000, 2),
            info("/a/IMG_001-copy.jpg", 1_200_000, 4000, 3000, 3),
            info("/a/IMG_001(2).jpg", 1_000_000, 3000, 3000, 4)
        )
        val result = SimilarImageDetector.findDuplicates(images).map { it.name }
        assertEquals(listOf("IMG_001(1).jpg"), result)
    }

    @Test
    fun sizeToleranceIsFivePercent() {
        assertTrue(SimilarImageDetector.sizesClose(100, 105))
        assertFalse(SimilarImageDetector.sizesClose(100, 106))
        assertFalse(SimilarImageDetector.sizesClose(0, 0))
    }
}
