package com.aster.files.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import androidx.media3.datasource.DataSource
import com.aster.files.network.FtpMediaDataSource
import com.aster.files.network.NetworkNode
import com.aster.files.network.NetworkProtocol
import com.aster.files.network.RemoteEntry
import com.aster.files.network.RemoteImageLoader
import com.aster.files.network.remoteAuthHeaders
import com.aster.files.network.remoteHttpUrl
import com.aster.files.services.FileService
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.FileNameDialog
import com.aster.files.ui.components.ImageViewerDialog
import com.aster.files.ui.components.OperationProgressDialog
import com.aster.files.ui.components.RemotePropertiesDialog
import com.aster.files.ui.components.SelectionActionBar
import com.aster.files.ui.components.SelectionCheckbox
import com.aster.files.ui.components.SortBottomSheet
import com.aster.files.ui.components.fileKindIcon
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.viewmodel.NetworkBrowserViewModel
import com.aster.files.ui.viewmodel.NetworkTransferViewModel
import com.aster.files.ui.selection.SelectionState
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.viewmodel.NetworkTabState

private data class RemoteVideoRequest(
    val title: String,
    val path: String,
    val url: String,
    val headers: Map<String, String>,
    val dataSourceFactory: DataSource.Factory? = null
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun NetworkBrowserScreen(
    viewModel: NetworkBrowserViewModel,
    transferViewModel: NetworkTransferViewModel,
    fileService: FileService,
    rootPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val transfer by transferViewModel.state.collectAsState()
    val context = LocalContext.current
    var showNodePicker by remember { mutableStateOf(false) }
    var downloadEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var batchDownloadEntries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var previewEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewRetryToken by remember { mutableStateOf(0) }
    var videoRequest by remember { mutableStateOf<RemoteVideoRequest?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteEntries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var propertyEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    val pullState = rememberPullRefreshState(
        refreshing = state.loading,
        onRefresh = { viewModel.refresh() }
    )
    val selection = remember { SelectionState() }
    val selectedEntries = state.entries.filter { it.path in selection.selectedPaths }

    LaunchedEffect(previewEntry?.path, state.activeNode?.id, previewRetryToken) {
        val entry = previewEntry
        val node = state.activeNode
        if (entry == null || node == null) {
            previewBitmap = null
            previewError = null
            previewLoading = false
            return@LaunchedEffect
        }
        previewLoading = true
        previewError = null
        previewBitmap = withContext(Dispatchers.IO) {
            RemoteImageLoader.load(node, entry)
        }
        if (previewBitmap == null) previewError = "图片加载失败，请检查网络或重试"
        previewLoading = false
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(transfer.message, transfer.error) {
        val message = transfer.message ?: transfer.error
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (transfer.message != null) transferViewModel.consumeMessage()
            if (transfer.error != null) transferViewModel.clearError()
        }
    }

    val activeTab = state.activeTab
    BackHandler(enabled = activeTab != null) {
        if (activeTab != null && activeTab.path != "/") viewModel.goUp() else onBack()
    }

    videoRequest?.let { request ->
        val synthetic = FileEntry(
            name = request.title,
            path = request.path,
            isDirectory = false,
            isSymlink = false,
            sizeBytes = 0L,
            modifiedAt = 0L,
            extension = request.title.substringAfterLast('.', ""),
            kind = FileKind.VIDEO
        )
        Dialog(
            onDismissRequest = { videoRequest = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogView = LocalView.current
            SideEffect {
                val window = (dialogView.parent as? DialogWindowProvider)?.window
                if (window != null) {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    WindowInsetsControllerCompat(window, dialogView).apply {
                        hide(WindowInsetsCompat.Type.systemBars())
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                VideoPlayerScreen(
                    entry = synthetic,
                    networkUri = request.url,
                    networkHeaders = request.headers,
                    dataSourceFactory = request.dataSourceFactory,
                    onBack = { videoRequest = null }
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (state.currentPath != "/") viewModel.goUp() else onBack()
            }) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activeTab?.nodeName ?: "网络存储",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showSort = true }) {
                Icon(Icons.Outlined.Sort, contentDescription = "排序")
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新建文件夹") },
                        onClick = {
                            showMenu = false
                            showNewFolder = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名选中项") },
                        enabled = selectedEntries.size == 1,
                        onClick = {
                            showMenu = false
                            renameEntry = selectedEntries.singleOrNull()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除选中项") },
                        enabled = selectedEntries.isNotEmpty(),
                        onClick = {
                            showMenu = false
                            deleteEntries = selectedEntries
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("属性") },
                        enabled = selectedEntries.size == 1,
                        onClick = {
                            showMenu = false
                            propertyEntry = selectedEntries.singleOrNull()
                        }
                    )
                }
            }
            IconButton(onClick = { showNodePicker = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建网络标签")
            }
        }

        if (state.tabs.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = state.tabs.indexOfFirst { it.id == state.activeTabId }
                    .coerceAtLeast(0),
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                state.tabs.forEach { tab ->
                    Tab(
                        selected = tab.id == state.activeTabId,
                        onClick = { viewModel.switchTab(tab.id) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(150.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "关闭网络标签",
                                    modifier = Modifier
                                        .size(15.dp)
                                        .combinedClickable(onClick = { viewModel.closeTab(tab.id) })
                                )
                            }
                        }
                    )
                }
            }
        }

        if (state.loading || state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.tabs.isEmpty()) {
            EmptyState(
                title = "还没有打开网络节点",
                description = "点击右上角 + 选择已配置的 FTP / WebDAV 节点。",
                modifier = Modifier.weight(1f)
            )
        } else if (state.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("连接失败", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.error ?: "网络不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = viewModel::retry) {
                            Text("重连")
                        }
                    }
                }
            }
        } else if (state.entries.isEmpty() && !state.loading) {
            EmptyState(
                title = "空目录",
                description = "当前网络目录没有文件。",
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pullRefresh(pullState)
            ) {
                PullRefreshIndicator(
                    refreshing = state.loading,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = if (selection.active) 96.dp else 20.dp
                    )
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        RemoteEntryRow(
                            entry = entry,
                            node = state.activeNode,
                            selected = entry.path in selection.selectedPaths,
                            selectionMode = selection.active,
                            onOpen = {
                                if (selection.active) {
                                    selection.toggle(entry.path)
                                } else {
                                    when {
                                        entry.isDirectory -> viewModel.navigate(entry.path)
                                        entry.isImage -> previewEntry = entry
                                        entry.isVideo -> {
                                            val node = state.activeNode
                                            if (node == null) {
                                                Toast.makeText(context, "节点已失效", Toast.LENGTH_SHORT).show()
                                            } else if (node.protocol == NetworkProtocol.WEBDAV) {
                                                val url = remoteHttpUrl(node, entry.path)
                                                if (url == null) {
                                                    Toast.makeText(context, "无法生成视频地址", Toast.LENGTH_LONG).show()
                                                } else {
                                                    videoRequest = RemoteVideoRequest(
                                                        title = entry.name,
                                                        path = "remote://" + node.id + entry.path,
                                                        url = url,
                                                        headers = remoteAuthHeaders(node)
                                                    )
                                                }
                                            } else if (node.protocol == NetworkProtocol.FTP) {
                                                videoRequest = RemoteVideoRequest(
                                                    title = entry.name,
                                                    path = "remote-ftp://" + node.id + entry.path,
                                                    url = "aster-ftp://" + node.id + entry.path,
                                                    headers = emptyMap(),
                                                    dataSourceFactory = FtpMediaDataSource.Factory(node)
                                                )
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "SMB 视频暂不支持在线播放，请先下载",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        else -> downloadEntry = entry
                                    }
                                }
                            },
                            onLongClick = { selection.toggle(entry.path) },
                            onToggle = { selection.toggle(entry.path) },
                            onDownload = { downloadEntry = entry }
                        )
                    }
                }
                if (selection.active) {
                    SelectionActionBar(
                        count = selection.count,
                        onDownload = {
                            val files = selectedEntries.filterNot { it.isDirectory }
                            if (files.isEmpty()) {
                                Toast.makeText(context, "请选择文件", Toast.LENGTH_SHORT).show()
                            } else {
                                batchDownloadEntries = files
                            }
                        },
                        onDelete = { deleteEntries = selectedEntries },
                        onProperties = if (selectedEntries.size == 1) {
                            { propertyEntry = selectedEntries.first() }
                        } else {
                            null
                        },
                        onClose = { selection.clear() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    if (showNodePicker) {
        NetworkNodePickerDialog(
            nodes = state.nodes,
            onDismiss = { showNodePicker = false },
            onSelect = { node ->
                showNodePicker = false
                viewModel.addTab(node.id)
            }
        )
    }

    previewEntry?.let { entry ->
        when {
            previewLoading || (previewBitmap == null && previewError == null) -> {
                FullScreenImageLoading(
                    name = entry.name,
                    onDismiss = { previewEntry = null }
                )
            }
            previewBitmap != null -> {
                ImageViewerDialog(
                    name = entry.name,
                    model = previewBitmap,
                    onDismiss = {
                        previewEntry = null
                        previewBitmap = null
                    }
                )
            }
            else -> {
                FullScreenImageError(
                    name = entry.name,
                    message = previewError ?: "图片加载失败",
                    onRetry = { previewRetryToken++ },
                    onDismiss = { previewEntry = null }
                )
            }
        }
    }

    downloadEntry?.let { entry ->
        DirectoryPickerDialog(
            initialPath = rootPath,
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = { downloadEntry = null },
            onSelect = { target ->
                downloadEntry = null
                state.activeNode?.let { node ->
                    transferViewModel.download(node.id, entry, target)
                }
            }
        )
    }

    batchDownloadEntries?.let { entries ->
        DirectoryPickerDialog(
            initialPath = rootPath,
            rootPath = rootPath,
            fileService = fileService,
            onDismiss = { batchDownloadEntries = null },
            onSelect = { target ->
                batchDownloadEntries = null
                state.activeNode?.let { node ->
                    transferViewModel.downloadBatch(node.id, entries, target)
                }
            }
        )
    }

    if (showSort) {
        SortBottomSheet(
            current = state.sort,
            onSelect = viewModel::setSort,
            onDismiss = { showSort = false }
        )
    }

    if (showNewFolder) {
        FileNameDialog(
            title = "新建网络文件夹",
            initialValue = "",
            confirmLabel = "创建",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                viewModel.createDirectory(name)
            }
        )
    }

    renameEntry?.let { entry ->
        FileNameDialog(
            title = "重命名网络文件",
            initialValue = entry.name,
            confirmLabel = "重命名",
            onDismiss = { renameEntry = null },
            onConfirm = { name ->
                renameEntry = null
                viewModel.rename(entry, name)
            }
        )
    }

    propertyEntry?.let { entry ->
        val node = state.activeNode
        if (node != null) {
            RemotePropertiesDialog(
                node = node,
                entry = entry,
                onDismiss = { propertyEntry = null }
            )
        }
    }

    deleteEntries?.let { entries ->
        ConfirmDialog(
            title = "删除网络文件",
            text = "将从网络端删除选中的 " + entries.size + " 项，此操作不可撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                deleteEntries = null
                selection.clear()
                viewModel.delete(entries)
            },
            onDismiss = { deleteEntries = null }
        )
    }

    if (transfer.active && transfer.progress != null) {
        OperationProgressDialog(
            label = transfer.label,
            progress = transfer.progress ?: 0f
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    node: NetworkNode?,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (entry.isImage && node != null) {
                RemoteImage(
                    node = node,
                    entry = entry,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(
                    imageVector = when {
                        entry.isDirectory -> Icons.Outlined.Folder
                        entry.isImage -> Icons.Outlined.Image
                        entry.isVideo -> Icons.Outlined.Movie
                        else -> fileKindIcon(FileKind.OTHER)
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDirectory) "文件夹" else formatBytes(entry.sizeBytes) +
                    " · " + formatTime(entry.modifiedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!entry.isDirectory) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = "下载到本地")
            }
        }
        SelectionCheckbox(
            checked = selected,
            selectionMode = selectionMode,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun NetworkNodePickerDialog(
    nodes: List<NetworkNode>,
    onDismiss: () -> Unit,
    onSelect: (NetworkNode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择网络节点") },
        text = {
            if (nodes.isEmpty()) {
                Text("尚未配置网络存储，请先在工具 → 网络存储中添加。")
            } else {
                LazyColumn {
                    items(nodes, key = { it.id }) { node ->
                        TextButton(
                            onClick = { onSelect(node) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(node.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    node.protocol.label + " · " + node.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun RemoteImage(
    node: NetworkNode,
    entry: RemoteEntry,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(node.id, entry.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(node.id, entry.path) {
        bitmap = RemoteImageLoader.load(node, entry)
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = entry.name,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = entry.name,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FullScreenImageLoading(name: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.size(14.dp))
                    Text(name, color = androidx.compose.ui.graphics.Color.White)
                    Text(
                        "正在从网络加载原图…",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenImageError(
    name: String,
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(name, color = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.size(8.dp))
                    Text(message, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteImagePreview(
    node: NetworkNode?,
    entry: RemoteEntry,
    onDismiss: () -> Unit
) {
    val url = node?.let { remoteHttpUrl(it, entry.path) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp)) {
            if (node == null) {
                Text(
                    "网络节点已失效。",
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                RemoteImage(
                    node = node,
                    entry = entry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
