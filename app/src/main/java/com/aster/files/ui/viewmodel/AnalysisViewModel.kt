package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.services.AnalysisResult
import com.aster.files.services.AnalysisService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisUiState(
    val result: AnalysisResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class AnalysisViewModel(
    private val analysisService: AnalysisService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(AnalysisUiState(loading = true))
    val state: StateFlow<AnalysisUiState> = mutableState.asStateFlow()

    init {
        analyze()
    }

    fun analyze() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val result = withContext(ioDispatcher) { analysisService.analyze() }
                mutableState.update { it.copy(result = result, loading = false) }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "分析失败")
                }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
