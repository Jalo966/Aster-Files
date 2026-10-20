package com.aster.files.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileCategory
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.services.ArchiveService
import com.aster.files.services.CategoryScanResult
import com.aster.files.services.FileClassifier
import com.aster.files.services.FileService
import com.aster.files.services.ZipPasswordStore
import com.aster.files.ui.components.AdvancedZipActionDialog
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.DocumentOpener
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.components.FileActionBottomSheet
import com.aster.files.ui.components.FileDetailsDialog
import com.aster.files.ui.components.FileSheetAction
import com.aster.files.ui.components.FileNameDialog
import com.aster.files.ui.components.FilePreviewDialog
import com.aster.files.ui.components.ImageGalleryDialog
import com.aster.files.ui.components.FileThumbnail
import com.aster.files.ui.components.OperationProgressDialog
import com.aster.files.ui.components.rememberMediaAspectRatio
import com.aster.files.ui.components.SelectionActionBar
import com.aster.files.ui.components.SelectionCheckbox
import com.aster.files.ui.components.SelectionDetailsDialog
import com.aster.files.ui.components.SortBottomSheet
import com.aster.files.ui.components.categoryIcon
import com.aster.files.ui.components.categoryLabel
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.sort.FileSort
import com.aster.files.ui.selection.SelectionState
import com.aster.files.ui.sort.FileSorter
import com.aster.files.ui.viewmodel.CategoryViewModel
import java.io.File
import android.widget.Toast

private enum class CategoryFileAction { COPY, MOVE }

private data class CategoryTarget(
    val action: CategoryFileAction,
    val entry: FileEntry
)

private data class CategoryBatchTarget(
    val action: CategoryFileAction,
    val paths: List<String>
)

