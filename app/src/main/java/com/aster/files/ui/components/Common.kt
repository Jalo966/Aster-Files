package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.data.IntegrityStatus
import com.aster.files.network.NetworkNode
import com.aster.files.services.FileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.DOCUMENT -> Icons.Outlined.Description
    FileKind.ARCHIVE -> Icons.Outlined.Archive
    FileKind.CODE -> Icons.Outlined.Code
    FileKind.OTHER -> Icons.Outlined.InsertDriveFile
}

@Composable
fun StatusBadge(status: IntegrityStatus) {
    val (label, color) = when (status) {
        IntegrityStatus.NORMAL -> "正常" to Color(0xFF2E7D5B)
        IntegrityStatus.SUSPECTED_CORRUPT -> "疑似损坏" to MaterialTheme.colorScheme.secondary
        IntegrityStatus.UNREADABLE -> "不可读" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确认",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (destructive) confirmLabel else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    rootPath: String,
    fileService: FileService,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    networkNodes: List<NetworkNode> = emptyList(),
    onSelectNetwork: ((NetworkNode) -> Unit)? = null
) {
    val context = LocalContext.current
    val recentStore = remember { RecentDirectoriesStore(context) }
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(initialPath) }
    var directories by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recentPaths by remember { mutableStateOf(recentStore.paths()) }
    var showRecents by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        recentPaths = recentStore.record(currentPath)
        loading = true
        error = null
        directories = try {
            withContext(Dispatchers.IO) {
                fileService.listDirectory(currentPath, showHidden = true)
                    .filter { it.isDirectory }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
            }
        } catch (e: Exception) {
            error = e.message ?: "无法读取目录"
            emptyList()
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 520.dp, max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("选择目标目录", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (currentPath != rootPath) {
                                currentPath = File(currentPath).parentFile?.absolutePath ?: rootPath
                            }
                        },
                        enabled = currentPath != rootPath
                    ) { Text("上一级") }
                    OutlinedButton(onClick = { currentPath = rootPath }) { Text("根目录") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { showRecents = true }) { Text("最近访问") }
                        DropdownMenu(
                            expanded = showRecents,
                            onDismissRequest = { showRecents = false }
                        ) {
                            if (recentPaths.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无最近目录") },
                                    onClick = { showRecents = false }
                                )
                            } else {
                                recentPaths.forEach { path ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                path,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            currentPath = path
                                            showRecents = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        newFolderName = ""
                        newFolderError = null
                        showNewFolder = true
                    }) { Text("＋ 新建文件夹") }
                }
                if (networkNodes.isNotEmpty() && onSelectNetwork != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "网络存储（上传到节点根目录）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    networkNodes.forEach { node ->
                        TextButton(
                            onClick = { onSelectNetwork(node) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                node.protocol.label + " · " + node.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("上传", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (loading) {
                    Text("正在读取…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(directories, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    text = entry.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { currentPath = entry.path }) {
                                    Text("进入")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSelect(currentPath) }) { Text("选择此目录") }
                }
            }
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                Column {
                    Text(
                        currentPath,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            newFolderError = null
                        },
                        singleLine = true,
                        label = { Text("文件夹名称") }
                    )
                    newFolderError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newFolderName.isNotBlank() && !loading,
                    onClick = {
                        val name = newFolderName.trim()
                        val parent = currentPath
                        scope.launch {
                            loading = true
                            val result = withContext(Dispatchers.IO) {
                                fileService.createDirectory(parent, name)
                            }
                            loading = false
                            if (result.success) {
                                showNewFolder = false
                                currentPath = File(parent, name).absolutePath
                            } else {
                                newFolderError = result.message
                            }
                        }
                    }
                ) { Text("创建并进入") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("取消") }
            }
        )
    }
}
