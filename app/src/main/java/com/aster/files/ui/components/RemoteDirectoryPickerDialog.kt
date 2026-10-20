package com.aster.files.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.network.NetworkClientFactory
import com.aster.files.network.NetworkNode
import com.aster.files.network.RemoteEntry
import com.aster.files.network.remoteParent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 选择网络存储上的目标子目录（用于本地上传到网络）。 */
@Composable
fun RemoteDirectoryPickerDialog(
    node: NetworkNode,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(node.id) {
        val initial = withContext(Dispatchers.IO) {
            runCatching {
                NetworkClientFactory.create(node).use { it.initialPath() }
            }.getOrDefault("/")
        }
        currentPath = initial
    }

    LaunchedEffect(currentPath) {
        val path = currentPath ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            directories = withContext(Dispatchers.IO) {
                NetworkClientFactory.create(node).use { client ->
                    client.list(path).filter { it.isDirectory }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "无法读取网络目录"
            directories = emptyList()
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择网络目标目录") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 520.dp)
            ) {
                Text(
                    currentPath ?: "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = currentPath != null && currentPath != "/",
                        onClick = {
                            currentPath?.let { currentPath = remoteParent(it) }
                        }
                    ) { Text("上一级") }
                    OutlinedButton(
                        enabled = currentPath != null,
                        onClick = { currentPath = "/" }
                    ) { Text("根目录") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("正在读取…")
                    }
                    error != null -> Text(error ?: "读取失败", color = MaterialTheme.colorScheme.error)
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(directories, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = entry.path }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    entry.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("进入", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = currentPath != null && !loading,
                onClick = { currentPath?.let(onSelect) }
            ) { Text("上传到此目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
