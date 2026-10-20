package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileEntry
import com.aster.files.network.NetworkClientFactory
import com.aster.files.network.NetworkNode
import com.aster.files.network.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

object FileHasher {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun FilePropertiesDialog(entry: FileEntry, onDismiss: () -> Unit) {
    PropertiesDialog(
        title = entry.name,
        path = entry.path,
        sizeBytes = entry.sizeBytes,
        modifiedAt = entry.modifiedAt,
        isDirectory = entry.isDirectory,
        onDismiss = onDismiss,
        hashProvider = if (entry.isDirectory) null else {
            { FileHasher.sha256(File(entry.path)) }
        }
    )
}

@Composable
fun RemotePropertiesDialog(
    node: NetworkNode,
    entry: RemoteEntry,
    onDismiss: () -> Unit
) {
    PropertiesDialog(
        title = entry.name,
        path = node.name + ": " + entry.path,
        sizeBytes = entry.sizeBytes,
        modifiedAt = entry.modifiedAt,
        isDirectory = entry.isDirectory,
        onDismiss = onDismiss,
        hashProvider = if (entry.isDirectory) null else {
            {
                val digest = MessageDigest.getInstance("SHA-256")
                NetworkClientFactory.create(node).use { client ->
                    client.download(entry.path, DigestSink(digest))
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    )
}

@Composable
private fun PropertiesDialog(
    title: String,
    path: String,
    sizeBytes: Long,
    modifiedAt: Long,
    isDirectory: Boolean,
    hashProvider: (suspend () -> String)?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var hash by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val compute = {
        if (!loading && hashProvider != null) {
            loading = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { hashProvider() }
                }
                loading = false
                if (result.isSuccess) {
                    hash = result.getOrNull()
                    error = null
                } else {
                    hash = null
                    error = result.exceptionOrNull()?.message ?: "哈希计算失败"
                }
            }
        }
        Unit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件属性") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hashProvider != null) {
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("正在计算 SHA-256…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    hash?.let {
                        Text(
                            "SHA-256",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(it)) }) {
                            Text("复制哈希")
                        }
                    }
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                PropertyRow("名称", title)
                PropertyRow("完整路径", path)
                PropertyRow("大小", if (isDirectory) "文件夹" else formatBytes(sizeBytes))
                PropertyRow("修改时间", formatTime(modifiedAt))
            }
        },
        confirmButton = {
            if (hashProvider != null) {
                TextButton(enabled = !loading, onClick = { compute() }) {
                    Text(if (hash == null) "计算 SHA-256" else "重新计算")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (hashProvider != null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(value, style = MaterialTheme.typography.bodySmall)
}

private class DigestSink(private val digest: MessageDigest) : OutputStream() {
    override fun write(b: Int) = digest.update(b.toByte())
    override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
}
