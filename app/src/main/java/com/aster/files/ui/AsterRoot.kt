package com.aster.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aster.files.AppContainer
import kotlinx.coroutines.launch
import com.aster.files.data.FileCategory
import com.aster.files.data.FileEntry
import com.aster.files.ui.components.ApkActionDialog
import com.aster.files.ui.components.ArchivePreviewDialog
import com.aster.files.ui.components.MiniAudioPlayer
import com.aster.files.ui.components.TextEditorDialog
import com.aster.files.ui.screens.AnalysisScreen
import com.aster.files.ui.screens.AppManagementScreen
import com.aster.files.ui.screens.AudioPlayerScreen
import com.aster.files.ui.screens.CategoryScreen
import com.aster.files.ui.screens.DirectoryAnalysisScreen
import com.aster.files.ui.screens.FilesScreen
import com.aster.files.ui.screens.HomeScreen
import com.aster.files.ui.screens.IntegrityScreen
import com.aster.files.ui.screens.NetworkBrowserScreen
import com.aster.files.ui.screens.NetworkStorageScreen
import com.aster.files.ui.screens.SettingsScreen
import com.aster.files.ui.screens.SpaceCleanerScreen
import com.aster.files.ui.screens.ToolsScreen
import com.aster.files.ui.screens.TrashScreen
import com.aster.files.ui.screens.WifiTransferScreen
import com.aster.files.ui.viewmodel.AnalysisViewModel
import com.aster.files.ui.viewmodel.AppsViewModel
import com.aster.files.ui.viewmodel.CategoryViewModel
import com.aster.files.ui.viewmodel.DirectoryAnalysisViewModel
import com.aster.files.ui.screens.FrameExtractScreen
import com.aster.files.ui.screens.VideoPlayerScreen
import com.aster.files.ui.viewmodel.FilesViewModel
import com.aster.files.ui.viewmodel.FrameExtractViewModel
import com.aster.files.ui.viewmodel.IntegrityViewModel
import com.aster.files.ui.viewmodel.MainViewModel
import com.aster.files.ui.viewmodel.NetworkBrowserViewModel
import com.aster.files.ui.viewmodel.NetworkStorageViewModel
import com.aster.files.ui.viewmodel.NetworkTransferViewModel
import com.aster.files.ui.viewmodel.SettingsViewModel
import com.aster.files.ui.viewmodel.TrashViewModel
import com.aster.files.ui.viewmodel.WifiTransferViewModel

private enum class AsterTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Outlined.Home),
    CATEGORY("分类", Icons.Outlined.Category),
    FILES("文件", Icons.Outlined.Folder),
    ANALYSIS("分析", Icons.Outlined.Insights),
    TOOLS("工具", Icons.Outlined.Build)
}

private enum class ToolRoute {
    TRASH,
    APPS,
    SETTINGS,
    WIFI,
    NETWORK,
    NETWORK_BROWSER,
    VAULT,
    SPACE_CLEANER
}

private data class AudioRequest(
    val entry: FileEntry,
    val queue: List<FileEntry>
)

