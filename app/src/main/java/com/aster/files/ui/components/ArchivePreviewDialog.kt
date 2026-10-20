package com.aster.files.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aster.files.data.FileEntry
import com.aster.files.services.ArchiveService
import com.aster.files.services.ArchiveTreeBuilder
import com.aster.files.services.ArchiveTreeNode
import com.aster.files.services.FileClassifier
import com.aster.files.services.ZipInspection
import com.aster.files.ui.viewmodel.FilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * ZIP/APK 树状浏览：按当前目录展示子项，支持上一级、面包屑与文件夹进入。
 * 只读取中央目录；图片按需读取条目字节交给 Coil。
 */
@Composable
fun ArchivePreviewDialog(
    entry: FileEntry,
    archiveService: ArchiveService,
    onDismiss: () -> Unit
) {
    var inspection by remember(entry.path) { mutableStateOf<ZipInspection?>(null) }
    var tree by remember(entry.path) { mutableStateOf<ArchiveTreeNode?>(null) }
    var currentPath by remember(entry.path) { mutableStateOf("") }
    var loading by remember(entry.path) { mutableStateOf(true) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    var imageName by remember(entry.path) { mutableStateOf<String?>(null) }
    var imageBytes by remember(entry.path) { mutableStateOf<ByteArray?>(null) }
    var imageLoading by remember(entry.path) { mutableStateOf(false) }
    var imageError by remember(entry.path) { mutableStateOf<String?>(null) }
    var textName by remember(entry.path) { mutableStateOf<String?>(null) }
    var textContent by remember(entry.path) { mutableStateOf<String?>(null) }
    var textLoading by remember(entry.path) { mutableStateOf(false) }
    var textError by remember(entry.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entry.path) {
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                archiveService.listEntries(entry.path)
            }
            inspection = result
            tree = ArchiveTreeBuilder.build(result.entries)
        } catch (e: Exception) {
            error = e.message ?: "无法读取压缩包"
        }
        loading = false
    }

    BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = ArchiveTreeBuilder.parentPath(currentPath)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                    Icon(
                        Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            inspection?.let { it.entries.size.toString() + " 个条目" }
                                ?: "读取条目…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            currentPath = ArchiveTreeBuilder.parentPath(currentPath)
                        }) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "上一级")
                        }
                    }
                }

                Breadcrumb(
                    currentPath = currentPath,
                    onSelect = { currentPath = it }
                )

                if (loading || imageLoading || textLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                (imageError ?: textError)?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                when {
                    error != null -> Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp)
                    )
                    tree == null -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                    else -> {
                        val children = ArchiveTreeBuilder.children(tree!!, currentPath)
                        if (children.isEmpty()) {
                            Text(
                                "此文件夹为空",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(children, key = { it.path }) { node ->
                                    ArchiveTreeRow(
                                        node = node,
                                        onOpenFolder = { currentPath = node.path },
                                        onOpenText = {
                                            textLoading = true
                                            textError = null
                                            scope.launch {
                                                try {
                                                    val bytes = withContext(Dispatchers.IO) {
                                                        archiveService.readEntryBytes(
                                                            entry.path,
                                                            node.path
                                                        )
                                                    }
                                                    textContent = bytes
                                                        .take(2 * 1024 * 1024)
                                                        .toByteArray()
                                                        .toString(Charsets.UTF_8)
                                                    textName = node.name
                                                } catch (e: Exception) {
                                                    textError = e.message ?: "无法读取条目文本"
                                                } finally {
                                                    textLoading = false
                                                }
                                            }
                                        },
                                        onOpenImage = {
                                            imageLoading = true
                                            imageError = null
                                            scope.launch {
                                                try {
                                                    val bytes = withContext(Dispatchers.IO) {
                                                        archiveService.readEntryBytes(
                                                            entry.path,
                                                            node.path
                                                        )
                                                    }
                                                    imageName = node.name
                                                    imageBytes = bytes
                                                } catch (e: Exception) {
                                                    imageError = e.message ?: "无法读取条目图片"
                                                } finally {
                                                    imageLoading = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val bytes = imageBytes
    val name = imageName
    val text = textContent
    val tName = textName
    if (text != null && tName != null) {
        FilePreviewDialog(
            preview = FilePreview.Text(tName, entry.path + "!/" + tName, text),
            onDismiss = {
                textContent = null
                textName = null
            }
        )
    }

    if (bytes != null && name != null) {
        ImageViewerDialog(
            name = name,
            model = ByteBuffer.wrap(bytes),
            onDismiss = {
                imageBytes = null
                imageName = null
            },
            apngBytes = if (name.endsWith(".png", ignoreCase = true) ||
                name.endsWith(".apng", ignoreCase = true)
            ) {
                bytes
            } else {
                null
            }
        )
    }
}

@Composable
private fun Breadcrumb(currentPath: String, onSelect: (String) -> Unit) {
    val segments = currentPath.split('/').filter { it.isNotEmpty() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onSelect("") }) { Text("根目录") }
        var accumulated = ""
        for (segment in segments) {
            accumulated = if (accumulated.isEmpty()) segment else accumulated + "/" + segment
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onSelect(accumulated) }) {
                Text(
                    segment,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArchiveTreeRow(
    node: ArchiveTreeNode,
    onOpenFolder: () -> Unit,
    onOpenText: () -> Unit,
    onOpenImage: () -> Unit
) {
    val isImage = !node.isDirectory && FileClassifier.isImage(node.name)
    val isText = !node.isDirectory && FileClassifier.isTextPreviewable(node.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    node.isDirectory -> onOpenFolder()
                    isImage -> onOpenImage()
                    isText -> onOpenText()
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                node.isDirectory -> Icons.Outlined.Folder
                isImage -> Icons.Outlined.Image
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (node.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = node.name + if (node.isDirectory) "/" else "",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        if (node.isDirectory) {
            Text(
                node.children.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "进入文件夹",
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                formatBytes(node.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
