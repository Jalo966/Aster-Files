package com.aster.files.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTreeTest {

    @Test
    fun buildsTreeAndImplicitDirectories() {
        val tree = ArchiveTreeBuilder.build(
            listOf(
                entry("META-INF/", directory = true),
                entry("META-INF/MANIFEST.MF", size = 10),
                entry("res/drawable/icon.png", size = 20),
                entry("res/values/strings.xml", size = 30),
                entry("classes.dex", size = 40)
            )
        )

        assertEquals(3, tree.children.size)
        assertTrue(tree.children.first { it.name == "META-INF" }.isDirectory)
        assertEquals(1, tree.children.first { it.name == "META-INF" }.children.size)

        val res = tree.children.first { it.name == "res" }
        assertTrue(res.isDirectory)
        assertEquals(2, res.children.size)
        assertTrue(res.children.all { it.isDirectory })
        assertFalse(tree.children.first { it.name == "classes.dex" }.isDirectory)
        assertEquals(40L, tree.children.first { it.name == "classes.dex" }.sizeBytes)
    }

    @Test
    fun findsCurrentFolderAndChildren() {
        val tree = ArchiveTreeBuilder.build(
            listOf(entry("res/drawable/icon.png", size = 20))
        )
        val drawable = ArchiveTreeBuilder.find(tree, "res/drawable")
        assertNotNull(drawable)
        assertEquals(1, ArchiveTreeBuilder.children(tree, "res/drawable").size)
        assertEquals("res", ArchiveTreeBuilder.parentPath("res/drawable"))
        assertEquals("", ArchiveTreeBuilder.parentPath("res"))
    }

    @Test
    fun emptyAndUnknownPathsAreSafe() {
        val tree = ArchiveTreeBuilder.build(emptyList())
        assertEquals(0, tree.children.size)
        assertTrue(ArchiveTreeBuilder.children(tree, "missing").isEmpty())
        assertEquals("", ArchiveTreeBuilder.parentPath(""))
    }

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long = 0L
    ): ZipEntryInfo = ZipEntryInfo(
        name = name,
        isDirectory = directory,
        uncompressedSize = size,
        compressedSize = size,
        crc = 0L,
        method = 8,
        externalAttributes = 0L
    )
}
