package com.aster.files.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import com.aster.files.ui.selection.DragSelectState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.services.FileClassifier
import com.aster.files.services.ArchiveService
import com.aster.files.services.FileService
import com.aster.files.services.MediaConversionService
import com.aster.files.services.ZipPasswordStore
import com.aster.files.network.NetworkNode
import com.aster.files.vault.VaultManager
import androidx.compose.material.icons.outlined.Lock
import kotlinx.coroutines.launch
import com.aster.files.ui.components.AdvancedZipActionDialog
import com.aster.files.ui.components.AdvancedZipDialog
import com.aster.files.ui.components.ApkSigningDialog
import com.aster.files.ui.components.BatchRenameDialog
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.DocumentOpener
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.FileDetailsDialog
import com.aster.files.ui.components.FileNameDialog
import com.aster.files.ui.components.FilePropertiesDialog
import com.aster.files.ui.components.FilePreviewDialog
import com.aster.files.ui.components.FileActionBottomSheet
import com.aster.files.ui.components.FileSheetAction
import com.aster.files.ui.components.FileThumbnail
import com.aster.files.ui.components.ImageConversionDialog
import com.aster.files.ui.components.ImageGalleryDialog
import com.aster.files.ui.components.OperationProgressDialog
import com.aster.files.ui.components.RemoteDirectoryPickerDialog
import com.aster.files.ui.components.rememberMediaAspectRatio
import com.aster.files.ui.components.SelectionActionBar
import com.aster.files.ui.components.SelectionCheckbox
import com.aster.files.ui.components.SelectionDetailsDialog
import com.aster.files.ui.components.SortBottomSheet
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.viewmodel.FilePreview
import com.aster.files.ui.viewmodel.FileTabState
import com.aster.files.ui.viewmodel.FilesViewModel
import com.aster.files.ui.viewmodel.MainViewModel
import com.aster.files.ui.viewmodel.NetworkTransferViewModel
import java.io.File
import android.widget.Toast

private enum class FileAction { COPY, MOVE }

private data class PendingPaths(val action: FileAction, val paths: List<String>)

