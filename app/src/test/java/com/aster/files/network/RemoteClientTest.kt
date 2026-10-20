package com.aster.files.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteClientTest {

    @Test
    fun normalizesAndJoinsRemotePaths() {
        assertEquals("/folder/file.txt", normalizeRemotePath("folder//file.txt"))
        assertEquals("/", normalizeRemotePath("/"))
        assertEquals("/a/b", remoteParent("/a/b/c"))
        assertEquals("/", remoteParent("/a"))
        assertEquals("/a/b/file.txt", remoteJoin("/a/b", "file.txt"))
        assertEquals("/file.txt", remoteJoin("/", "file.txt"))
    }

    @Test
    fun classifiesRemoteMedia() {
        val image = RemoteEntry("photo.JPG", "/photo.JPG", false, 1, 0)
        val video = RemoteEntry("movie.mp4", "/movie.mp4", false, 1, 0)
        val folder = RemoteEntry("dir", "/dir", true, 0, 0)
        assertTrue(image.isImage)
        assertTrue(video.isVideo)
        assertFalse(folder.isImage)
        assertFalse(folder.isVideo)
    }

    @Test
    fun parsesWebDavMultiStatusWithBasePath() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat><d:prop>
                  <d:displayname>dav</d:displayname>
                  <d:resourcetype><d:collection/></d:resourcetype>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/photo%20one.jpg</d:href>
                <d:propstat><d:prop>
                  <d:displayname>photo one.jpg</d:displayname>
                  <d:getcontentlength>1234</d:getcontentlength>
                  <d:getlastmodified>Mon, 19 Oct 2026 15:00:00 GMT</d:getlastmodified>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/sub/</d:href>
                <d:propstat><d:prop>
                  <d:displayname>sub</d:displayname>
                  <d:resourcetype><d:collection/></d:resourcetype>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()
        val entries = parseDavEntries(xml, "/dav")
        assertEquals(3, entries.size)
        val file = entries.first { it.name == "photo one.jpg" }
        assertEquals("/photo one.jpg", file.path)
        assertEquals(1234L, file.sizeBytes)
        assertTrue(file.modifiedAt > 0L)
        assertTrue(entries.first { it.name == "sub" }.isDirectory)
    }
}
