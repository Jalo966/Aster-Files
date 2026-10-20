package com.aster.files.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.data.MediaType
import com.aster.files.integrity.IntegrityScope
import com.aster.files.services.DirectoryAnalysisService
import com.aster.files.services.DuplicateGroup
import com.aster.files.services.DuplicateSelectionStrategy
import com.aster.files.services.DuplicateSmartSelector
import com.aster.files.services.FileClassifier
import com.aster.files.services.FileService
import com.aster.files.services.FolderUsage
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.FileActionBottomSheet
import com.aster.files.ui.components.FilePreviewDialog
import com.aster.files.ui.components.FileSheetAction
import com.aster.files.ui.components.SelectionActionBar
import com.aster.files.ui.components.SelectionCheckbox
import com.aster.files.ui.components.SelectionDetailsDialog
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.selection.SelectionState
import com.aster.files.ui.viewmodel.DirectoryAnalysisViewModel
import com.aster.files.ui.viewmodel.FilePreview

@Composable
fun DirectoryAnalysisScreen(
    viewModel: DirectoryAnalysisViewModel,
    path: String,
    rootPath: String,
    fileService: FileService,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSelectPath: (String) -> Unit,
    onStartIntegrity: (IntegrityScope) -> Unit,
    onPlayVideo: (FileEntry) -> Unit = {},
    onOpenText: (FileEntry) -> Unit = {},
    confirmDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var tab by remember(path) { mutableStateOf(0) }
    var showDirectoryPicker by remember(path) { mutableStateOf(false) }
    val selection = remember(path) { SelectionState() }
    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var detailsPaths by remember { mutableStateOf<List<String>?>(null) }
    var pendingDeletePaths by remember { mutableStateOf<List<String>?>(null) }
    var pendingMovePaths by remember { mutableStateOf<List<String>?>(null) }
    var pathDialog by remember { mutableStateOf<String?>(null) }
    var imagePreview by remember { mutableStateOf<FileEntry?>(null) }
    var selectedStrategy by remember { mutableStateOf<DuplicateSelectionStrategy?>(null) }

    fun requestDelete(paths: List<String>) {
        if (paths.isEmpty()) return
        if (confirmDelete) {
            pendingDeletePaths = paths
        } else {
            viewModel.deletePaths(paths)
            selection.clear()
        }
    }

    fun requestMove(paths: List<String>) {
        if (paths.isEmpty()) return
        pendingMovePaths = paths
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            if (selection.active) selection.toggle(entry.path) else onSelectPath(entry.path)
            return
        }
        when {
            entry.kind == FileKind.IMAGE -> imagePreview = entry
            entry.kind == FileKind.VIDEO -> onPlayVideo(entry)
            FileClassifier.isTextPreviewable(entry.name) -> onOpenText(entry)
            entry.isDirectory -> onSelectPath(entry.path)
        }
    }

    fun handleClick(entry: FileEntry) {
        if (selection.active) selection.toggle(entry.path) else openEntry(entry)
    }

    BackHandler {
        if (selection.active) selection.clear() else onBack()
    }

    LaunchedEffect(path) {
        selection.clear()
        tab = 0
        selectedStrategy = null
        viewModel.analyze(path)
    }
    LaunchedEffect(state.error, state.message) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (selection.active) 76.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回文件")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "目录分析",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { showDirectoryPicker = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("分析目录", style = MaterialTheme.typography.labelSmall)
                        Text(
                            state.result?.rootPath ?: path,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Outlined.Edit, contentDescription = "选择目录")
                }
            }

            val result = state.result
            if (state.loading || result == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(10.dp))
                    Text("正在分析当前目录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onStartIntegrity(
                                IntegrityScope(
                                    directoryPaths = listOf(result.rootPath),
                                    recursiveDirectories = true,
                                    mediaTypes = setOf(MediaType.PHOTO)
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("图片完整性检测") }
                    Button(
                        onClick = {
                            onStartIntegrity(
                                IntegrityScope(
                                    directoryPaths = listOf(result.rootPath),
                                    recursiveDirectories = true,
                                    mediaTypes = setOf(MediaType.VIDEO)
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("视频完整性检测") }
                }

                val redundantCount = result.zeroByteFiles.size + result.emptyDirectories.size
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("大文件 " + result.largeFiles.size) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("冗余 " + redundantCount) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("重复 " + result.duplicateGroups.size) })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("文件夹 " + result.folderUsage.size) })
                    Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("新文件 " + result.recentFiles.size) })
                }

                if (result.truncated) {
                    Text(
                        "分析达到上限，结果可能不完整",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                when (tab) {
                    0 -> LargeFilesTab(
                        files = result.largeFiles,
                        selection = selection,
                        onToggle = selection::toggle,
                        onClick = ::handleClick,
                        onLongClick = { actionEntry = it }
                    )
                    1 -> RedundantTab(
                        zeroFiles = result.zeroByteFiles,
                        emptyDirectories = result.emptyDirectories,
                        selection = selection,
                        onClick = ::handleClick,
                        onLongClick = { actionEntry = it }
                    )
                    2 -> DuplicatesTab(
                        groups = result.duplicateGroups,
                        selection = selection,
                        selectedStrategy = selectedStrategy,
                        onApplyStrategy = { strategy ->
                            selectedStrategy = strategy
                            selection.replace(
                                DuplicateSmartSelector.selectForDeletion(
                                    result.duplicateGroups,
                                    strategy
                                )
                            )
                        },
                        onClick = ::handleClick,
                        onLongClick = { actionEntry = it }
                    )
                    3 -> FolderUsageTab(
                        folders = result.folderUsage,
                        directFiles = result.directFiles,
                        selection = selection,
                        onClick = ::handleClick,
                        onLongClick = { actionEntry = it },
                        onOpenFolder = { folder ->
                            if (selection.active) {
                                selection.toggle(folder.path)
                            } else {
                                onSelectPath(folder.path)
                            }
                        }
                    )
                    else -> RecentFilesTab(
                        files = result.recentFiles,
                        selection = selection,
                        onClick = ::handleClick,
                        onLongClick = { actionEntry = it }
                    )
                }
            }
        }

        if (selection.active) {
            SelectionActionBar(
                count = selection.count,
                onMove = { requestMove(selection.selectedPaths.toList()) },
                onDelete = { requestDelete(selection.selectedPaths.toList()) },
                onDetails = { detailsPaths = selection.selectedPaths.toList() },
                onClose = { selection.clear() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showDirectoryPicker) {
        DirectoryPickerDialog(
            initialPath = path,
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = { showDirectoryPicker = false },
            onSelect = { selected ->
                showDirectoryPicker = false
                onSelectPath(selected)
            }
        )
    }

    actionEntry?.let { entry ->
        FileActionBottomSheet(
            title = entry.name,
            actions = listOf(
                FileSheetAction("查看/预览", onClick = { openEntry(entry) }),
                FileSheetAction("移动", Icons.Outlined.DriveFileMove) {
                    requestMove(listOf(entry.path))
                },
                FileSheetAction("删除", Icons.Outlined.Delete, destructive = true) {
                    requestDelete(listOf(entry.path))
                },
                FileSheetAction("详情", Icons.Outlined.Info) {
                    detailsPaths = listOf(entry.path)
                },
                FileSheetAction("查看完整路径", Icons.Outlined.Info) {
                    pathDialog = entry.path
                }
            ),
            onDismiss = { actionEntry = null }
        )
    }

    detailsPaths?.let { paths ->
        SelectionDetailsDialog(
            paths = paths,
            onDismiss = { detailsPaths = null }
        )
    }

    pendingDeletePaths?.let { paths ->
        AlertDialog(
            onDismissRequest = { pendingDeletePaths = null },
            title = { Text("移入回收站") },
            text = { Text("将选中的 " + paths.size + " 项移入回收站？可从回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletePaths = null
                    viewModel.deletePaths(paths)
                    selection.clear()
                }) { Text("移入回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePaths = null }) { Text("取消") }
            }
        )
    }

    pendingMovePaths?.let { paths ->
        DirectoryPickerDialog(
            initialPath = path,
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = { pendingMovePaths = null },
            onSelect = { target ->
                pendingMovePaths = null
                viewModel.movePaths(paths, target)
                selection.clear()
            }
        )
    }

    pathDialog?.let { fullPath ->
        AlertDialog(
            onDismissRequest = { pathDialog = null },
            title = { Text("完整路径") },
            text = {
                SelectionContainer {
                    Text(fullPath)
                }
            },
            confirmButton = {
                TextButton(onClick = { pathDialog = null }) { Text("关闭") }
            }
        )
    }

    imagePreview?.let { entry ->
        FilePreviewDialog(
            preview = FilePreview.Image(entry.name, entry.path),
            onDismiss = { imagePreview = null },
            onExtractFrames = {}
        )
    }
}

@Composable
private fun LargeFilesTab(
    files: List<FileEntry>,
    selection: SelectionState,
    onToggle: (String) -> Unit,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    if (files.isEmpty()) {
        EmptyState(
            "没有大于 10MB 的大文件",
            "当前目录没有大于 10MB 的大文件",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.path }) { file ->
            AnalysisRow(
                entry = file,
                icon = Icons.Outlined.InsertDriveFile,
                trailing = formatBytes(file.sizeBytes),
                selected = file.path in selection.selectedPaths,
                selectionMode = selection.active,
                onToggle = { onToggle(file.path) },
                onClick = { onClick(file) },
                onLongClick = { onLongClick(file) }
            )
        }
    }
}

@Composable
private fun RedundantTab(
    zeroFiles: List<FileEntry>,
    emptyDirectories: List<FileEntry>,
    selection: SelectionState,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    if (zeroFiles.isEmpty() && emptyDirectories.isEmpty()) {
        EmptyState("没有冗余项", "未发现 0 字节文件或空文件夹", modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (zeroFiles.isNotEmpty()) {
            item { Text("0 字节文件（" + zeroFiles.size + "）", style = MaterialTheme.typography.titleSmall) }
            items(zeroFiles, key = { "z:" + it.path }) { file ->
                AnalysisRow(
                    entry = file,
                    icon = Icons.Outlined.InsertDriveFile,
                    trailing = "0 B",
                    selected = file.path in selection.selectedPaths,
                    selectionMode = selection.active,
                    onToggle = { selection.toggle(file.path) },
                    onClick = { onClick(file) },
                    onLongClick = { onLongClick(file) }
                )
            }
        }
        if (emptyDirectories.isNotEmpty()) {
            item { Text("空文件夹（" + emptyDirectories.size + "）", style = MaterialTheme.typography.titleSmall) }
            items(emptyDirectories, key = { "d:" + it.path }) { dir ->
                AnalysisRow(
                    entry = dir,
                    icon = Icons.Outlined.FolderOff,
                    trailing = "空",
                    selected = dir.path in selection.selectedPaths,
                    selectionMode = selection.active,
                    onToggle = { selection.toggle(dir.path) },
                    onClick = { onClick(dir) },
                    onLongClick = { onLongClick(dir) }
                )
            }
        }
    }
}

@Composable
private fun RecentFilesTab(
    files: List<FileEntry>,
    selection: SelectionState,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    if (files.isEmpty()) {
        EmptyState(
            "最近没有新文件",
            "最近 7 天没有新增或修改的文件",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.path }) { file ->
            AnalysisRow(
                entry = file,
                icon = Icons.Outlined.InsertDriveFile,
                trailing = formatBytes(file.sizeBytes),
                meta = formatTime(file.modifiedAt),
                selected = file.path in selection.selectedPaths,
                selectionMode = selection.active,
                onToggle = { selection.toggle(file.path) },
                onClick = { onClick(file) },
                onLongClick = { onLongClick(file) }
            )
        }
    }
}

@Composable
private fun DuplicatesTab(
    groups: List<DuplicateGroup>,
    selection: SelectionState,
    selectedStrategy: DuplicateSelectionStrategy?,
    onApplyStrategy: (DuplicateSelectionStrategy) -> Unit,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    if (groups.isEmpty()) {
        EmptyState("没有疑似重复文件", "按文件大小 + 前 4KB SHA-256 初步筛选", modifier = Modifier.fillMaxSize())
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "快捷选择要清理的副本",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DuplicateSelectionStrategy.values().forEach { strategy ->
                AssistChip(
                    onClick = { onApplyStrategy(strategy) },
                    label = { Text(strategy.label, maxLines = 1) }
                )
            }
        }
        if (selectedStrategy != null) {
            Text(
                "已按“" + selectedStrategy.label + "”选中每组除保留项外的副本；可继续手动调整。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(groups, key = { it.hashPrefix }) { group ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            group.files.size.toString() + " 个疑似重复",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatBytes(group.sizeBytes), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "前 4KB SHA-256：" + group.hashPrefix.take(16) + "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    group.files.forEach { file ->
                        AnalysisRow(
                            entry = file,
                            icon = Icons.Outlined.InsertDriveFile,
                            trailing = formatBytes(file.sizeBytes),
                            selected = file.path in selection.selectedPaths,
                            selectionMode = selection.active,
                            onToggle = { selection.toggle(file.path) },
                            onClick = { onClick(file) },
                            onLongClick = { onLongClick(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderUsageTab(
    folders: List<FolderUsage>,
    directFiles: List<FileEntry>,
    selection: SelectionState,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    onOpenFolder: (FolderUsage) -> Unit
) {
    if (folders.isEmpty() && directFiles.isEmpty()) {
        EmptyState("没有可统计的内容", "当前目录没有直属文件或子文件夹", modifier = Modifier.fillMaxSize())
        return
    }
    val total = folders.sumOf { it.sizeBytes } + directFiles.sumOf { it.sizeBytes }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (folders.isNotEmpty()) {
            item { Text("子文件夹（点击下钻）", style = MaterialTheme.typography.titleSmall) }
            items(folders, key = { "f:" + it.path }) { folder ->
                val entry = folder.asEntry()
                val fraction = if (total > 0) folder.sizeBytes.toFloat() / total else 0f
                AnalysisRow(
                    entry = entry,
                    icon = Icons.Outlined.Folder,
                    trailing = formatBytes(folder.sizeBytes),
                    meta = folder.fileCount.toString() + " 个文件",
                    selected = entry.path in selection.selectedPaths,
                    selectionMode = selection.active,
                    progress = fraction,
                    onToggle = { selection.toggle(entry.path) },
                    onClick = {
                        if (selection.active) selection.toggle(entry.path) else onOpenFolder(folder)
                    },
                    onLongClick = { onLongClick(entry) }
                )
            }
        }
        if (directFiles.isNotEmpty()) {
            item { Text("直属文件", style = MaterialTheme.typography.titleSmall) }
            items(directFiles, key = { "d:" + it.path }) { file ->
                val fraction = if (total > 0) file.sizeBytes.toFloat() / total else 0f
                AnalysisRow(
                    entry = file,
                    icon = Icons.Outlined.InsertDriveFile,
                    trailing = formatBytes(file.sizeBytes),
                    selected = file.path in selection.selectedPaths,
                    selectionMode = selection.active,
                    progress = fraction,
                    onToggle = { selection.toggle(file.path) },
                    onClick = { onClick(file) },
                    onLongClick = { onLongClick(file) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnalysisRow(
    entry: FileEntry,
    icon: ImageVector,
    trailing: String,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    meta: String? = null,
    progress: Float? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (progress != null) {
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(progressColor(progress).copy(alpha = 0.28f))
                    )
                }
            }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta != null) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(trailing, style = MaterialTheme.typography.labelMedium)
                    if (progress != null) {
                        Text(
                            (progress * 100).toInt().toString() + "%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                SelectionCheckbox(
                    checked = selected,
                    selectionMode = selectionMode,
                    onCheckedChange = { onToggle() }
                )
            }
        }
        }
    }
}

private fun progressColor(progress: Float): Color = when {
    progress < 0.2f -> Color(0xFF2E7D32)
    progress < 0.5f -> Color(0xFFEF6C00)
    else -> Color(0xFFC62828)
}

private fun FolderUsage.asEntry(): FileEntry = FileEntry(
    name = name,
    path = path,
    isDirectory = true,
    isSymlink = false,
    sizeBytes = sizeBytes,
    modifiedAt = 0L,
    extension = "",
    kind = FileKind.FOLDER
)
