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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.aster.files.data.EntryType
import com.aster.files.data.TrashEntry
import com.aster.files.services.TrashService
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.viewmodel.TrashViewModel

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    snackbarHostState: SnackbarHostState,
    retentionDays: Int = 30,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var deleteEntry by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
            viewModel.clearMessage()
        }
    }

    val listState = rememberLazyListState()
    val gestureScope = rememberCoroutineScope()
    val pullState = rememberPullRefreshState(
        refreshing = state.loading,
        onRefresh = { viewModel.refresh() }
    )
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        gestureScope.launch { listState.animateScrollToItem(0) }
                    })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回工具")
                }
            }
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "回收站",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "文件保留 " + retentionDays + " 天，过期自动清理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.entries.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
            }
            TextButton(onClick = viewModel::refresh) { Text("刷新") }
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.weight(1f).pullRefresh(pullState)
        ) {
        PullRefreshIndicator(
            refreshing = state.loading,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        if (state.loading && state.entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
        } else if (state.entries.isEmpty()) {
            EmptyState(
                title = "回收站为空",
                description = "删除的文件会先进入这里，可恢复；超过 " +
                    retentionDays + " 天后自动永久删除。",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.entries, key = { it.trashId }) { entry ->
                    TrashEntryCard(
                        entry = entry,
                        onRestore = { viewModel.restore(entry.trashId) },
                        onDelete = { deleteEntry = entry }
                    )
                }
            }
        }
        }
    }

    deleteEntry?.let { entry ->
        ConfirmDialog(
            title = "永久删除",
            text = "将永久删除“" + entry.name + "”，不会进入回收站，也无法恢复。",
            confirmLabel = "永久删除",
            destructive = true,
            onConfirm = {
                deleteEntry = null
                viewModel.deletePermanently(entry.trashId)
            },
            onDismiss = { deleteEntry = null }
        )
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "清空回收站",
            text = "将永久删除回收站中的 " + state.entries.size + " 项，无法恢复。",
            confirmLabel = "清空并永久删除",
            destructive = true,
            onConfirm = {
                confirmEmpty = false
                viewModel.emptyTrash()
            },
            onDismiss = { confirmEmpty = false }
        )
    }
}

@Composable
private fun TrashEntryCard(
    entry: TrashEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.originalPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (entry.entryType == EntryType.DIRECTORY) "文件夹" else formatBytes(entry.sizeBytes)) +
                        " · " + formatTime(entry.deletedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onRestore) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("恢复")
                }
            }
        }
    }
}
