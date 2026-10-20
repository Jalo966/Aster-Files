package com.aster.files.network

import androidx.compose.runtime.Immutable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val isImage: Boolean get() = extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    val isVideo: Boolean get() = extension in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v")
    val isAudio: Boolean get() = extension in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg")
}

class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RemoteClient : AutoCloseable {
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun test()
    suspend fun download(
        path: String,
        sink: OutputStream,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    )
    suspend fun upload(
        file: File,
        remoteDir: String,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    )
    suspend fun createDirectory(path: String)
    suspend fun rename(path: String, newName: String)
    suspend fun delete(path: String, isDirectory: Boolean)
    fun httpUrl(path: String): String? = null
    fun authHeaders(): Map<String, String> = emptyMap()
    fun initialPath(): String = "/"
    override fun close() = Unit
}

object NetworkClientFactory {
    fun create(node: NetworkNode): RemoteClient = when (node.protocol) {
        NetworkProtocol.FTP -> FtpRemoteClient(node)
        NetworkProtocol.WEBDAV -> WebDavRemoteClient(node)
        NetworkProtocol.SMB -> SmbRemoteClient(node)
    }
}

fun normalizeRemotePath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
    return "/" + parts.joinToString("/")
}

fun remoteParent(path: String): String {
    val normalized = normalizeRemotePath(path)
    if (normalized == "/") return "/"
    val parent = normalized.substringBeforeLast('/', "")
    return if (parent.isBlank()) "/" else parent
}

fun remoteJoin(parent: String, name: String): String {
    val base = normalizeRemotePath(parent)
    return if (base == "/") "/$name" else "$base/$name"
}

fun remoteHttpUrl(node: NetworkNode, path: String): String? {
    if (node.protocol != NetworkProtocol.WEBDAV) return null
    return runCatching {
        val raw = node.address.let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        val base = raw.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        normalizeRemotePath(path).trim('/').split('/')
            .filter { it.isNotBlank() }
            .forEach { builder.addPathSegment(it) }
        builder.build().toString()
    }.getOrNull()
}

fun remoteAuthHeaders(node: NetworkNode): Map<String, String> {
    val pair = authHeader(node) ?: return emptyMap()
    return mapOf(pair)
}

private fun authHeader(node: NetworkNode): Pair<String, String>? {
    if (node.username.isBlank() && node.password.isBlank()) return null
    val token = Base64.getEncoder().encodeToString(
        (node.username + ":" + node.password).toByteArray(Charsets.UTF_8)
    )
    return "Authorization" to "Basic $token"
}

private class FtpRemoteClient(private val node: NetworkNode) : RemoteClient {
    private val uri: URI = runCatching {
        URI(node.address)
    }.getOrElse {
        URI("ftp://" + node.address)
    }
    private val host: String = uri.host ?: throw RemoteException("FTP 地址缺少主机名")
    private val port: Int = if (uri.port > 0) uri.port else 21
    private val basePath: String = normalizeRemotePath(uri.path.orEmpty())

