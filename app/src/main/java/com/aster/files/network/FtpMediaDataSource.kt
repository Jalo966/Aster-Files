package com.aster.files.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.io.InputStream

/** ExoPlayer 的 FTP DataSource：使用 RETR + REST 支持直接流式播放与基础跳转。 */
@UnstableApi
class FtpMediaDataSource(private val node: NetworkNode) : BaseDataSource(true) {
    private var client: FTPClient? = null
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var remaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("FTP 路径为空")
        val ftp = FTPClient().apply {
            connectTimeout = 10_000
            defaultTimeout = 20_000
            dataTimeout = java.time.Duration.ofSeconds(30)
        }
        try {
            ftp.connect(node.address.toUriHost(), node.address.toUriPort())
            val username = node.username.ifBlank { "anonymous" }
            val password = if (node.username.isBlank()) "anonymous@" else node.password
            if (!ftp.login(username, password)) throw IOException("FTP 登录失败")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.bufferSize = 32 * 1024
            val size = ftp.listFiles(path)?.firstOrNull()?.size ?: C.LENGTH_UNSET.toLong()
            if (dataSpec.position > 0) {
                ftp.setRestartOffset(dataSpec.position)
            }
            stream = ftp.retrieveFileStream(path)
                ?: throw IOException("FTP 无法打开视频流")
            client = ftp
            uri = dataSpec.uri
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                size > 0 -> (size - dataSpec.position).coerceAtLeast(0L)
                else -> C.LENGTH_UNSET.toLong()
            }
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            closeClient()
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val input = stream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), remaining).toInt()
        }
        val read = input.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        runCatching { client?.completePendingCommand() }
        closeClient()
        uri = null
        transferEnded()
    }

    private fun closeClient() {
        val ftp = client
        client = null
        runCatching { ftp?.logout() }
        runCatching { ftp?.disconnect() }
    }

    private fun String.toUriHost(): String =
        Uri.parse(this).host ?: substringAfter("://").substringBefore('/').substringBefore(':')

    private fun String.toUriPort(): Int =
        Uri.parse(this).port.takeIf { it > 0 } ?: 21

    @UnstableApi
    class Factory(private val node: NetworkNode) : DataSource.Factory {
        override fun createDataSource(): DataSource = FtpMediaDataSource(node)
    }
}
