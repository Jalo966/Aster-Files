package com.aster.files.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 网络图片读取：WebDAV 使用带认证的 HTTP GET，FTP 通过 RemoteClient 下载字节。 */
object RemoteImageLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val http = OkHttpClient()

    suspend fun load(node: NetworkNode, entry: RemoteEntry): Bitmap? = withContext(Dispatchers.IO) {
        val key = node.id + "|" + entry.path
        cache.get(key)?.let { return@withContext it }
        val bytes = runCatching { bytes(node, entry) }.getOrNull() ?: return@withContext null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    private fun bytes(node: NetworkNode, entry: RemoteEntry): ByteArray {
        val url = remoteHttpUrl(node, entry.path)
        if (url != null) {
            val request = Request.Builder().url(url).apply {
                remoteAuthHeaders(node).forEach { (name, value) -> header(name, value) }
            }.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                return response.body!!.bytes()
            }
        }
        val output = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking {
            NetworkClientFactory.create(node).use { client ->
                client.download(entry.path, output)
            }
        }
        return output.toByteArray()
    }
}

