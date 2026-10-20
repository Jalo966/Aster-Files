package com.aster.files.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConversionPlannerTest {
    @Test
    fun namesOutputWithFormatAndCollisionSuffix() {
        assertEquals("photo_converted.jpg", ConversionPlanner.outputName("photo.png", ImageOutputFormat.JPG))
        assertEquals("photo_converted (1).webp", ConversionPlanner.outputName("photo.png", ImageOutputFormat.WEBP, 1))
        assertEquals(100, ConversionPlanner.normalizedQuality(120))
        assertEquals(0, ConversionPlanner.normalizedQuality(-4))
    }

    @Test
    fun emptyDirectoryScannerFindsOnlyTopLevelEmptyTrees() {
        val root = Files.createTempDirectory("empty-scan").toFile()
        val emptyA = root.resolve("emptyA").apply { mkdirs() }
        val nested = root.resolve("nested").apply { mkdirs() }
        val nestedChild = nested.resolve("child").apply { mkdirs() }
        val withFile = root.resolve("withFile").apply { mkdirs() }
        withFile.resolve("a.txt").writeText("x")
        val result = EmptyDirectoryScanner.find(root).map { it.absolutePath }.toSet()
        assertTrue(result.contains(emptyA.absolutePath))
        assertTrue(!result.contains(nestedChild.absolutePath))
        assertTrue(result.contains(nested.absolutePath))
        assertTrue(!result.contains(withFile.absolutePath))
        root.deleteRecursively()
    }
}
