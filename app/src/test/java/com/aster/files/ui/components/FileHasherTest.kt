package com.aster.files.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class FileHasherTest {

    @Test
    fun sha256MatchesKnownDigest() {
        val file = Files.createTempFile("hash-test", ".txt").toFile()
        try {
            file.writeText("hello", Charsets.UTF_8)
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                FileHasher.sha256(file)
            )
        } finally {
            file.delete()
        }
    }
}