private data class CategoryZipRequest(
    val entry: FileEntry,
    val password: String?,
    val deleteSource: Boolean
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    viewModel: CategoryViewModel,
    fileService: FileService,
    archiveService: ArchiveService,
    zipPasswordStore: ZipPasswordStore,
    snackbarHostState: SnackbarHostState,
    onOpenApk: (FileEntry) -> Unit,
    onOpenArchive: (FileEntry) -> Unit,
    onOpenText: (FileEntry) -> Unit,
    onExtractFrames: (String) -> Unit = {},
    onPlayVideo: (FileEntry) -> Unit = {},
    onPlayAudio: (FileEntry, List<FileEntry>) -> Unit = { _, _ -> },
    confirmDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val selected = state.selectedCategory
    var menuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var pendingTarget by remember { mutableStateOf<CategoryTarget?>(null) }
    var detailsEntry by remember { mutableStateOf<FileEntry?>(null) }
    var zipActionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var zipExtractRequest by remember { mutableStateOf<CategoryZipRequest?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    val selection = remember { SelectionState() }
    var showSelectionDetails by remember { mutableStateOf(false) }
    var batchDeletePaths by remember { mutableStateOf<List<String>?>(null) }
    var batchTarget by remember { mutableStateOf<CategoryBatchTarget?>(null) }
    var imageGallery by remember { mutableStateOf<Pair<List<FileEntry>, Int>?>(null) }

    BackHandler(enabled = selected != null || selection.active) {
        if (selection.active) selection.clear() else viewModel.closeCategory()
    }

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }

    fun openEntry(entry: FileEntry) {
        when {
            entry.kind == FileKind.IMAGE -> {
                viewModel.dismissPreview()
                val images = state.result?.files(FileCategory.IMAGE).orEmpty()
                val index = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                imageGallery = images to index
            }
            entry.kind == FileKind.AUDIO -> onPlayAudio(
                entry,
                state.result?.files(FileCategory.AUDIO).orEmpty()
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

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = if (selection.active) 76.dp else 0.dp)
    ) {
        if (state.loading || state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (selected == null) {
            CategoryOverview(
                result = state.result,
                loading = state.loading,
                onRefresh = viewModel::refresh,
                onOpen = viewModel::openCategory
            )
        } else {
            CategoryDetail(
                category = selected,
                result = state.result,
                loading = state.loading,
                rootPath = viewModel.rootPath,
                sort = state.sort,
                onSort = { showSortSheet = true },
                onBack = viewModel::closeCategory,
                onRefresh = viewModel::refresh,
                onPreview = { entry ->
                    if (selection.active) selection.toggle(entry.path) else openEntry(entry)
                },
                onLongPress = { selection.toggle(it.path) },
                selectedPaths = selection.selectedPaths,
                selectionMode = selection.active,
                onToggle = { selection.toggle(it.path) }
            )
        }
    }

    if (selection.active) {
        SelectionActionBar(
            count = selection.count,
            onCopy = {
                batchTarget = CategoryBatchTarget(
                    CategoryFileAction.COPY,
                    selection.selectedPaths.toList()
                )
            },
            onMove = {
                batchTarget = CategoryBatchTarget(
                    CategoryFileAction.MOVE,
                    selection.selectedPaths.toList()
                )
            },
            onDelete = {
                val paths = selection.selectedPaths.toList()
                if (confirmDelete) {
                    batchDeletePaths = paths
                } else {
                    viewModel.deletePaths(paths)
                    selection.clear()
                }
            },
            onDetails = { showSelectionDetails = true },
            onClose = { selection.clear() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }

    menuEntry?.let { entry ->
        val actions = buildList {
            add(FileSheetAction("查看/预览", onClick = { openEntry(entry) }))
            add(
                FileSheetAction("复制", Icons.Outlined.ContentCopy) {
                    pendingTarget = CategoryTarget(CategoryFileAction.COPY, entry)
                }
            )
            add(
                FileSheetAction("移动", Icons.Outlined.DriveFileMove) {
                    pendingTarget = CategoryTarget(CategoryFileAction.MOVE, entry)
                }
            )
            add(FileSheetAction("重命名", onClick = { renameEntry = entry }))
            add(
                FileSheetAction("删除", Icons.Outlined.Delete, destructive = true) {
                    if (confirmDelete) deleteEntry = entry else viewModel.delete(entry)
                }
            )
            add(FileSheetAction("详情", Icons.Outlined.Info, onClick = { detailsEntry = entry }))
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
            confirmLabel = "重命名为",
            onDismiss = { renameEntry = null },
            onConfirm = { name ->
                renameEntry = null
                viewModel.rename(entry, name)
            }
        )
    }

    deleteEntry?.let { entry ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将“" + entry.name + "”移入回收站？可从回收站恢复。",
            confirmLabel = "移入回收站",
            destructive = true,
            onConfirm = {
                deleteEntry = null
                viewModel.delete(entry)
            },
            onDismiss = { deleteEntry = null }
        )
    }

    pendingTarget?.let { target ->
        DirectoryPickerDialog(
            initialPath = File(target.entry.path).parentFile?.absolutePath ?: viewModel.rootPath,
            rootPath = viewModel.rootPath,
            fileService = fileService,
            onDismiss = { pendingTarget = null },
            onSelect = { directory ->
                pendingTarget = null
                when (target.action) {
                    CategoryFileAction.COPY -> viewModel.copy(target.entry, directory)
                    CategoryFileAction.MOVE -> viewModel.move(target.entry, directory)
                }
            }
        )
    }

    detailsEntry?.let { entry ->
        FileDetailsDialog(entry = entry, onDismiss = { detailsEntry = null })
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
                    viewModel.rootPath,
                    password,
                    deleteSource
                )
            },
            onExtractTo = { password, deleteSource ->
                zipActionEntry = null
                zipExtractRequest = CategoryZipRequest(entry, password, deleteSource)
            },
            onDismiss = { zipActionEntry = null }
        )
    }

    zipExtractRequest?.let { request ->
        DirectoryPickerDialog(
            initialPath = viewModel.rootPath,
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

    batchDeletePaths?.let { paths ->
        ConfirmDialog(
            title = "移入回收站",
            text = "将选中的 " + paths.size + " 项移入回收站？可从回收站恢复。",
            confirmLabel = "移入回收站",
            destructive = true,
            onConfirm = {
                batchDeletePaths = null
                viewModel.deletePaths(paths)
                selection.clear()
            },
            onDismiss = { batchDeletePaths = null }
        )
    }

    batchTarget?.let { target ->
        DirectoryPickerDialog(
            initialPath = viewModel.rootPath,
            rootPath = viewModel.rootPath,
            fileService = fileService,
            onDismiss = { batchTarget = null },
            onSelect = { directory ->
                batchTarget = null
                when (target.action) {
                    CategoryFileAction.COPY -> viewModel.copyPaths(target.paths, directory)
                    CategoryFileAction.MOVE -> viewModel.movePaths(target.paths, directory)
                }
                selection.clear()
            }
        )
    }

    if (showSelectionDetails) {
        SelectionDetailsDialog(
            paths = selection.selectedPaths.toList(),
            onDismiss = { showSelectionDetails = false }
        )
    }

    state.operationProgress?.let { progress ->
        OperationProgressDialog(
            label = state.operationLabel ?: "正在处理…",
            progress = progress
        )
    }

    imageGallery?.let { (images, index) ->
        ImageGalleryDialog(
            images = images,
            initialIndex = index,
            onDismiss = { imageGallery = null }
        )
    }

    state.preview?.let { preview ->
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryOverview(
    result: CategoryScanResult?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (FileCategory) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "分类",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    result?.let { "已扫描 " + it.scannedItems + " 项" } ?: "正在扫描存储…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
            }
        }

        if (result == null) {
            EmptyState(
                title = if (loading) "正在扫描…" else "暂无分类数据",
                description = "扫描图片、视频、音频、文档、安装包和压缩包",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            if (result.truncated) {
                Text(
                    text = "扫描达到上限，部分文件未列出；可进入具体目录后继续浏览。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                items(FileCategory.values(), key = { it.name }) { category ->
                    CategoryCard(
                        category = category,
                        count = result.count(category),
                        bytes = result.totalBytes(category),
                        onClick = { onOpen(category) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: FileCategory,
    count: Int,
    bytes: Long,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                categoryIcon(category),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                categoryLabel(category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                count.toString() + " 个文件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatBytes(bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private enum class MediaTab(val label: String) {
    ALL("全部"),
    BY_DIRECTORY("按目录")
}

private enum class DocumentTab(val label: String, val extensions: Set<String>?) {
    ALL("全部", null),
    TXT("TXT", setOf("txt", "md", "log", "rtf")),
    WORD("Word", setOf("doc", "docx", "odt")),
    EXCEL("Excel", setOf("xls", "xlsx", "csv", "ods")),
    PPT("PPT", setOf("ppt", "pptx", "odp")),
    OTHER("其他", null)
}

@Composable
private fun CategoryDetail(
    category: FileCategory,
    result: CategoryScanResult?,
    loading: Boolean,
    rootPath: String,
    sort: FileSort,
    onSort: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onToggle: (FileEntry) -> Unit
) {
    when (category) {
        FileCategory.IMAGE, FileCategory.VIDEO -> MediaCategoryDetail(
            category = category,
            result = result,
            loading = loading,
            rootPath = rootPath,
            sort = sort,
            onSort = onSort,
            onBack = onBack,
            onRefresh = onRefresh,
            onPreview = onPreview,
            onLongPress = onLongPress,
            selectedPaths = selectedPaths,
            selectionMode = selectionMode,
            onToggle = onToggle
        )
        FileCategory.DOCUMENT -> DocumentCategoryDetail(
            result = result,
            loading = loading,
            sort = sort,
            onSort = onSort,
            onBack = onBack,
            onRefresh = onRefresh,
            onPreview = onPreview,
            onLongPress = onLongPress,
            selectedPaths = selectedPaths,
            selectionMode = selectionMode,
            onToggle = onToggle
        )
        else -> SimpleCategoryDetail(
            category = category,
            entries = result?.files(category).orEmpty(),
            loading = loading,
            sort = sort,
            onSort = onSort,
            onBack = onBack,
            onRefresh = onRefresh,
            onPreview = onPreview,
            onLongPress = onLongPress,
            selectedPaths = selectedPaths,
            selectionMode = selectionMode,
            onToggle = onToggle
        )
    }
}

@Composable
private fun CategoryHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSort: (() -> Unit)? = null
) {
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
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onSort != null) {
            IconButton(onClick = onSort) {
                Icon(Icons.Outlined.Sort, contentDescription = "排序")
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryList(
    category: FileCategory,
    entries: List<FileEntry>,
    loading: Boolean,
    onPreview: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onToggle: (FileEntry) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(
            title = if (loading) "正在扫描…" else "没有文件",
            description = "尝试刷新，或到文件页浏览其他目录",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    var gridMode by rememberSaveable(category.name) { mutableStateOf(false) }
    val dragState = remember { DragSelectState() }
    val latestSelection by rememberUpdatedState(selectedPaths)
    val latestEntries by rememberUpdatedState(entries)
    val latestToggle by rememberUpdatedState(onToggle)
    val applyDragSelection: (Set<String>) -> Unit = { newSet ->
        val known = latestEntries.associateBy { it.path }
        (newSet - latestSelection).forEach { path ->
            known[path]?.let { latestToggle(it) }
        }
        (latestSelection - newSet).forEach { path ->
            known[path]?.let { latestToggle(it) }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { gridMode = !gridMode }) {
                Icon(
                    if (gridMode) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = if (gridMode) "列表模式" else "网格模式"
                )
            }
        }
        if (gridMode) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(128.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                items(entries, key = { it.path }) { entry ->
                    CategoryStaggeredCard(
                        entry = entry,
                        selected = entry.path in selectedPaths,
                        selectedPaths = selectedPaths,
                        selectionMode = selectionMode,
                        dragState = dragState,
                        onDragSelection = applyDragSelection,
                        onToggle = { onToggle(entry) },
                        onClick = { onPreview(entry) },
                        onLongClick = { onLongPress(entry) }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(entries, key = { it.path }) { entry ->
                    CategoryFileRow(
                        entry = entry,
                        category = category,
                        selected = entry.path in selectedPaths,
                        selectedPaths = selectedPaths,
                        selectionMode = selectionMode,
                        dragState = dragState,
                        onDragSelection = applyDragSelection,
                        onToggle = { onToggle(entry) },
                        onClick = { onPreview(entry) },
                        onLongClick = { onLongPress(entry) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryStaggeredCard(
    entry: FileEntry,
    selected: Boolean,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    dragState: DragSelectState,
    onDragSelection: (Set<String>) -> Unit,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val latestSelection by rememberUpdatedState(selectedPaths)
    val latestChange by rememberUpdatedState(onDragSelection)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { dragState.register(entry.path, it.boundsInRoot()) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(entry.path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragState.begin(
                            entry.path,
                            entry.path !in latestSelection,
                            latestSelection
                        ) { latestChange(it) }
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
        Column(modifier = Modifier.padding(10.dp)) {
            FileThumbnail(
                entry = entry,
                size = 96.dp,
                modifier = Modifier.fillMaxWidth(),
                aspectRatio = 1f
            )
            Spacer(Modifier.height(6.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SelectionCheckbox(
                    checked = selected,
                    selectionMode = selectionMode,
                    onCheckedChange = { onToggle() }
                )
            }
        }
    }
}

@Composable
private fun SimpleCategoryDetail(
    category: FileCategory,
    entries: List<FileEntry>,
    loading: Boolean,
    sort: FileSort,
    onSort: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onToggle: (FileEntry) -> Unit
) {
    val sortedEntries = FileSorter.sort(entries, sort)
    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            title = categoryLabel(category),
            subtitle = sortedEntries.size.toString() + " 个文件 · " +
                formatBytes(sortedEntries.sumOf { it.sizeBytes }),
            onBack = onBack,
            onRefresh = onRefresh,
            onSort = onSort
        )
        CategoryList(
            category, sortedEntries, loading, onPreview, onLongPress,
            selectedPaths, selectionMode, onToggle
        )
    }
}

@Composable
private fun MediaCategoryDetail(
    category: FileCategory,
    result: CategoryScanResult?,
    loading: Boolean,
    rootPath: String,
    sort: FileSort,
    onSort: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onToggle: (FileEntry) -> Unit
) {
    var tabIndex by rememberSaveable(category.name) { mutableStateOf(0) }
    var directoryPath by rememberSaveable(category.name) { mutableStateOf<String?>(null) }
    val entries = FileSorter.sort(result?.files(category).orEmpty(), sort)
    val currentDirectory = directoryPath

    BackHandler(enabled = currentDirectory != null) {
        directoryPath = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (currentDirectory != null) {
            val directoryEntries = entries.filter {
                File(it.path).parentFile?.absolutePath == currentDirectory
            }
            CategoryHeader(
                title = if (currentDirectory == rootPath) {
                    "存储根目录"
                } else {
                    File(currentDirectory).name.ifBlank { "存储根目录" }
                },
                subtitle = directoryEntries.size.toString() + " 个文件 · " +
                    formatBytes(directoryEntries.sumOf { it.sizeBytes }),
                onBack = { directoryPath = null },
                onRefresh = onRefresh,
                onSort = onSort
            )
            CategoryList(
                category,
                FileSorter.sort(directoryEntries, sort),
                loading,
                onPreview,
                onLongPress,
                selectedPaths,
                selectionMode,
                onToggle
            )
        } else {
            CategoryHeader(
                title = categoryLabel(category),
                subtitle = entries.size.toString() + " 个文件 · " +
                    formatBytes(entries.sumOf { it.sizeBytes }),
                onBack = onBack,
                onRefresh = onRefresh,
                onSort = onSort
            )
            TabRow(selectedTabIndex = tabIndex) {
                MediaTab.values().forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(tab.label) }
                    )
                }
            }
            if (tabIndex == 0) {
                CategoryList(
                    category, entries, loading, onPreview, onLongPress,
                    selectedPaths, selectionMode, onToggle
                )
            } else {
                DirectoryGroups(
                    category = category,
                    entries = entries,
                    loading = loading,
                    rootPath = rootPath,
                    onOpen = { directoryPath = it }
                )
            }
        }
    }
}

@Composable
private fun DirectoryGroups(
    category: FileCategory,
    entries: List<FileEntry>,
    loading: Boolean,
    rootPath: String,
    onOpen: (String) -> Unit
) {
    val groups = entries.groupBy { File(it.path).parentFile?.absolutePath ?: "" }
        .toList()
        .sortedBy { it.first.lowercase() }

    if (groups.isEmpty()) {
        EmptyState(
            title = if (loading) "正在扫描…" else "没有文件夹",
            description = "当前分类下没有可聚合的目录",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(groups, key = { it.first }) { (directory, files) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(directory) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (directory == rootPath) {
                                "存储根目录"
                            } else {
                                File(directory).name.ifBlank { "存储根目录" }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            directory,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(files.size.toString() + " 个", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DocumentCategoryDetail(
    result: CategoryScanResult?,
    loading: Boolean,
    sort: FileSort,
    onSort: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onToggle: (FileEntry) -> Unit
) {
    val category = FileCategory.DOCUMENT
    val entries = FileSorter.sort(result?.files(category).orEmpty(), sort)
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val selectedTab = DocumentTab.values()[tabIndex]

    val knownExtensions = DocumentTab.values()
        .mapNotNull { it.extensions }
        .flatten()
        .toSet()
    val filtered = when (selectedTab) {
        DocumentTab.ALL -> entries
        DocumentTab.OTHER -> entries.filter { it.extension !in knownExtensions }
        else -> entries.filter { it.extension in (selectedTab.extensions ?: emptySet()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            title = "文档",
            subtitle = entries.size.toString() + " 个文件 · " +
                formatBytes(entries.sumOf { it.sizeBytes }),
            onBack = onBack,
            onRefresh = onRefresh,
            onSort = onSort
        )
        ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
            DocumentTab.values().forEachIndexed { index, tab ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(tab.label) }
                )
            }
        }
        CategoryList(
            category, filtered, loading, onPreview, onLongPress,
            selectedPaths, selectionMode, onToggle
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryFileRow(
    entry: FileEntry,
    category: FileCategory,
    selected: Boolean,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    dragState: DragSelectState,
    onDragSelection: (Set<String>) -> Unit,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val latestSelection by rememberUpdatedState(selectedPaths)
    val latestChange by rememberUpdatedState(onDragSelection)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { dragState.register(entry.path, it.boundsInRoot()) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(entry.path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragState.begin(
                            entry.path,
                            entry.path !in latestSelection,
                            latestSelection
                        ) { latestChange(it) }
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
            size = 48.dp,
            fallbackIcon = categoryIcon(category)
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatBytes(entry.sizeBytes) + " · " + formatTime(entry.modifiedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                entry.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SelectionCheckbox(
            checked = selected,
            selectionMode = selectionMode,
            onCheckedChange = { onToggle() }
        )
    }
}
