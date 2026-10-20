package com.aster.files.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbRemoteClient(private val node: NetworkNode) : RemoteClient {
    private val uri: URI = runCatching {
        URI(node.address)
    }.getOrElse { URI("smb://" + node.address) }
    private val host: String = uri.host ?: throw RemoteException("SMB 地址缺少主机名")
    private val port: Int = if (uri.port > 0) uri.port else 445
    private val segments = (uri.path ?: "").split('/').filter { it.isNotBlank() }
    private val shareName: String = segments.firstOrNull()
        ?: throw RemoteException("SMB 地址需要包含共享名，如 smb://host/ShareName")
    private val basePath: String = if (segments.size <= 1) "/" else
        "/" + segments.drop(1).joinToString("/")

    private suspend fun <T> withShare(block: suspend (DiskShare) -> T): T =
        withContext(Dispatchers.IO) {
            SMBClient().use { client ->
                val connection = client.connect(host, port)
                try {
                    val auth = AuthenticationContext(
                        node.username.ifBlank { "" },
                        node.password.toCharArray(),
                        null
                    )
                    val session = connection.authenticate(auth)
                    try {
                        val share = session.connectShare(shareName)
                        try {
                            if (share !is DiskShare) {
                                throw RemoteException("SMB 共享不是磁盘共享")
                            }
                            block(share)
                        } finally {
                            runCatching { share.close() }
                        }
                    } finally {
                        runCatching { session.logoff() }
                    }
                } finally {
                    runCatching { connection.close() }
                }
            }
        }

    override fun initialPath(): String = basePath

    private fun smbPath(path: String): String {
        val normalized = if (path == "/") basePath else normalizeRemotePath(path)
        return normalized.trim('/').replace('/', '\\')
    }

    override suspend fun list(path: String): List<RemoteEntry> {
        val directory = smbPath(path)
        return withShare { share ->
            share.list(directory).asSequence()
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { item ->
                    val isDirectory = (item.fileAttributes and
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                    RemoteEntry(
                        name = item.fileName,
                        path = remoteJoin(
                            if (path == "/") basePath else normalizeRemotePath(path),
                            item.fileName
                        ),
                        isDirectory = isDirectory,
                        sizeBytes = item.endOfFile,
                        modifiedAt = item.lastWriteTime.toEpochMillis()
                    )
                }
                .sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
        }
    }

    override suspend fun test() {
        withShare { share ->
            share.list(smbPath("/")).size
        }
    }

    override suspend fun download(
        path: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        withShare { share ->
            val file = share.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                val total = file.fileInformation.standardInformation.endOfFile
                file.inputStream.use { input ->
                    copyWithSmbProgress(input, sink, total, onProgress)
                }
            } finally {
                runCatching { file.close() }
            }
        }
    }

    override suspend fun upload(
        file: File,
        remoteDir: String,
        onProgress: (Long, Long) -> Unit
    ) {
        withShare { share ->
            val target = smbPath(remoteJoin(remoteDir, file.name))
            val remote = share.openFile(
                target,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            try {
                remote.outputStream.use { output ->
                    file.inputStream().use { input ->
                        copyWithSmbProgress(input, output, file.length(), onProgress)
                    }
                }
            } finally {
                runCatching { remote.close() }
            }
        }
    }

    override suspend fun createDirectory(path: String) {
        withShare { share -> share.mkdir(smbPath(path)) }
    }

    override suspend fun rename(path: String, newName: String) {
        withShare { share ->
            val source = smbPath(path)
            val info = share.getFileInformation(source)
            val target = smbPath(remoteJoin(remoteParent(path), newName))
            if (target == source) return@withShare
            if (info.standardInformation.isDirectory) {
                copySmbDirectory(share, source, target)
                share.rmdir(source, true)
            } else {
                copySmbFile(share, source, target)
                share.rm(source)
            }
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) {
        withShare { share ->
            if (isDirectory) {
                share.rmdir(smbPath(path), true)
            } else {
                share.rm(smbPath(path))
            }
        }
    }
}

private fun copySmbDirectory(share: DiskShare, source: String, target: String) {
    share.mkdir(target)
    share.list(source).forEach { item ->
        if (item.fileName == "." || item.fileName == "..") return@forEach
        val fileName = item.fileName
        val sourceChild = source.trimEnd('\\') + "\\" + fileName
        val targetChild = target.trimEnd('\\') + "\\" + fileName
        val isDirectory = (item.fileAttributes and
            FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
        if (isDirectory) {
            copySmbDirectory(share, sourceChild, targetChild)
        } else {
            copySmbFile(share, sourceChild, targetChild)
        }
    }
}

private fun copySmbFile(share: DiskShare, source: String, target: String) {
    val from = share.openFile(
        source,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )
    try {
        val to = share.openFile(
            target,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        try {
            from.inputStream.use { input ->
                to.outputStream.use { output -> input.copyTo(output) }
            }
        } finally {
            runCatching { to.close() }
        }
    } finally {
        runCatching { from.close() }
    }
}

private fun copyWithSmbProgress(
    input: java.io.InputStream,
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