private data class ZipExtractRequest(
    val entry: FileEntry,
    val password: String?,
    val deleteSource: Boolean
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    mainViewModel: MainViewModel,
    networkTransferViewModel: NetworkTransferViewModel,
    networkNodes: List<NetworkNode>,
    vaultManager: VaultManager,
    conversionService: MediaConversionService,
    fileService: FileService,
    archiveService: ArchiveService,
    zipPasswordStore: ZipPasswordStore,
    snackbarHostState: SnackbarHostState,
    onOpenApk: (FileEntry) -> Unit,
    onOpenArchive: (FileEntry) -> Unit,
    onOpenText: (FileEntry) -> Unit,
    onAnalyzeDirectory: (String) -> Unit,
    onExtractFrames: (String) -> Unit = {},
    onPlayVideo: (FileEntry) -> Unit = {},
    onPlayAudio: (FileEntry, List<FileEntry>) -> Unit = { _, _ -> },
    confirmDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val mainState by mainViewModel.state.collectAsState()
    val networkTransfer by networkTransferViewModel.state.collectAsState()
    val context = LocalContext.current

    fun saveActiveTab() {
        mainViewModel.updateTab(
            id = mainState.activeTabId,
            path = state.currentPath,
            selectedPaths = state.selectedPaths,
            sort = state.sort
        )
    }

    fun applyTab(tab: FileTabState) {
        viewModel.setSort(tab.sort)
        viewModel.openDirectory(tab.path)
        viewModel.setSelection(tab.selectedPaths)
    }

    fun switchTab(tab: FileTabState) {
        if (tab.id == mainState.activeTabId) return
        saveActiveTab()
        mainViewModel.activateTab(tab.id)
        applyTab(tab)
    }

    fun newTab() {
        saveActiveTab()
        val tab = mainViewModel.addTab(viewModel.rootPath, state.sort)
        viewModel.openDirectory(tab.path)
        viewModel.setSelection(emptySet())
    }

    fun closeTab(tabId: String) {
        if (mainState.tabs.size <= 1) return
        saveActiveTab()
        val wasActive = tabId == mainState.activeTabId
        val next = mainViewModel.closeTab(tabId) ?: return
        if (wasActive) applyTab(next)
    }

    LaunchedEffect(
        mainState.activeTabId,
        state.currentPath,
        state.selectedPaths,
        state.sort
    ) {
        mainViewModel.updateTab(
            id = mainState.activeTabId,
            path = state.currentPath,
            selectedPaths = state.selectedPaths,
            sort = state.sort
        )
    }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }
    LaunchedEffect(networkTransfer.message, networkTransfer.error) {
        val message = networkTransfer.message ?: networkTransfer.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            if (networkTransfer.message != null) {
                networkTransferViewModel.consumeMessage()
                viewModel.refresh()
            }
            if (networkTransfer.error != null) networkTransferViewModel.clearError()
        }
    }
    var menuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deletePaths by remember { mutableStateOf<List<String>?>(null) }
    var zipPaths by remember { mutableStateOf<List<String>?>(null) }
    var zipActionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var zipExtractRequest by remember { mutableStateOf<ZipExtractRequest?>(null) }
    var pendingTarget by remember { mutableStateOf<PendingPaths?>(null) }
    var networkUploadRequest by remember { mutableStateOf<PendingPaths?>(null) }
    var networkUploadNode by remember { mutableStateOf<NetworkNode?>(null) }
    var detailsEntry by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var signingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showSelectionDetails by remember { mutableStateOf(false) }
    var batchRenameEntries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var vaultPaths by remember { mutableStateOf<List<String>?>(null) }
    var imageGallery by remember { mutableStateOf<Pair<List<FileEntry>, Int>?>(null) }
    var imageConversionPaths by remember { mutableStateOf<List<String>?>(null) }
    var videoExtractPaths by remember { mutableStateOf<List<String>?>(null) }
    var conversionProgress by remember { mutableStateOf<Float?>(null) }
    val conversionScope = androidx.compose.runtime.rememberCoroutineScope()
    val vaultScope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    val dragSelect = remember { DragSelectState() }
    val pullState = rememberPullRefreshState(
        refreshing = state.busy || state.loading,
        onRefresh = { viewModel.refresh() }
    )

    fun requestDelete(paths: List<String>) {
        if (confirmDelete) {
            deletePaths = paths
        } else {
            viewModel.delete(paths)
        }
    }

    fun openEntry(entry: FileEntry) {
        when {
            entry.isDirectory -> viewModel.openDirectory(entry.path)
            entry.kind == FileKind.IMAGE -> {
                viewModel.dismissPreview()
                val images = state.visibleEntries.filter {
                    !it.isDirectory && it.kind == FileKind.IMAGE
                }
                val index = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                imageGallery = images to index
            }
            entry.kind == FileKind.AUDIO -> onPlayAudio(
                entry,
                state.visibleEntries.filter { it.kind == FileKind.AUDIO && !it.isDirectory }
            )
            entry.kind == FileKind.VIDEO -> onPlayVideo(entry)
            entry.extension.equals("apk", ignoreCase = true) -> onOpenApk(entry)
            entry.extension.equals("zip", ignoreCase = true) -> zipActionEntry = entry
            DocumentOpener.supports(entry.extension) -> {
                if (!DocumentOpener.open(context, entry.path, entry.extension)) {
                    Toast.makeText(context, "暂不支持内置预览", Toast.LENGTH_LONG).show()
                }
            }
            FileClassifier.isTextPreviewable(entry.name) -> onOpenText(entry)
            else -> viewModel.openPreview(entry)
        }
    }

    val selected = state.selectedPaths
    val selectionMode = selected.isNotEmpty()

    BackHandler(enabled = state.currentPath != viewModel.rootPath || selectionMode) {
        if (selectionMode) {
            viewModel.clearSelection()
        } else {
            viewModel.navigateUp()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(state.gridMode) {
                    detectTapGestures(onDoubleTap = {
                        vaultScope.launch {
                            if (state.gridMode) gridState.animateScrollToItem(0)
                            else listState.animateScrollToItem(0)
                        }
                    })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.currentPath != viewModel.rootPath) {
                IconButton(onClick = viewModel::navigateUp) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "上一级")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.currentPath == viewModel.rootPath) {
                        "文件"
                    } else {
                        File(state.currentPath).name
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { newTab() }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建标签页")
            }
            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.Outlined.Sort, contentDescription = "排序")
            }
            IconButton(onClick = viewModel::toggleViewMode) {
                Icon(
                    imageVector = if (state.gridMode) {
                        Icons.Outlined.ViewList
                    } else {
                        Icons.Outlined.GridView
                    },
                    contentDescription = if (state.gridMode) "列表模式" else "网格模式"
                )
            }
            Box {
                IconButton(onClick = { showTopMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("分析当前目录") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Analytics, contentDescription = null)
                        },
                        onClick = {
                            showTopMenu = false
                            onAnalyzeDirectory(state.currentPath)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = {
                            showTopMenu = false
                            viewModel.refresh()
                        }
                    )
                }
            }
        }

        if (mainState.tabs.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = mainState.tabs
                    .indexOfFirst { it.id == mainState.activeTabId }
                    .coerceAtLeast(0),
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                mainState.tabs.forEach { tab ->
                    Tab(
                        selected = tab.id == mainState.activeTabId,
                        onClick = { switchTab(tab) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (tab.path == viewModel.rootPath) "根目录" else tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "关闭标签",
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { closeTab(tab.id) }
                                )
                            }
                        }
                    )
                }
                IconButton(onClick = { newTab() }) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建标签页")
                }
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            singleLine = true,
            placeholder = { Text("搜索当前目录") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                    }
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("隐藏文件", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.showHidden,
                onCheckedChange = viewModel::setShowHidden
            )
            Spacer(Modifier.weight(1f))
            if (selectionMode) {
                TextButton(onClick = viewModel::selectAll) { Text("全选") }
                TextButton(onClick = viewModel::clearSelection) { Text("取消选择") }
            }
        }

        if (state.busy || state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .pullRefresh(pullState)
        ) {
            PullRefreshIndicator(
                refreshing = state.busy || state.loading,
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            when {
                state.visibleEntries.isEmpty() && !state.loading -> EmptyState(
                    title = if (state.searching) "没有搜索结果" else "此文件夹为空",
                    description = if (state.searching) "尝试其他关键词" else "复制或新建文件后会显示在这里",
                    modifier = Modifier.fillMaxSize()
                )
                state.gridMode -> LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = StaggeredGridCells.Adaptive(128.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = if (selectionMode) 88.dp else 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.visibleEntries, key = { it.path }) { entry ->
                        FileGridItem(
                            entry = entry,
                            selectedPaths = selected,
                            dragState = dragSelect,
                            onSelectionChange = { viewModel.setSelection(it) },
                            onClick = {
                                if (selectionMode) {
                                    viewModel.toggleSelection(entry.path)
                                } else {
                                    openEntry(entry)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(entry.path) }
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        bottom = if (selectionMode) 88.dp else 16.dp
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.visibleEntries, key = { it.path }) { entry ->
                        FileListRow(
                            entry = entry,
                            selectedPaths = selected,
                            dragState = dragSelect,
                            onSelectionChange = { viewModel.setSelection(it) },
                            selectionMode = selectionMode,
                            onToggle = { viewModel.toggleSelection(entry.path) },
                            onClick = {
                                if (selectionMode) {
                                    viewModel.toggleSelection(entry.path)
                                } else {
                                    openEntry(entry)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(entry.path) }
                        )
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }

            if (selectionMode) {
                SelectionActionBar(
                    count = selected.size,
                    onConvert = {
                        val entries = state.visibleEntries.filter { it.path in selected }
                        when {
                            entries.isNotEmpty() && entries.all { it.kind == FileKind.IMAGE } ->
                                imageConversionPaths = entries.map { it.path }
                            entries.isNotEmpty() && entries.all { it.kind == FileKind.VIDEO } ->
                                videoExtractPaths = entries.map { it.path }
                            else -> Toast.makeText(
                                context,
                                "请选择全部图片进行格式转换，或全部视频提取音频",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onCopy = { pendingTarget = PendingPaths(FileAction.COPY, selected.toList()) },
                    onMove = { pendingTarget = PendingPaths(FileAction.MOVE, selected.toList()) },
                    onZip = { zipPaths = selected.toList() },
                    onDelete = { requestDelete(selected.toList()) },
                    onVault = {
                        val files = selected.filter { path ->
                            state.entries.any { it.path == path && !it.isDirectory }
                        }
                        if (files.isEmpty()) {
                            Toast.makeText(context, "请选择文件（不支持文件夹）", Toast.LENGTH_SHORT).show()
                        } else {
                            vaultPaths = files.toList()
                        }
                    },
                    onRename = {
                        val entries = state.visibleEntries.filter { it.path in selected }
                        if (entries.any { it.isDirectory }) {
                            Toast.makeText(
                                context,
                                "选中的文件夹不支持批量重命名，请只选择文件",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            batchRenameEntries = entries
                        }
                    },
                    onDetails = { showSelectionDetails = true },
                    onClose = viewModel::clearSelection,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    menuEntry?.let { entry ->
        val actions = buildList {
            add(
                FileSheetAction(label = "查看/预览", onClick = { openEntry(entry) })
            )
            add(FileSheetAction("属性", Icons.Outlined.Info, onClick = { propertiesEntry = entry }))
            if (!entry.isDirectory) {
                add(
                    FileSheetAction("移入保险箱", Icons.Outlined.Lock) {
                        vaultPaths = listOf(entry.path)
                    }
                )
            }
            add(
                FileSheetAction("复制", Icons.Outlined.ContentCopy) {
                    pendingTarget = PendingPaths(FileAction.COPY, listOf(entry.path))
                }
            )
            add(
                FileSheetAction("移动", Icons.Outlined.DriveFileMove) {
                    pendingTarget = PendingPaths(FileAction.MOVE, listOf(entry.path))
                }
            )
            add(FileSheetAction("重命名", onClick = { renameEntry = entry }))
            add(
                FileSheetAction("压缩为 ZIP", onClick = { zipPaths = listOf(entry.path) })
            )
            add(
                FileSheetAction("删除", Icons.Outlined.Delete, destructive = true) {
                    requestDelete(listOf(entry.path))
                }
            )
            if (entry.name.endsWith(".apk", ignoreCase = true)) {
                add(
                    FileSheetAction("修改签名", Icons.Outlined.Info) {
                        signingEntry = entry
                    }
                )
            }
        }
        FileActionBottomSheet(
            title = entry.name,
            actions = actions,
            onDismiss = { menuEntry = null }
        )
    }

    renameEntry?.let { entry ->
        FileNameDialog(
            title = "重命名",
            initialValue = entry.name,
            confirmLabel = "重命名",
            onDismiss = { renameEntry = null },
            onConfirm = { name ->
                renameEntry = null
                viewModel.rename(entry.path, name)
            }
        )
    }

    zipPaths?.let { paths ->
        AdvancedZipDialog(
            sourcePaths = paths,
            initialName = if (paths.size == 1) {
                File(paths.first()).nameWithoutExtension + ".zip"
            } else {
                "archive.zip"
            },
            onDismiss = { zipPaths = null },
            onConfirm = { name, options ->
                zipPaths = null
                viewModel.zipAdvanced(paths, name, options)
            }
        )
    }

    deletePaths?.let { paths ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将 " + paths.size + " 项移入应用私有回收站？可从回收站恢复。",
            confirmLabel = "移入回收站",
            destructive = true,
            onConfirm = {
                deletePaths = null
                viewModel.delete(paths)
            },
            onDismiss = { deletePaths = null }
        )
    }

    zipActionEntry?.let { entry ->
        AdvancedZipActionDialog(
            entry = entry,
            archiveService = archiveService,
            passwordStore = zipPasswordStore,
            onPreview = {
                zipActionEntry = null
                onOpenArchive(entry)
            },
            onExtractHere = { password, deleteSource ->
                zipActionEntry = null
                viewModel.unzipAdvanced(
                    entry.path,
                    state.currentPath,
                    password,
                    deleteSource
                )
            },
            onExtractTo = { password, deleteSource ->
                zipActionEntry = null
                zipExtractRequest = ZipExtractRequest(entry, password, deleteSource)
            },
            onDismiss = { zipActionEntry = null }
        )
    }

    zipExtractRequest?.let { request ->
        DirectoryPickerDialog(
            initialPath = state.currentPath,
            rootPath = viewModel.rootPath,
            fileService = fileService,
            onDismiss = { zipExtractRequest = null },
            onSelect = { target ->
                zipExtractRequest = null
                viewModel.unzipAdvanced(
                    request.entry.path,
                    target,
                    request.password,
                    request.deleteSource
                )
            }
        )
    }

    networkUploadNode?.let { node ->
        RemoteDirectoryPickerDialog(
            node = node,
            onDismiss = {
                networkUploadNode = null
                networkUploadRequest = null
            },
            onSelect = { remoteDirectory ->
                val request = networkUploadRequest
                networkUploadNode = null
                networkUploadRequest = null
                if (request != null) {
                    viewModel.clearSelection()
                    networkTransferViewModel.uploadLocal(
                        nodeId = node.id,
                        paths = request.paths,
                        remoteDirectory = remoteDirectory,
                        deleteAfter = request.action == FileAction.MOVE
                    )
                }
            }
        )
    }

    pendingTarget?.let { pending ->
        DirectoryPickerDialog(
            initialPath = state.currentPath,
            rootPath = viewModel.rootPath,
            fileService = fileService,
            onDismiss = { pendingTarget = null },
            onSelect = { target ->
                pendingTarget = null
                when (pending.action) {
                    FileAction.COPY -> viewModel.copy(pending.paths, target)
                    FileAction.MOVE -> viewModel.move(pending.paths, target)
                }
            },
            networkNodes = networkNodes,
            onSelectNetwork = { node ->
                networkUploadRequest = pending
                networkUploadNode = node
                pendingTarget = null
            }
        )
    }

    detailsEntry?.let { entry ->
        FileDetailsDialog(entry = entry, onDismiss = { detailsEntry = null })
    }

    propertiesEntry?.let { entry ->
        FilePropertiesDialog(entry = entry, onDismiss = { propertiesEntry = null })
    }

    signingEntry?.let { entry ->
        ApkSigningDialog(entry = entry, onDismiss = { signingEntry = null })
    }

    vaultPaths?.let { paths ->
        ConfirmDialog(
            title = "移入隐私保险箱",
            text = "将选中的 " + paths.size + " 个文件从当前位置移入应用私有 vault 目录？",
            confirmLabel = "移入保险箱",
            destructive = false,
            onConfirm = {
                vaultPaths = null
                vaultScope.launch {
                    var ok = 0
                    var fail = 0
                    for (path in paths) {
                        runCatching { vaultManager.moveIn(path) }
                            .onSuccess { ok++ }
                            .onFailure { fail++ }
                    }
                    Toast.makeText(
                        context,
                        if (fail == 0) "已移入保险箱：" + ok + " 项" else "成功 " + ok + " 项，失败 " + fail + " 项",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.refresh()
                }
            },
            onDismiss = { vaultPaths = null }
        )
    }

    imageConversionPaths?.let { paths ->
        ImageConversionDialog(
            count = paths.size,
            onDismiss = { imageConversionPaths = null },
            onConfirm = { format, quality ->
                imageConversionPaths = null
                conversionScope.launch {
                    var ok = 0
                    var fail = 0
                    paths.forEachIndexed { index, path ->
                        conversionProgress = (index.toFloat() / paths.size).coerceIn(0f, 1f)
                        runCatching { conversionService.convertImage(path, format, quality) }
                            .onSuccess { if (it.success) ok++ else fail++ }
                            .onFailure { fail++ }
                    }
                    conversionProgress = null
                    Toast.makeText(
                        context,
                        "转换完成：成功 " + ok + "，失败 " + fail,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.refresh()
                }
            }
        )
    }

    videoExtractPaths?.let { paths ->
        ConfirmDialog(
            title = "提取视频音频",
            text = "将从选中的 " + paths.size + " 个视频中提取 AAC 音频为 .m4a 文件。非 AAC 编码会给出提示。",
            confirmLabel = "开始提取",
            destructive = false,
            onConfirm = {
                videoExtractPaths = null
                conversionScope.launch {
                    var ok = 0
                    var unsupported = 0
                    paths.forEachIndexed { index, path ->
                        conversionProgress = (index.toFloat() / paths.size).coerceIn(0f, 1f)
                        val result = runCatching { conversionService.extractAudio(path) }.getOrNull()
                        if (result?.success == true) ok++ else unsupported++
                    }
                    conversionProgress = null
                    Toast.makeText(
                        context,
                        "音频提取：成功 " + ok + "，不支持/失败 " + unsupported,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.refresh()
                }
            },
            onDismiss = { videoExtractPaths = null }
        )
    }

    conversionProgress?.let { progress ->
        OperationProgressDialog(label = "正在转换媒体…", progress = progress)
    }

    imageGallery?.let { (images, index) ->
        ImageGalleryDialog(
            images = images,
            initialIndex = index,
            onDismiss = { imageGallery = null }
        )
    }

    val preview = state.preview
    if (preview != null) {
        FilePreviewDialog(
            preview = preview,
            onDismiss = viewModel::dismissPreview,
            onExtractFrames = onExtractFrames
        )
    }

    if (showSortSheet) {
        SortBottomSheet(
            current = state.sort,
            onSelect = viewModel::setSort,
            onDismiss = { showSortSheet = false }
        )
    }

    if (showSelectionDetails) {
        SelectionDetailsDialog(
            paths = selected.toList(),
            onDismiss = { showSelectionDetails = false }
        )
    }

    batchRenameEntries?.let { entries ->
        BatchRenameDialog(
            fileCount = entries.size,
            sampleName = entries.firstOrNull()?.name.orEmpty(),
            onDismiss = { batchRenameEntries = null },
            onConfirm = { options ->
                batchRenameEntries = null
                viewModel.renameBatch(entries, options)
            }
        )
    }

    state.operationProgress?.let { progress ->
        OperationProgressDialog(
            label = state.operationLabel ?: "正在处理…",
            progress = progress
        )
    }

    if (networkTransfer.active && networkTransfer.progress != null) {
        OperationProgressDialog(
            label = networkTransfer.label.ifBlank { "网络传输" },
            progress = networkTransfer.progress ?: 0f
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    entry: FileEntry,
    selectedPaths: Set<String>,
    dragState: DragSelectState,
    onSelectionChange: (Set<String>) -> Unit,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val latestSelection by rememberUpdatedState(selectedPaths)
    val latestChange by rememberUpdatedState(onSelectionChange)
    val isSelected = entry.path in selectedPaths
    val sizeLabel = remember(entry.sizeBytes, entry.isDirectory) {
        if (entry.isDirectory) "文件夹" else formatBytes(entry.sizeBytes)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { dragState.register(entry.path, it.boundsInRoot()) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(entry.path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragState.begin(entry.path, entry.path !in latestSelection, latestSelection) {
                            latestChange(it)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val rect = dragState.bounds[entry.path]
                        if (rect != null) {
                            dragState.drag(rect.topLeft + change.position, latestSelection) {
                                latestChange(it)
                            }
                        }
                    },
                    onDragEnd = { dragState.end() },
                    onDragCancel = { dragState.end() }
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileThumbnail(
            entry = entry,
            size = 48.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sizeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.isSymlink) {
            Text("链接", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        SelectionCheckbox(
            checked = isSelected,
            selectionMode = selectionMode,
            onCheckedChange = { onToggle() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    entry: FileEntry,
    selectedPaths: Set<String>,
    dragState: DragSelectState,
    onSelectionChange: (Set<String>) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val latestSelection by rememberUpdatedState(selectedPaths)
    val latestChange by rememberUpdatedState(onSelectionChange)
    val isSelected = entry.path in selectedPaths
    val sizeLabel = remember(entry.sizeBytes, entry.isDirectory) {
        if (entry.isDirectory) "文件夹" else formatBytes(entry.sizeBytes)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { dragState.register(entry.path, it.boundsInRoot()) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(entry.path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragState.begin(entry.path, entry.path !in latestSelection, latestSelection) {
                            latestChange(it)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val rect = dragState.bounds[entry.path]
                        if (rect != null) {
                            dragState.drag(rect.topLeft + change.position, latestSelection) {
                                latestChange(it)
                            }
                        }
                    },
                    onDragEnd = { dragState.end() },
                    onDragCancel = { dragState.end() }
                )
            },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FileThumbnail(
                entry = entry,
                size = 96.dp,
                modifier = Modifier.fillMaxWidth(),
                aspectRatio = 1f
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sizeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSelected) {
                Text(
                    text = "已选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onZip: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已选 " + count, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopy) { Text("复制") }
            TextButton(onClick = onMove) { Text("移动") }
            TextButton(onClick = onZip) { Text("压缩为 ZIP") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "退出多选")
            }
        }
    }
}


