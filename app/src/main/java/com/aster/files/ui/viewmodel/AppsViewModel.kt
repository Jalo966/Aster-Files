package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.services.AppService
import com.aster.files.services.InstalledAppInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppsUiState(
    val apps: List<InstalledAppInfo> = emptyList(),
    val loading: Boolean = true,
    val extractingPackage: String? = null,
    val message: String? = null,
    val error: String? = null
)

class AppsViewModel(
    private val appService: AppService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val apps = withContext(ioDispatcher) { appService.listApps() }
                mutableState.update { it.copy(apps = apps, loading = false) }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "读取应用列表失败")
                }
            }
        }
    }

    fun extractApk(packageName: String) {
        if (mutableState.value.extractingPackage != null) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(extractingPackage = packageName, error = null, message = null)
            }
            val result = withContext(ioDispatcher) {
                appService.extractApk(packageName)
            }
            mutableState.update {
                it.copy(
                    extractingPackage = null,
                    message = result.message,
                    error = if (result.success) null else result.message
                )
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
