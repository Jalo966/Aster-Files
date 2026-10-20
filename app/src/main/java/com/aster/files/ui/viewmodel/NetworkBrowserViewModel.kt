package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.network.NetworkClientFactory
import com.aster.files.network.NetworkNode
import com.aster.files.network.NetworkStorageStore
import com.aster.files.network.RemoteEntry
import com.aster.files.network.RemoteSorter
import com.aster.files.network.remoteJoin
import com.aster.files.network.remoteParent
import com.aster.files.ui.sort.FileSort
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkTabState(
    val id: String = UUID.randomUUID().toString(),
    val nodeId: String,
    val nodeName: String,
    val path: String
) {
    val title: String
        get() = if (path == "/") nodeName else nodeName + " - " + path.substringAfterLast('/')
}

data class NetworkBrowserState(
    val nodes: List<NetworkNode> = emptyList(),
    val tabs: List<NetworkTabState> = emptyList(),
    val activeTabId: String? = null,
    val activeNode: NetworkNode? = null,
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val sort: FileSort = FileSort.DEFAULT,
    val message: String? = null,
    val error: String? = null
) {
    val activeTab: NetworkTabState? get() = tabs.firstOrNull { it.id == activeTabId }
    val currentPath: String get() = activeTab?.path ?: "/"
}

class NetworkBrowserViewModel(
    private val store: NetworkStorageStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        NetworkBrowserState(nodes = store.load())
    )
    val state: StateFlow<NetworkBrowserState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun openNode(nodeId: String) {
        val node = store.load().firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(error = "网络节点不存在") }
            return
        }
        val existing = mutableState.value.tabs.firstOrNull { it.nodeId == nodeId }
        if (existing != null) {
            switchTab(existing.id)
            return
        }
        addTab(nodeId)
    }

    fun addTab(nodeId: String) {
        val node = store.load().firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(error = "网络节点不存在") }
            return
        }
        viewModelScope.launch {
            val root = withContext(ioDispatcher) {
                runCatching {
                    NetworkClientFactory.create(node).use { it.initialPath() }
                }.getOrDefault("/")
            }
            val tab = NetworkTabState(nodeId = node.id, nodeName = node.name, path = root)
            mutableState.update {
                it.copy(
                    nodes = store.load(),
                    tabs = it.tabs + tab,
                    activeTabId = tab.id,
                    activeNode = node
                )
            }
            loadActive()
        }
    }

    fun switchTab(tabId: String) {
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId } ?: return
        mutableState.update {
            it.copy(
                activeTabId = tabId,
                activeNode = it.nodes.firstOrNull { node -> node.id == tab.nodeId }
            )
        }
        loadActive()
    }

    fun closeTab(tabId: String) {
        val current = mutableState.value
        val index = current.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val remaining = current.tabs.filterNot { it.id == tabId }
        val active = if (current.activeTabId == tabId) {
            remaining.getOrNull((index - 1).coerceAtLeast(0))
        } else {
            current.tabs.firstOrNull { it.id == current.activeTabId }
        }
        mutableState.update {
            it.copy(
                tabs = remaining,
                activeTabId = active?.id,
                activeNode = active?.let { tab ->
                    it.nodes.firstOrNull { node -> node.id == tab.nodeId }
                },
                entries = if (active == null) emptyList() else it.entries,
                error = null
            )
        }
        if (active != null) loadActive()
    }

    fun navigate(path: String) {
        val tab = mutableState.value.activeTab ?: return
        mutableState.update {
            it.copy(
                tabs = it.tabs.map { value ->
                    if (value.id == tab.id) value.copy(path = path) else value
                }
            )
        }
        loadActive()
    }

    fun goUp() {
        val tab = mutableState.value.activeTab ?: return
        if (tab.path == "/") return
        navigate(remoteParent(tab.path))
    }

    fun refresh() {
        mutableState.update { it.copy(nodes = store.load()) }
        loadActive()
    }

    fun setSort(sort: FileSort) {
        mutableState.update {
            it.copy(sort = sort, entries = RemoteSorter.sort(it.entries, sort))
        }
    }

    fun retry() {
        loadActive()
    }

    fun createDirectory(name: String) {
        val node = mutableState.value.activeNode ?: return
        val path = remoteJoin(mutableState.value.currentPath, name)
        runRemoteOperation("新建文件夹") {
            NetworkClientFactory.create(node).use { it.createDirectory(path) }
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        val node = mutableState.value.activeNode ?: return
        runRemoteOperation("重命名") {
            NetworkClientFactory.create(node).use { it.rename(entry.path, newName) }
        }
    }

    fun delete(entries: List<RemoteEntry>) {
        val node = mutableState.value.activeNode ?: return
        if (entries.isEmpty()) return
        runRemoteOperation("删除") {
            NetworkClientFactory.create(node).use { client ->
                entries.forEach { client.delete(it.path, it.isDirectory) }
            }
        }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun runRemoteOperation(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = null) }
            try {
                withContext(ioDispatcher) { block() }
                mutableState.update { it.copy(busy = false, message = label + "完成") }
                loadActive()
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(busy = false, error = label + "失败：" + (e.message ?: "网络异常"))
                }
            }
        }
    }

    private fun loadActive() {
        loadJob?.cancel()
        val snapshot = mutableState.value
        val tab = snapshot.activeTab ?: run {
            mutableState.update {
                it.copy(loading = false, entries = emptyList(), error = null, activeNode = null)
            }
            return
        }
        val node = store.load().firstOrNull { it.id == tab.nodeId }
        if (node == null) {
            mutableState.update {
                it.copy(loading = false, error = "网络节点已删除", entries = emptyList())
            }
            return
        }
        mutableState.update {
            it.copy(
                loading = true,
                error = null,
                nodes = store.load(),
                activeNode = node
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val entries = withContext(ioDispatcher) {
                    NetworkClientFactory.create(node).use { client ->
                        client.list(tab.path)
                    }
                }
                mutableState.update {
                    if (it.activeTabId == tab.id) {
                        it.copy(
                            entries = RemoteSorter.sort(entries, it.sort),
                            loading = false,
                            error = null
                        )
                    } else {
                        it
                    }
                }
            } catch (e: Exception) {
                mutableState.update {
                    if (it.activeTabId == tab.id) {
                        it.copy(
                            loading = false,
                            entries = emptyList(),
                            error = e.message ?: "网络目录读取失败"
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }
}
