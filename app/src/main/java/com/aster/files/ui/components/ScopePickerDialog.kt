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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.window.Dialog
import com.aster.files.data.FileEntry
import com.aster.files.integrity.IntegrityScope
import com.aster.files.integrity.MediaFormats
import com.aster.files.services.FileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun ScopePickerDialog(
    rootPath: String,
    fileService: FileService,
    onDismiss: () -> Unit,
    onConfirm: (IntegrityScope) -> Unit
) {
    var currentPath by remember { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDirectories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recursive by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        entries = try {
            withContext(Dispatchers.IO) {
                fileService.listDirectory(currentPath, showHidden = true)
                    .filter { it.isDirectory || MediaFormats.isSupported(it.name) }
                    .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
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
                .heightIn(max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("选择扫描范围", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    OutlinedButton(
                        onClick = {
                            selectedDirectories = selectedDirectories + currentPath
                        }
                    ) { Text("选择当前目录") }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("递归扫描子目录", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Switch(checked = recursive, onCheckedChange = { recursive = it })
                }
                Text(
                    text = "已选文件 " + selectedFiles.size + " 个，目录 " +
                        selectedDirectories.size + " 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))

                if (loading) {
                    Text("正在读取…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(entries, key = { it.path }) { entry ->
                            if (entry.isDirectory) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentPath = entry.path }
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
                                    TextButton(
                                        onClick = {
                                            selectedDirectories = selectedDirectories + entry.path
                                        }
                                    ) { Text("选择") }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedFiles = if (entry.path in selectedFiles) {
                                                selectedFiles - entry.path
                                            } else {
                                                selectedFiles + entry.path
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = entry.path in selectedFiles,
                                        onCheckedChange = {
                                            selectedFiles = if (entry.path in selectedFiles) {
                                                selectedFiles - entry.path
                                            } else {
                                                selectedFiles + entry.path
                                            }
                                        }
                                    )
                                    Text(
                                        text = entry.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
                    Button(
                        onClick = {
                            onConfirm(
                                IntegrityScope(
                                    filePaths = selectedFiles.toList(),
                                    directoryPaths = selectedDirectories.toList(),
                                    recursiveDirectories = recursive
                                )
                            )
                        },
                        enabled = selectedFiles.isNotEmpty() || selectedDirectories.isNotEmpty()
                    ) { Text("确认范围并开始扫描") }
                }
            }
        }
    }
}
