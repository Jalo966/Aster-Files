package com.aster.files.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.aster.files.data.IntegrityTask
import com.aster.files.data.TaskStatus
import com.aster.files.integrity.IntegrityScope
import com.aster.files.services.AnalysisCategory
import com.aster.files.services.FileService
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.ScopePickerDialog
import com.aster.files.ui.components.fileKindIcon
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.viewmodel.AnalysisViewModel
import com.aster.files.ui.viewmodel.IntegrityViewModel

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AnalysisScreen(
    analysisViewModel: AnalysisViewModel,
    integrityViewModel: IntegrityViewModel,
    fileService: FileService,
    rootPath: String,
    snackbarHostState: SnackbarHostState,
    onStartIntegrity: (IntegrityScope) -> Unit,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by analysisViewModel.state.collectAsState()
    val integrityState by integrityViewModel.state.collectAsState()
    var showScopePicker by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            analysisViewModel.clearError()
        }
    }
    LaunchedEffect(integrityState.message, integrityState.error) {
        val message = integrityState.message ?: integrityState.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            integrityViewModel.consumeMessage()
            integrityViewModel.clearError()
        }
    }

    val listState = rememberLazyListState()
    val gestureScope = rememberCoroutineScope()
    val pullState = rememberPullRefreshState(
        refreshing = state.loading,
        onRefresh = { analysisViewModel.analyze() }
    )
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        gestureScope.launch { listState.animateScrollToItem(0) }
                    })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "分析",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = analysisViewModel::analyze) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新分析")
            }
        }

        if (state.loading || integrityState.creatingTask) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.weight(1f).pullRefresh(pullState)
        ) {
        PullRefreshIndicator(
            refreshing = state.loading,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val result = state.result
            if (result == null) {
                item {
                    if (state.loading) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在分析存储…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        EmptyState(
                            title = "分析未完成",
                            description = state.error ?: "点击右上角刷新重试",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("常规存储分析", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Metric("文件", result.fileCount.toString())
                                Metric("目录", result.directoryCount.toString())
                                Metric("大小", formatBytes(result.totalBytes))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "照片 " + result.photoCount +
                                    " · 视频 " + result.videoCount +
                                    " · 文档 " + count(result.categoryCounts, AnalysisCategory.DOCUMENT) +
                                    " · 音频 " + count(result.categoryCounts, AnalysisCategory.AUDIO),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "压缩包 " + count(result.categoryCounts, AnalysisCategory.ARCHIVE) +
                                    " · 代码 " + count(result.categoryCounts, AnalysisCategory.CODE) +
                                    " · 其他 " + count(result.categoryCounts, AnalysisCategory.OTHER),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (result.truncated) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "已达到 " + result.maxItems + " 项遍历上限，结果已截断。",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (result.largestFiles.isNotEmpty()) {
                    item {
                        Text("最大的文件", style = MaterialTheme.typography.titleMedium)
                    }
                    items(result.largestFiles, key = { it.path }) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = fileKindIcon(
                                    com.aster.files.services.FileClassifier.kindOf(
                                        file.name,
                                        false
                                    )
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = file.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatBytes(file.sizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "照片/视频完整性分析",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "仅手动触发。选择文件或目录并确认范围后，才会创建扫描任务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { showScopePicker = true },
                                enabled = !integrityState.creatingTask,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Analytics, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(if (integrityState.creatingTask) "正在创建任务…" else "选择范围并开始")
                            }
                        }
                    }
                }
            }

            if (integrityState.recentTasks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "最近完整性任务",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showClearHistory = true }) {
                            Text("清除历史")
                        }
                    }
                }
                items(integrityState.recentTasks, key = { it.taskId }) { task ->
                    RecentTaskRow(task = task, onClick = { onOpenTask(task.taskId) })
                }
            }
        }
        }
    }

    if (showClearHistory) {
        ConfirmDialog(
            title = "清除完整性历史",
            text = "将删除所有已完成、已取消和失败的任务及其检测结果，正在运行的任务不受影响。",
            confirmLabel = "清除历史",
            destructive = true,
            onConfirm = {
                showClearHistory = false
                integrityViewModel.clearHistory()
            },
            onDismiss = { showClearHistory = false }
        )
    }

    if (showScopePicker) {
        ScopePickerDialog(
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = { showScopePicker = false },
            onConfirm = { scope ->
                showScopePicker = false
                onStartIntegrity(scope)
            }
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun count(map: Map<AnalysisCategory, Int>, category: AnalysisCategory): Int =
    map[category] ?: 0

@Composable
private fun RecentTaskRow(task: IntegrityTask, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.createdAt.let { formatTime(it) },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "状态：" + statusLabel(task.status) +
                        " · " + task.processedCount + "/" + task.totalCount,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) { Text("查看") }
        }
    }
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "待执行"
    TaskStatus.RUNNING -> "运行中"
    TaskStatus.CANCELLED -> "已取消"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
}
