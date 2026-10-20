package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.data.ActionOperation
import com.aster.files.data.IntegrityActionRequest
import com.aster.files.data.IntegrityResult
import com.aster.files.data.IntegrityStatus
import com.aster.files.data.IntegrityTask
import com.aster.files.data.TaskStatus
import com.aster.files.integrity.IntegrityActionException
import com.aster.files.integrity.IntegrityActionHandler
import com.aster.files.integrity.IntegrityActionPreview
import com.aster.files.integrity.IntegrityActionResult
import com.aster.files.integrity.IntegrityScope
import com.aster.files.integrity.IntegrityStore
import com.aster.files.integrity.ManualIntegrityService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IntegrityUiState(
    val task: IntegrityTask? = null,
    val results: List<IntegrityResult> = emptyList(),
    val recentTasks: List<IntegrityTask> = emptyList(),
    val filter: IntegrityStatus? = null,
    val selectedResultIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val scanning: Boolean = false,
    val creatingTask: Boolean = false,
    val actionPreview: IntegrityActionPreview? = null,
    val actionRunning: Boolean = false,
    val actionJobId: String? = null,
    val error: String? = null,
    val message: String? = null
) {
    val visibleResults: List<IntegrityResult>
        get() = if (filter == null) results else results.filter { it.status == filter }

    val selectedResults: List<IntegrityResult>
        get() = results.filter { it.resultId in selectedResultIds }

    val normalSelected: Boolean
        get() = selectedResults.any { it.status == IntegrityStatus.NORMAL }
}

