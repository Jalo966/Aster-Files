package com.aster.files

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aster.files.data.FileEntry
import com.aster.files.services.FileService
import com.aster.files.ui.components.FileThumbnail
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.theme.AsterTheme
import com.aster.files.ui.theme.ThemeMode
import java.io.File

/** SAF 文件选择器：GET_CONTENT / OPEN_DOCUMENT 返回 FileProvider content URI。 */
class SafPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = (application as AsterApplication).container.fileService
        setContent {
            AsterTheme(mode = ThemeMode.SYSTEM) {
                SafPickerScreen(
                    service = service,
                    onClose = { finish() },
                    onPick = { entry -> returnFile(entry) }
                )
            }
        }
    }

    private fun returnFile(entry: FileEntry) {
        val file = File(entry.path)
        val uri = FileProvider.getUriForFile(
            this,
            packageName + ".fileprovider",
            file
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(entry.extension.lowercase())
            ?: "application/octet-stream"
        val result = Intent().apply {
            data = uri
            type = mime
            clipData = ClipData.newUri(contentResolver, "Aster Files", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

@Composable
private fun SafPickerScreen(
    service: FileService,
    onClose: () -> Unit,
    onPick: (FileEntry) -> Unit
) {
    var currentPath by remember { mutableStateOf(service.rootPath) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        runCatching { service.listDirectory(currentPath) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "无法读取目录" }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (currentPath == service.rootPath) onClose()
                else currentPath = File(currentPath).parent ?: service.rootPath
            }) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("选择文件", style = MaterialTheme.typography.titleLarge)
                Text(
                    currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) currentPath = entry.path
                            else onPick(entry)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entry.isDirectory) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        FileThumbnail(entry = entry, size = 40.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (entry.isDirectory) "文件夹" else formatBytes(entry.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
