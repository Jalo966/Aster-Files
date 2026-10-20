package com.aster.files.network

import com.aster.files.support.TestFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files

class LanWebServerTest {

    private lateinit var root: File
    private lateinit var server: LanWebServer
    private val port = 18123

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lan-server").toFile()
        TestFiles.write(File(root, "hello.txt"), "hello over lan")
        File(root, "sub").mkdirs()
        TestFiles.write(File(root, "sub/nested.txt"), "nested")
        server = LanWebServer(port, root.absolutePath, TestFiles.guard(root))
        server.start(1000, true)
        Thread.sleep(150)
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    @Test
    fun browseAndDownloadUseGuard() {
        val page = GET("/")
        assertEquals(200, page.first)
        assertTrue(page.second.contains("hello.txt"))
        assertTrue(page.second.contains("sub"))

        val download = GET("/download?path=" + encode(File(root, "hello.txt").absolutePath))
        assertEquals(200, download.first)
        assertEquals("hello over lan", download.second)
    }

    @Test
    fun traversalOutsideRootIsForbidden() {
        val response = GET("/?path=" + encode("/etc/passwd"))
        assertEquals(403, response.first)
        assertTrue(response.second.contains("访问被拒绝"))
    }

    @Test
    fun deletePostRemovesFile() {
        val target = File(root, "delete-me.txt")
        TestFiles.write(target, "remove")
        val connection = URL(
            "http://127.0.0.1:$port/delete?path=" + encode(root.absolutePath) +
                "&target=" + encode(target.absolutePath)
        ).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }
        val code = connection.responseCode
        connection.disconnect()
        assertEquals(301, code)
        assertFalse(target.exists())
    }

    private fun GET(path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to body
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