    private suspend fun <T> withFtp(block: suspend (FTPClient) -> T): T = withContext(Dispatchers.IO) {
        val client = FTPClient().apply {
            connectTimeout = 10_000
            defaultTimeout = 20_000
            dataTimeout = java.time.Duration.ofSeconds(30)
        }
        try {
            client.connect(host, port)
            val username = node.username.ifBlank { "anonymous" }
            val password = if (node.username.isBlank()) "anonymous@" else node.password
            if (!client.login(username, password)) {
                throw RemoteException("FTP 登录失败，请检查用户名和密码")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.bufferSize = 32 * 1024
            block(client)
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("FTP 连接失败：" + (e.message ?: "网络不可用"), e)
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    override fun initialPath(): String = basePath

    override suspend fun list(path: String): List<RemoteEntry> {
        val normalized = if (path == "/") basePath else normalizeRemotePath(path)
        return withFtp { client ->
            client.changeWorkingDirectory(normalized)
            val files = client.listFiles(normalized) ?: emptyArray()
            files.filter { it.name != "." && it.name != ".." }
                .map { file ->
                    RemoteEntry(
                        name = file.name,
                        path = remoteJoin(normalized, file.name),
                        isDirectory = file.isDirectory,
                        sizeBytes = file.size,
                        modifiedAt = file.timestamp?.timeInMillis ?: 0L
                    )
                }
                .sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        }
    }

    override suspend fun test() {
        withFtp { client ->
            if (client.printWorkingDirectory().isNullOrBlank()) {
                throw RemoteException("FTP 已连接但无法读取工作目录")
            }
        }
    }

    override suspend fun download(
        path: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        withFtp { client ->
            val remote = normalizeRemotePath(path)
            val total = client.listFiles(remote)?.firstOrNull()?.size ?: 0L
            client.retrieveFileStream(remote)?.use { input ->
                copyWithProgress(input, sink, total, onProgress)
            } ?: throw RemoteException("FTP 文件不存在或无法读取：$remote")
            if (!client.completePendingCommand()) {
                throw RemoteException("FTP 下载未正常完成")
            }
        }
    }

    override suspend fun createDirectory(path: String) {
        withFtp { client ->
            if (!client.makeDirectory(normalizeRemotePath(path))) {
                throw RemoteException("FTP 新建文件夹失败：" + client.replyString.orEmpty())
            }
        }
    }

    override suspend fun rename(path: String, newName: String) {
        withFtp { client ->
            val source = normalizeRemotePath(path)
            val target = remoteJoin(remoteParent(source), newName)
            if (!client.rename(source, target)) {
                throw RemoteException("FTP 重命名失败：" + client.replyString.orEmpty())
            }
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) {
        withFtp { client ->
            val target = normalizeRemotePath(path)
            if (isDirectory) {
                deleteFtpDirectory(client, target)
            } else if (!client.deleteFile(target)) {
                throw RemoteException("FTP 删除失败：" + client.replyString.orEmpty())
            }
        }
    }

    private fun deleteFtpDirectory(client: FTPClient, path: String) {
        client.listFiles(path).orEmpty().forEach { child ->
            val childPath = remoteJoin(path, child.name)
            if (child.isDirectory) {
                deleteFtpDirectory(client, childPath)
            } else {
                client.deleteFile(childPath)
            }
        }
        if (!client.removeDirectory(path)) {
            throw RemoteException("FTP 删除文件夹失败：" + client.replyString.orEmpty())
        }
    }

    override suspend fun upload(
        file: File,
        remoteDir: String,
        onProgress: (Long, Long) -> Unit
    ) {
        withFtp { client ->
            val dir = normalizeRemotePath(remoteDir)
            if (dir != "/" && !client.changeWorkingDirectory(dir)) {
                throw RemoteException("FTP 目标目录不存在：$dir")
            }
            val target = remoteJoin(dir, file.name)
            file.inputStream().use { input ->
                val counting = CountingInputStream(input, onProgress, file.length())
                if (!client.storeFile(target, counting)) {
                    throw RemoteException("FTP 上传失败：" + client.replyString.orEmpty())
                }
                if (!client.completePendingCommand()) {
                    throw RemoteException("FTP 上传未正常完成")
                }
            }
        }
    }
}

private class WebDavRemoteClient(private val node: NetworkNode) : RemoteClient {
    private val baseUrl: HttpUrl = runCatching {
        val raw = node.address.let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        raw.toHttpUrlOrNull() ?: throw RemoteException("WebDAV 地址无效")
    }.getOrElse { throw RemoteException("WebDAV 地址无效", it) }
    private val basePath: String = normalizeRemotePath(baseUrl.encodedPath)
    private val authorization = authHeader(node)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun urlFor(path: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        normalizeRemotePath(path).trim('/').split('/')
            .filter { it.isNotBlank() }
            .forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url(urlFor(path))
        authorization?.let { (name, value) -> builder.header(name, value) }
        return builder
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>""".trimIndent()
        val request = requestBuilder(path)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RemoteException(httpError(response.code))
                }
                val xml = response.body?.string().orEmpty()
                val current = normalizeRemotePath(path)
                parseDavEntries(xml, basePath)
                    .filterNot { it.path == current }
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 连接失败：" + (e.message ?: "网络不可用"), e)
        }
    }

    override suspend fun test() = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val request = requestBuilder("/")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteException(httpError(response.code))
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 连接失败：" + (e.message ?: "网络不可用"), e)
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        val request = requestBuilder(path)
            .method("MKCOL", null)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 405) {
                    throw RemoteException(httpError(response.code))
                }
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 新建文件夹失败：" + (e.message ?: "网络异常"), e)
        }
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val source = normalizeRemotePath(path)
        val target = remoteJoin(remoteParent(source), newName)
        val request = requestBuilder(source)
            .method("MOVE", null)
            .header("Destination", urlFor(target).toString())
            .header("Overwrite", "F")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteException(httpError(response.code))
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 重命名失败：" + (e.message ?: "网络异常"), e)
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).delete().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteException(httpError(response.code))
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 删除失败：" + (e.message ?: "网络异常"), e)
        }
    }

    override suspend fun download(
        path: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteException(httpError(response.code))
                val body = response.body ?: throw RemoteException("WebDAV 返回空文件")
                body.byteStream().use { input ->
                    copyWithProgress(input, sink, body.contentLength(), onProgress)
                }
            }
            Unit
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 下载失败：" + (e.message ?: "网络异常"), e)
        }
    }

    override suspend fun upload(
        file: File,
        remoteDir: String,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val target = remoteJoin(remoteDir, file.name)
        val mediaType = "application/octet-stream".toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, file.length())
                    }
                }
            }
        }
        val request = requestBuilder(target).put(body).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RemoteException(httpError(response.code))
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 上传失败：" + (e.message ?: "网络异常"), e)
        }
    }

    override fun httpUrl(path: String): String = urlFor(path).toString()
    override fun authHeaders(): Map<String, String> =
        authorization?.let { mapOf(it) }.orEmpty()

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun httpError(code: Int): String = when (code) {
        401 -> "WebDAV 认证失败，请检查用户名和密码"
        403 -> "WebDAV 拒绝访问"
        404 -> "WebDAV 路径不存在"
        in 500..599 -> "WebDAV 服务器错误（HTTP $code）"
        else -> "WebDAV 请求失败（HTTP $code）"
    }
}

private fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    total: Long,
    onProgress: (Long, Long) -> Unit
): Long {
    val buffer = ByteArray(32 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        output.write(buffer, 0, read)
        copied += read
        onProgress(copied, total)
    }
    output.flush()
    return copied
}

private class CountingInputStream(
    private val delegate: InputStream,
    private val onProgress: (Long, Long) -> Unit,
    private val total: Long
) : InputStream() {
    private var copied = 0L
    override fun read(): Int = delegate.read().also { if (it >= 0) { copied++; onProgress(copied, total) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) { copied += it; onProgress(copied, total) } }
    override fun close() = delegate.close()
}

internal fun parseDavEntries(xml: String, basePath: String): List<RemoteEntry> {
    if (xml.isBlank()) return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val doc = runCatching { factory.newDocumentBuilder().parse(xml.byteInputStream()) }
        .getOrElse { throw RemoteException("WebDAV 目录响应解析失败", it) }
    val responses = doc.getElementsByTagNameNS("*", "response")
    val result = ArrayList<RemoteEntry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as? Element ?: continue
        val href = response.getElementsByTagNameNS("*", "href")
            .item(0)?.textContent?.trim().orEmpty()
        if (href.isBlank()) continue
        val isDirectory = response.getElementsByTagNameNS("*", "collection").length > 0
        val displayName = response.getElementsByTagNameNS("*", "displayname")
            .item(0)?.textContent?.trim().orEmpty()
        val length = response.getElementsByTagNameNS("*", "getcontentlength")
            .item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
        val modifiedText = response.getElementsByTagNameNS("*", "getlastmodified")
            .item(0)?.textContent?.trim().orEmpty()
        val path = davPath(href, basePath)
        val name = displayName.ifBlank { path.substringAfterLast('/') }
        result.add(
            RemoteEntry(
                name = name,
                path = path,
                isDirectory = isDirectory,
                sizeBytes = length,
                modifiedAt = parseHttpDate(modifiedText)
            )
        )
    }
    return result.sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
}

private fun davPath(href: String, basePath: String): String {
    val decoded = runCatching {
        val uri = URI(href)
        URLDecoder.decode(uri.path ?: href, "UTF-8")
    }.getOrElse { href }
    val withoutBase = if (basePath != "/" && decoded.startsWith(basePath)) {
        decoded.removePrefix(basePath)
    } else {
        decoded
    }
    return normalizeRemotePath(withoutBase)
}

private fun parseHttpDate(value: String): Long {
    if (value.isBlank()) return 0L
    return runCatching {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT")
        formatter.parse(value)?.time ?: 0L
    }.getOrDefault(0L)
}
