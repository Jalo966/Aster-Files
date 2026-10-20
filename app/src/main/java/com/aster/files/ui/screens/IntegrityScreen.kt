package com.aster.files.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aster.files.data.ActionOperation
import com.aster.files.data.AsterJsonCodec
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.TaskStatus
import com.aster.files.services.FileService
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.StatusBadge
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.viewmodel.IntegrityViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IntegrityScreen(
    viewModel: IntegrityViewModel,
    fileService: FileService,
    rootPath: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    confirmDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var pendingOperation by remember { mutableStateOf<ActionOperation?>(null) }
    var showTargetPicker by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
            viewModel.clearError()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "完整性任务",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                state.task?.let { task ->
                    Text(
                        "创建于 " + formatTime(task.createdAt) + " · " + statusLabel(task.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val task = state.task
        if (task == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
        Box(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = statusLabel(task.status),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = task.processedCount.toString() + " / " + task.totalCount,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (state.scanning) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = if (task.totalCount == 0) {
                                    0f
                                } else {
                                    task.processedCount.toFloat() / task.totalCount
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = task.currentFile ?: "准备扫描…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = viewModel::cancelScan) { Text("取消扫描") }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "正常 " + countStatus(state.results, IntegrityStatus.NORMAL) +
                                    " · 疑似损坏 " + countStatus(state.results, IntegrityStatus.SUSPECTED_CORRUPT) +
                                    " · 不可读 " + countStatus(state.results, IntegrityStatus.UNREADABLE),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            task.error?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { FilterChip(state.filter == null, { viewModel.setFilter(null) }, { Text("全部 " + state.results.size) }) }
                    item {
                        FilterChip(
                            state.filter == IntegrityStatus.SUSPECTED_CORRUPT,
                            { viewModel.setFilter(IntegrityStatus.SUSPECTED_CORRUPT) },
                            { Text("疑似损坏") }
                        )
                    }
                    item {
                        FilterChip(
                            state.filter == IntegrityStatus.UNREADABLE,
                            { viewModel.setFilter(IntegrityStatus.UNREADABLE) },
                            { Text("不可读") }
                        )
                    }
                    item {
                        FilterChip(
                            state.filter == IntegrityStatus.NORMAL,
                            { viewModel.setFilter(IntegrityStatus.NORMAL) },
                            { Text("正常") }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 " + state.selectedResultIds.size,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::selectAllVisible) { Text("全选") }
                    TextButton(onClick = viewModel::clearSelection) { Text("清空") }
                }

                if (state.visibleResults.isEmpty()) {
                    EmptyState(
                        title = if (state.scanning) "正在扫描" else "没有结果",
                        description = if (state.scanning) "结果会逐项显示在这里" else "此筛选条件下没有文件",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (state.scanning) 24.dp else 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.visibleResults, key = { it.resultId }) { result ->
                            ResultRow(
                                result = result,
                                selected = result.resultId in state.selectedResultIds,
                                onToggle = { viewModel.toggleResultSelection(result.resultId) }
                            )
                        }
                    }
                }
            }

            if (!state.scanning && state.results.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        if (state.normalSelected) {
                            Text(
                                "仅可对失效文件执行此操作",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 " + state.selectedResultIds.size,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    viewModel.previewAction(
                                        ActionOperation.DELETE,
                                        autoConfirm = !confirmDelete
                                    )
                                },
                                enabled = state.selectedResultIds.isNotEmpty() && !state.normalSelected
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                            TextButton(
                                onClick = {
                                    pendingOperation = ActionOperation.COPY
                                    showTargetPicker = true
                                },
                                enabled = state.selectedResultIds.isNotEmpty() && !state.normalSelected
                            ) {
                                Icon(Icons.Outlined.FileCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制")
                            }
                            TextButton(
                                onClick = {
                                    pendingOperation = ActionOperation.MOVE
                                    showTargetPicker = true
                                },
                                enabled = state.selectedResultIds.isNotEmpty() && !state.normalSelected
                            ) {
                                Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("移动")
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showTargetPicker) {
        DirectoryPickerDialog(
            initialPath = rootPath,
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = {
                showTargetPicker = false
                pendingOperation = null
            },
            onSelect = { target ->
                showTargetPicker = false
                val operation = pendingOperation
                pendingOperation = null
                if (operation != null) {
                    viewModel.previewAction(operation, target)
                }
            }
        )
    }

    state.actionPreview?.let { preview ->
        Dialog(
            onDismissRequest = {
                if (!state.actionRunning) viewModel.dismissActionPreview()
            }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = actionTitle(preview.request.operation),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(preview.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "目标：" + preview.targetDisplayPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "共 " + preview.items.size + " 项 · " + formatBytes(preview.totalBytes),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(preview.items, key = { it.resultId }) { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.fileName,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        StatusBadge(item.status)
                                    }
                                    Text(
                                        formatBytes(item.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    item.reasons.forEach { reason ->
                                        Text(
                                            reason.code.name + " · " + reason.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.actionRunning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "正在执行…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                if (state.actionRunning) viewModel.cancelAction()
                                else viewModel.dismissActionPreview()
                            }
                        ) {
                            Text(if (state.actionRunning) "取消动作" else "取消")
                        }
                        Button(
                            onClick = viewModel::confirmAction,
                            enabled = !state.actionRunning
                        ) {
                            Text("确认" + actionVerb(preview.request.operation))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    result: IntegrityResult,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.fileName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                StatusBadge(result.status)
            }
            Text(
                text = formatBytes(result.sizeBytes) + " · " + result.filePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val reasons = AsterJsonCodec.decodeReasons(result.reasonsJson)
            if (reasons.isNotEmpty()) {
                Text(
                    text = reasons.joinToString("；") { it.code.name + " " + it.detail },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun countStatus(results: List<IntegrityResult>, status: IntegrityStatus): Int =
    results.count { it.status == status }

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "待执行"
    TaskStatus.RUNNING -> "扫描中"
    TaskStatus.CANCELLED -> "已取消"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
}

private fun actionTitle(operation: ActionOperation): String = when (operation) {
    ActionOperation.DELETE -> "确认移入回收站"
    ActionOperation.COPY -> "确认复制失效文件"
    ActionOperation.MOVE -> "确认移动失效文件"
}

private fun actionVerb(operation: ActionOperation): String = when (operation) {
    ActionOperation.DELETE -> "删除"
    ActionOperation.COPY -> "复制"
    ActionOperation.MOVE -> "移动"
}
