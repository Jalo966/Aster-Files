package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.network.NetworkClientFactory
import com.aster.files.network.NetworkStorageStore
import com.aster.files.network.RemoteEntry
import com.aster.files.security.FileGuard
import com.aster.files.transfer.TransferCenter
import com.aster.files.transfer.TransferNotifications
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkTransferState(
    val active: Boolean = false,
    val label: String = "",
    val progress: Float? = null,
    val message: String? = null,
    val error: String? = null
)

class NetworkTransferViewModel(
    private val store: NetworkStorageStore,
    private val guard: FileGuard,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(NetworkTransferState())
    val state: StateFlow<NetworkTransferState> = mutableState.asStateFlow()

    fun download(
        nodeId: String,
        remote: RemoteEntry,
        targetDirectory: String
    ) {
        val node = store.load().firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(error = "网络节点不存在") }
            return
        }
        val taskId = TransferCenter.create("下载 " + remote.name, "下载")
        TransferCenter.registerRetry(taskId) { download(nodeId, remote, targetDirectory) }
        viewModelScope.launch {
            mutableState.update {
                it.copy(active = true, label = "下载 " + remote.name, progress = 0f, error = null, message = null)
            }
            var created: File? = null
            var acquired = false
            try {
                TransferCenter.acquire(taskId)
                acquired = true
                val result = withContext(ioDispatcher) {
                    val directory = guard.guardTargetDirectory(targetDirectory).file
                    val plan = uniquePlan(directory.path, remote.name)
                    guard.verifyCreationTarget(plan)
                    created = plan.target
                    NetworkClientFactory.create(node).use { client ->
                        plan.target.outputStream().buffered().use { output ->
                            client.download(remote.path, output) { copied, total ->
                                TransferCenter.gate(taskId)?.check()
                                TransferCenter.update(taskId, copied, total)
                                val ratio = if (total > 0) copied.toFloat() / total else 0f
                                mutableState.update {
                                    it.copy(progress = ratio.coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    plan.target
                }
                TransferCenter.complete(taskId)
                TransferCenter.get(taskId)?.let { TransferNotifications.completed(it) }
                if (acquired) TransferCenter.release()
                acquired = false
                mutableState.update {
                    it.copy(
                        active = false,
                        progress = null,
                        message = "已下载到 " + result.absolutePath
                    )
                }
            } catch (e: Exception) {
                created?.let { runCatching { it.delete() } }
                if (acquired) TransferCenter.release()
                acquired = false
                if (TransferCenter.isCancelled(taskId) || e is CancellationException) {
                    TransferCenter.cancel(taskId)
                    mutableState.update {
                        it.copy(active = false, progress = null, message = "下载已取消")
                    }
                } else {
                    TransferCenter.fail(taskId, e.message ?: "网络异常")
                    mutableState.update {
                        it.copy(
                            active = false,
                            progress = null,
                            error = "下载失败：" + (e.message ?: "网络异常")
                        )
                    }
                }
            }
        }
    }

    fun downloadBatch(
        nodeId: String,
        entries: List<RemoteEntry>,
        targetDirectory: String
    ) {
        if (entries.isEmpty()) return
        val node = store.load().firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(error = "网络节点不存在") }
            return
        }
        val taskId = TransferCenter.create("批量下载 " + entries.size + " 项", "下载")
        TransferCenter.registerRetry(taskId) { downloadBatch(nodeId, entries, targetDirectory) }
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    active = true,
                    label = "批量下载 " + entries.size + " 项",
                    progress = 0f,
                    error = null,
                    message = null
                )
            }
            var acquired = false
            try {
                TransferCenter.acquire(taskId)
                acquired = true
                withContext(ioDispatcher) {
                    val directory = guard.guardTargetDirectory(targetDirectory).file
                    NetworkClientFactory.create(node).use { client ->
                        entries.forEachIndexed { index, entry ->
                            if (entry.isDirectory) return@forEachIndexed
                            val plan = uniquePlan(directory.path, entry.name)
                            guard.verifyCreationTarget(plan)
                            runCatching {
                                plan.target.outputStream().buffered().use { output ->
                                    client.download(entry.path, output) { copied, total ->
                                        TransferCenter.gate(taskId)?.check()
                                        val fileRatio = if (total > 0) {
                                            copied.toFloat() / total
                                        } else {
                                            0f
                                        }
                                        val overall = ((index + fileRatio) / entries.size)
                                            .coerceIn(0f, 1f)
                                        TransferCenter.update(
                                            taskId,
                                            (overall * 1_000_000L).toLong(),
                                            1_000_000L
                                        )
                                        mutableState.update {
                                            it.copy(progress = overall)
                                        }
                                    }
                                }
                            }.onFailure {
                                runCatching { plan.target.delete() }
                                throw it
                            }
                        }
                    }
                }
                TransferCenter.complete(taskId)
                TransferCenter.get(taskId)?.let { TransferNotifications.completed(it) }
                if (acquired) TransferCenter.release()
                acquired = false
                mutableState.update {
                    it.copy(
                        active = false,
                        progress = null,
                        message = "批量下载完成"
                    )
                }
            } catch (e: Exception) {
                if (acquired) TransferCenter.release()
                acquired = false
                if (TransferCenter.isCancelled(taskId) || e is CancellationException) {
                    TransferCenter.cancel(taskId)
                    mutableState.update {
                        it.copy(active = false, progress = null, message = "批量下载已取消")
                    }
                } else {
                    TransferCenter.fail(taskId, e.message ?: "网络异常")
                    mutableState.update {
                        it.copy(
                            active = false,
                            progress = null,
                            error = "批量下载失败：" + (e.message ?: "网络异常")
                        )
                    }
                }
            }
        }
    }

    fun uploadLocal(
        nodeId: String,
        paths: List<String>,
        remoteDirectory: String,
        deleteAfter: Boolean
    ) {
        if (paths.isEmpty()) return
        val node = store.load().firstOrNull { it.id == nodeId }
        if (node == null) {
            mutableState.update { it.copy(error = "网络节点不存在") }
            return
        }
        val taskId = TransferCenter.create("上传到 " + node.name, "上传")
        TransferCenter.registerRetry(taskId) { uploadLocal(nodeId, paths, remoteDirectory, deleteAfter) }
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    active = true,
                    label = "上传到 " + node.name,
                    progress = 0f,
                    error = null,
                    message = null
                )
            }
            var acquired = false
            try {
                TransferCenter.acquire(taskId)
                acquired = true
                withContext(ioDispatcher) {
                    NetworkClientFactory.create(node).use { client ->
                        val targetDirectory = if (remoteDirectory == "/") {
                            client.initialPath()
                        } else {
                            remoteDirectory
                        }
                        paths.forEachIndexed { index, path ->
                            val local = File(path)
                            val baseProgress = index.toFloat() / paths.size
                            client.upload(local, targetDirectory) { copied, total ->
                                TransferCenter.gate(taskId)?.check()
                                val fileRatio = if (total > 0) copied.toFloat() / total else 0f
                                val overall = (baseProgress + fileRatio / paths.size)
                                    .coerceIn(0f, 1f)
                                TransferCenter.update(
                                    taskId,
                                    (overall * 1_000_000L).toLong(),
                                    1_000_000L
                                )
                                mutableState.update {
                                    it.copy(progress = overall)
                                }
                            }
                            if (deleteAfter) {
                                val guarded = guard.guardForWrite(local.absolutePath)
                                if (!guarded.file.deleteRecursively()) {
                                    throw IllegalStateException("已上传但无法删除本地文件：" + local.name)
                                }
                            }
                        }
                    }
                }
                TransferCenter.complete(taskId)
                TransferCenter.get(taskId)?.let { TransferNotifications.completed(it) }
                if (acquired) TransferCenter.release()
                acquired = false
                mutableState.update {
                    it.copy(
                        active = false,
                        progress = null,
                        message = if (deleteAfter) {
                            "已上传并移除 " + paths.size + " 个本地文件"
                        } else {
                            "已上传 " + paths.size + " 个文件"
                        }
                    )
                }
            } catch (e: Exception) {
                if (acquired) TransferCenter.release()
                acquired = false
                if (TransferCenter.isCancelled(taskId) || e is CancellationException) {
                    TransferCenter.cancel(taskId)
                    mutableState.update {
                        it.copy(active = false, progress = null, message = "上传已取消")
                    }
                } else {
                    TransferCenter.fail(taskId, e.message ?: "网络异常")
                    mutableState.update {
                        it.copy(
                            active = false,
                            progress = null,
                            error = "上传失败：" + (e.message ?: "网络异常")
                        )
                    }
                }
            }
        }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    private fun uniquePlan(
        parent: String,
        name: String
    ): com.aster.files.security.CreationPlan {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        repeat(50) { index ->
            val candidate = if (index == 0) {
                name
            } else {
                base + " (" + index + ")" + if (extension.isEmpty()) "" else "." + extension
            }
            runCatching { return guard.prepareCreate(parent, candidate) }
        }
        return guard.prepareCreate(parent, "download_" + System.currentTimeMillis())
    }
}