@Composable
fun AsterRoot(
    container: AppContainer,
    audioOpenToken: Int = 0
) {
    val factory = remember(container) { AsterViewModelFactory(container) }
    val filesViewModel: FilesViewModel = viewModel(factory = factory)
    val mainViewModel: MainViewModel = viewModel(factory = factory)
    val analysisViewModel: AnalysisViewModel = viewModel(factory = factory)
    val integrityViewModel: IntegrityViewModel = viewModel(factory = factory)
    val trashViewModel: TrashViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    val categoryViewModel: CategoryViewModel = viewModel(factory = factory)
    val appsViewModel: AppsViewModel = viewModel(factory = factory)
    val directoryAnalysisViewModel: DirectoryAnalysisViewModel = viewModel(factory = factory)
    val frameExtractViewModel: FrameExtractViewModel = viewModel(factory = factory)
    val wifiViewModel: WifiTransferViewModel = viewModel(factory = factory)
    val networkViewModel: NetworkStorageViewModel = viewModel(factory = factory)
    val networkBrowserViewModel: NetworkBrowserViewModel = viewModel(factory = factory)
    val networkTransferViewModel: NetworkTransferViewModel = viewModel(factory = factory)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var activeTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var toolRouteName by rememberSaveable { mutableStateOf<String?>(null) }
    var apkEntry by remember { mutableStateOf<FileEntry?>(null) }
    var archiveEntry by remember { mutableStateOf<FileEntry?>(null) }
    var textEditorEntry by remember { mutableStateOf<FileEntry?>(null) }
    var analysisDirectory by rememberSaveable { mutableStateOf<String?>(null) }
    var frameExtractPath by remember { mutableStateOf<String?>(null) }
    var videoPlayerEntry by remember { mutableStateOf<com.aster.files.data.FileEntry?>(null) }
    var audioRequest by remember { mutableStateOf<AudioRequest?>(null) }
    var showAudioPlayer by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(audioOpenToken) {
        if (audioOpenToken > 0) {
            audioRequest = null
            showAudioPlayer = true
        }
    }

    val integrityState by integrityViewModel.state.collectAsState()
    val categoryState by categoryViewModel.state.collectAsState()
    val confirmDelete by container.deleteConfirmationPreferences.enabled.collectAsState()
    val trashRetentionDays by container.trashRetentionPreferences.days.collectAsState()
    val appsState by appsViewModel.state.collectAsState()
    val networkState by networkViewModel.state.collectAsState()
    val tabs = AsterTab.entries
    val toolRoute = toolRouteName?.let { name ->
        runCatching { ToolRoute.valueOf(name) }.getOrNull()
    }

    LaunchedEffect(activeTaskId) {
        val taskId = activeTaskId ?: return@LaunchedEffect
        if (!integrityState.creatingTask && integrityState.task?.taskId != taskId) {
            integrityViewModel.loadTask(taskId)
        }
    }

    BackHandler(enabled = activeTaskId != null) {
        activeTaskId = null
    }
    BackHandler(enabled = activeTaskId == null && toolRouteName != null) {
        toolRouteName = null
    }

    val playingVideo = videoPlayerEntry
    if (playingVideo != null) {
        VideoPlayerScreen(
            entry = playingVideo,
            onBack = { videoPlayerEntry = null }
        )
        return
    }

    if (showAudioPlayer) {
        AudioPlayerScreen(
            entry = audioRequest?.entry,
            queue = audioRequest?.queue.orEmpty(),
            onBack = { showAudioPlayer = false }
        )
        return
    }

    if (activeTaskId != null) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            IntegrityScreen(
                viewModel = integrityViewModel,
                fileService = container.fileService,
                rootPath = container.rootPath,
                snackbarHostState = snackbarHostState,
                onBack = { activeTaskId = null },
                confirmDelete = confirmDelete,
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    val framesToExtract = frameExtractPath
    if (framesToExtract != null) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            FrameExtractScreen(
                viewModel = frameExtractViewModel,
                path = framesToExtract,
                onBack = { frameExtractPath = null },
                onSaved = {
                    frameExtractPath = null
                    filesViewModel.refresh()
                    categoryViewModel.refresh()
                },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    val directoryToAnalyze = analysisDirectory
    if (directoryToAnalyze != null) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            DirectoryAnalysisScreen(
                viewModel = directoryAnalysisViewModel,
                path = directoryToAnalyze,
                rootPath = container.rootPath,
                fileService = container.fileService,
                snackbarHostState = snackbarHostState,
                onBack = { analysisDirectory = null },
                onSelectPath = { analysisDirectory = it },
                onStartIntegrity = { scope ->
                    integrityViewModel.startTask(scope) { taskId ->
                        activeTaskId = taskId
                    }
                },
                onPlayVideo = { videoPlayerEntry = it },
                onOpenText = { textEditorEntry = it },
                confirmDelete = confirmDelete,
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                MiniAudioPlayer(
                    onOpen = {
                        audioRequest = null
                        showAudioPlayer = true
                    }
                )
                NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = {
                            val wasSelected = selectedTabIndex == index
                            selectedTabIndex = index
                            if (tab != AsterTab.TOOLS) {
                                toolRouteName = null
                            }
                            when (tab) {
                                AsterTab.HOME -> Unit
                                AsterTab.CATEGORY -> {
                                    if (wasSelected) categoryViewModel.closeCategory()
                                    categoryViewModel.refresh()
                                }
                                AsterTab.FILES -> filesViewModel.refresh()
                                AsterTab.ANALYSIS -> integrityViewModel.loadRecentTasks()
                                AsterTab.TOOLS -> {
                                    if (wasSelected) toolRouteName = null
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
                }
            }
        }
    ) { padding ->
        when (tabs[selectedTabIndex]) {
            AsterTab.HOME -> HomeScreen(
                rootPath = container.rootPath,
                categoryResult = categoryState.result,
                categoryLoading = categoryState.loading,
                onOpenCategory = { category: FileCategory ->
                    categoryViewModel.openCategory(category)
                    selectedTabIndex = tabs.indexOf(AsterTab.CATEGORY)
                },
                onOpenCategories = {
                    categoryViewModel.closeCategory()
                    selectedTabIndex = tabs.indexOf(AsterTab.CATEGORY)
                    categoryViewModel.refresh()
                },
                onOpenFiles = {
                    selectedTabIndex = tabs.indexOf(AsterTab.FILES)
                },
                onOpenAnalysis = {
                    selectedTabIndex = tabs.indexOf(AsterTab.ANALYSIS)
                    integrityViewModel.loadRecentTasks()
                },
                onOpenTools = {
                    selectedTabIndex = tabs.indexOf(AsterTab.TOOLS)
                    toolRouteName = null
                },
                onOpenApps = {
                    selectedTabIndex = tabs.indexOf(AsterTab.TOOLS)
                    toolRouteName = ToolRoute.APPS.name
                    appsViewModel.refresh()
                },
                networkNodes = networkState.nodes,
                onOpenNetworkStorage = {
                    selectedTabIndex = tabs.indexOf(AsterTab.TOOLS)
                    toolRouteName = ToolRoute.NETWORK.name
                },
                onOpenNetworkNode = { nodeId ->
                    selectedTabIndex = tabs.indexOf(AsterTab.TOOLS)
                    networkBrowserViewModel.openNode(nodeId)
                    toolRouteName = ToolRoute.NETWORK_BROWSER.name
                },
                modifier = Modifier.padding(padding)
            )
            AsterTab.CATEGORY -> CategoryScreen(
                viewModel = categoryViewModel,
                fileService = container.fileService,
                archiveService = container.archiveService,
                zipPasswordStore = container.zipPasswordStore,
                snackbarHostState = snackbarHostState,
                onOpenApk = { apkEntry = it },
                onOpenArchive = { archiveEntry = it },
                onOpenText = { textEditorEntry = it },
                onExtractFrames = { frameExtractPath = it },
                onPlayVideo = { videoPlayerEntry = it },
                onPlayAudio = { entry, queue ->
                    audioRequest = AudioRequest(entry, queue)
                    showAudioPlayer = true
                },
                confirmDelete = confirmDelete,
                modifier = Modifier.padding(padding)
            )
            AsterTab.FILES -> FilesScreen(
                viewModel = filesViewModel,
                mainViewModel = mainViewModel,
                networkTransferViewModel = networkTransferViewModel,
                networkNodes = networkState.nodes,
                vaultManager = container.vaultManager,
                conversionService = container.mediaConversionService,
                fileService = container.fileService,
                archiveService = container.archiveService,
                zipPasswordStore = container.zipPasswordStore,
                snackbarHostState = snackbarHostState,
                onOpenApk = { apkEntry = it },
                onOpenArchive = { archiveEntry = it },
                onOpenText = { textEditorEntry = it },
                onAnalyzeDirectory = { analysisDirectory = it },
                onExtractFrames = { frameExtractPath = it },
                onPlayVideo = { videoPlayerEntry = it },
                onPlayAudio = { entry, queue ->
                    audioRequest = AudioRequest(entry, queue)
                    showAudioPlayer = true
                },
                confirmDelete = confirmDelete,
                modifier = Modifier.padding(padding)
            )
            AsterTab.ANALYSIS -> AnalysisScreen(
                analysisViewModel = analysisViewModel,
                integrityViewModel = integrityViewModel,
                fileService = container.fileService,
                rootPath = container.rootPath,
                snackbarHostState = snackbarHostState,
                onStartIntegrity = { scope ->
                    integrityViewModel.startTask(scope) { taskId ->
                        activeTaskId = taskId
                    }
                },
                onOpenTask = { taskId ->
                    activeTaskId = taskId
                },
                modifier = Modifier.padding(padding)
            )
            AsterTab.TOOLS -> when (toolRoute) {
                ToolRoute.TRASH -> TrashScreen(
                    viewModel = trashViewModel,
                    snackbarHostState = snackbarHostState,
                    retentionDays = trashRetentionDays,
                    onBack = { toolRouteName = null },
                    modifier = Modifier.padding(padding)
                )
                ToolRoute.APPS -> AppManagementScreen(
                    viewModel = appsViewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { toolRouteName = null },
                    modifier = Modifier.padding(padding)
                )
                ToolRoute.SETTINGS -> SettingsScreen(
                    viewModel = settingsViewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { toolRouteName = null },
                    modifier = Modifier.padding(padding)
                )
                ToolRoute.WIFI -> WifiTransferScreen(
                    viewModel = wifiViewModel,
                    onBack = { toolRouteName = null },
                    modifier = Modifier.padding(padding)
                )
                ToolRoute.NETWORK -> NetworkStorageScreen(
                    viewModel = networkViewModel,
                    snackbarMessage = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onOpenNode = { nodeId ->
                        networkBrowserViewModel.openNode(nodeId)
                        toolRouteName = ToolRoute.NETWORK_BROWSER.name
                    },
                    onBack = { toolRouteName = null },
                    modifier = Modifier.padding(padding)
                )
                ToolRoute.SPACE_CLEANER -> SpaceCleanerScreen(
                    cleaner = container.spaceCleaner,
                    trashService = container.trashService,
                    rootPath = container.rootPath,
                    onBack = { toolRouteName = null }
                )
                ToolRoute.VAULT -> com.aster.files.vault.VaultScreen(
                    vaultManager = container.vaultManager,
                    fileService = container.fileService,
                    onBack = { toolRouteName = null }
                )
                ToolRoute.NETWORK_BROWSER -> NetworkBrowserScreen(
                    viewModel = networkBrowserViewModel,
                    transferViewModel = networkTransferViewModel,
                    fileService = container.fileService,
                    rootPath = container.rootPath,
                    onBack = { toolRouteName = ToolRoute.NETWORK.name },
                    modifier = Modifier.padding(padding)
                )
                null -> ToolsScreen(
                    appsCount = appsState.apps.size,
                    onOpenTrash = {
                        toolRouteName = ToolRoute.TRASH.name
                        trashViewModel.refresh()
                    },
                    onOpenApps = {
                        toolRouteName = ToolRoute.APPS.name
                        appsViewModel.refresh()
                    },
                    onOpenSettings = {
                        toolRouteName = ToolRoute.SETTINGS.name
                    },
                    onOpenWifi = {
                        toolRouteName = ToolRoute.WIFI.name
                    },
                    onOpenNetworkStorage = {
                        toolRouteName = ToolRoute.NETWORK.name
                    },
                    onOpenVault = {
                        toolRouteName = ToolRoute.VAULT.name
                    },
                    onOpenSpaceCleaner = {
                        toolRouteName = ToolRoute.SPACE_CLEANER.name
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    apkEntry?.let { entry ->
        ApkActionDialog(
            entry = entry,
            onDismiss = { apkEntry = null },
            onViewArchive = {
                apkEntry = null
                archiveEntry = entry
            }
        )
    }

    archiveEntry?.let { entry ->
        ArchivePreviewDialog(
            entry = entry,
            archiveService = container.archiveService,
            onDismiss = { archiveEntry = null }
        )
    }

    textEditorEntry?.let { entry ->
        TextEditorDialog(
            entry = entry,
            fileService = container.fileService,
            snackbarHostState = snackbarHostState,
            onDismiss = { textEditorEntry = null }
        )
    }
}