class IntegrityViewModel(
    private val service: ManualIntegrityService,
    private val store: IntegrityStore,
    private val actionHandler: IntegrityActionHandler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(IntegrityUiState())
    val state: StateFlow<IntegrityUiState> = mutableState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadRecentTasks()
    }

    fun startTask(scope: IntegrityScope, onCreated: (String) -> Unit) {
        if (mutableState.value.creatingTask) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    creatingTask = true,
                    scanning = false,
                    loading = false,
                    error = null,
                    message = null,
                    task = null,
                    results = emptyList(),
                    selectedResultIds = emptySet(),
                    filter = null,
                    actionPreview = null
                )
            }
            try {
                val task = withContext(ioDispatcher) { service.createTask(scope) }
                mutableState.update {
                    it.copy(task = task, creatingTask = false, scanning = true)
                }
                runCatching { onCreated(task.taskId) }
                startPolling(task.taskId)
                val finished = withContext(ioDispatcher) { service.runTask(task.taskId) }
                pollingJob?.cancel()
                loadTaskInternal(finished.taskId, showLoading = false)
                runCatching { loadRecentTasksInternal() }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        creatingTask = false,
                        scanning = false,
                        error = e.message ?: "无法开始完整性扫描"
                    )
                }
                runCatching { loadRecentTasksInternal() }
            }
        }
    }

    fun loadTask(taskId: String) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedResultIds = emptySet(),
                    filter = null,
                    actionPreview = null
                )
            }
            try {
                loadTaskInternal(taskId, showLoading = true)
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "无法读取完整性任务")
                }
            }
        }
    }

    fun cancelScan() {
        val task = mutableState.value.task ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { service.requestCancel(task.taskId) }
            mutableState.update { it.copy(message = "正在取消扫描…") }
        }
    }

    fun setFilter(status: IntegrityStatus?) {
        mutableState.update { it.copy(filter = status, selectedResultIds = emptySet()) }
    }

    fun toggleResultSelection(resultId: String) {
        mutableState.update {
            val next = if (resultId in it.selectedResultIds) {
                it.selectedResultIds - resultId
            } else {
                it.selectedResultIds + resultId
            }
            it.copy(selectedResultIds = next)
        }
    }

    fun selectAllVisible() {
        mutableState.update {
            it.copy(selectedResultIds = it.visibleResults.map { result -> result.resultId }.toSet())
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedResultIds = emptySet()) }
    }

    fun previewAction(
        operation: ActionOperation,
        targetDirectory: String? = null,
        autoConfirm: Boolean = false
    ) {
        val current = mutableState.value
        val task = current.task ?: return
        if (current.selectedResultIds.isEmpty()) {
            mutableState.update { it.copy(message = "请先选择失效文件") }
            return
        }
        viewModelScope.launch {
            try {
                val preview = withContext(ioDispatcher) {
                    actionHandler.preview(
                        IntegrityActionRequest(
                            taskId = task.taskId,
                            resultIds = current.selectedResultIds.toList(),
                            operation = operation,
                            targetDirectory = targetDirectory,
                            softDelete = operation == ActionOperation.DELETE
                        )
                    )
                }
                mutableState.update { it.copy(actionPreview = preview, error = null) }
                if (autoConfirm) confirmAction()
            } catch (e: IntegrityActionException) {
                mutableState.update {
                    it.copy(
                        message = e.message ?: "动作校验失败",
                        error = e.message ?: "动作校验失败"
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(error = e.message ?: "动作预览失败")
                }
            }
        }
    }

    fun confirmAction() {
        val preview = mutableState.value.actionPreview ?: return
        viewModelScope.launch {
            mutableState.update {
                it.copy(actionRunning = true, actionJobId = null, error = null, message = null)
            }
            try {
                val result = withContext(ioDispatcher) {
                    actionHandler.execute(preview, confirmed = true) { jobId ->
                        mutableState.update { it.copy(actionJobId = jobId) }
                    }
                }
                val summary = actionSummary(result)
                mutableState.update {
                    it.copy(
                        actionRunning = false,
                        actionJobId = null,
                        actionPreview = null,
                        selectedResultIds = emptySet(),
                        message = summary,
                        error = if (result.status == com.aster.files.data.ActionJobStatus.FAILED) {
                            summary
                        } else {
                            null
                        }
                    )
                }
                runCatching {
                    loadTaskInternal(preview.request.taskId, showLoading = false)
                    loadRecentTasksInternal()
                }
            } catch (e: IntegrityActionException) {
                mutableState.update {
                    it.copy(
                        actionRunning = false,
                        actionJobId = null,
                        error = e.message ?: "动作执行失败",
                        message = e.message ?: "动作执行失败"
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        actionRunning = false,
                        actionJobId = null,
                        error = e.message ?: "动作执行失败"
                    )
                }
            }
        }
    }

    fun cancelAction() {
        val jobId = mutableState.value.actionJobId ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { actionHandler.requestCancel(jobId) }
            mutableState.update { it.copy(message = "正在取消动作…") }
        }
    }

    fun dismissActionPreview() {
        if (mutableState.value.actionRunning) return
        mutableState.update { it.copy(actionPreview = null) }
    }

    fun loadRecentTasks() {
        viewModelScope.launch {
            runCatching { loadRecentTasksInternal() }
        }
    }

    fun clearHistory() {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null) }
            try {
                val removed = withContext(ioDispatcher) { store.clearFinishedTasks() }
                loadRecentTasksInternal()
                mutableState.update {
                    it.copy(
                        loading = false,
                        message = if (removed == 0) {
                            "没有可清除的历史任务"
                        } else {
                            "已清除 " + removed + " 条历史任务"
                        }
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "清除历史失败")
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

    private fun startPolling(taskId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val (task, results) = withContext(ioDispatcher) {
                    val currentTask = store.getTask(taskId)
                    if (currentTask == null) {
                        null
                    } else {
                        currentTask to store.listResults(taskId)
                    }
                } ?: break

                mutableState.update {
                    it.copy(
                        task = task,
                        results = results,
                        scanning = task.status == TaskStatus.PENDING ||
                            task.status == TaskStatus.RUNNING,
                        loading = false
                    )
                }
                if (task.status != TaskStatus.PENDING && task.status != TaskStatus.RUNNING) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadTaskInternal(taskId: String, showLoading: Boolean) {
        if (showLoading) {
            mutableState.update { it.copy(loading = true) }
        }
        val task = withContext(ioDispatcher) { store.getTask(taskId) }
        if (task == null) {
            mutableState.update {
                it.copy(loading = false, error = "完整性任务不存在")
            }
            return
        }
        val results = withContext(ioDispatcher) { store.listResults(taskId) }
        mutableState.update {
            it.copy(
                task = task,
                results = results,
                loading = false,
                scanning = task.status == TaskStatus.PENDING ||
                    task.status == TaskStatus.RUNNING,
                error = null
            )
        }
        if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
            startPolling(taskId)
        }
    }

    private suspend fun loadRecentTasksInternal() {
        val tasks = withContext(ioDispatcher) { store.listRecentTasks(20) }
        mutableState.update { it.copy(recentTasks = tasks) }
    }

    private fun actionSummary(result: IntegrityActionResult): String = when (result.status) {
        com.aster.files.data.ActionJobStatus.CANCELLED ->
            "操作已取消，已处理 " + result.processedCount + " 项"
        com.aster.files.data.ActionJobStatus.FAILED ->
            "操作失败：" + (result.error ?: "未知错误")
        else ->
            "操作完成，已处理 " + result.processedCount + " 项" +
                (result.error?.let { "（" + it + "）" } ?: "")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
