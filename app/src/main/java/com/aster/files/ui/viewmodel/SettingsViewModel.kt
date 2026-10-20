package com.aster.files.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.data.Repository
import com.aster.files.diagnostics.CacheManager
import com.aster.files.diagnostics.CrashLogManager
import com.aster.files.ui.settings.DeleteConfirmationPreferences
import com.aster.files.ui.settings.TrashRetentionPreferences
import com.aster.files.ui.theme.ThemeColor
import com.aster.files.ui.theme.ThemeMode
import com.aster.files.ui.theme.ThemePreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class OpenSourceComponent(
    val name: String,
    val version: String,
    val license: String,
    val packageUrl: String,
    val isCopyleft: Boolean,
    val boundary: String
)

data class SettingsUiState(
    val rootPath: String = "",
    val components: List<OpenSourceComponent> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeColor: ThemeColor = ThemeColor.DEFAULT,
    val confirmDelete: Boolean = true,
    val trashRetentionDays: Int = 30,
    val loading: Boolean = false,
    val cacheSizeBytes: Long = 0L,
    val exportedFile: File? = null,
    val exportedCrashLog: File? = null,
    val schemaJson: String? = null,
    val message: String? = null,
    val error: String? = null
)

class SettingsViewModel(
    context: Context,
    private val repository: Repository,
    rootPath: String,
    private val themePreference: ThemePreference,
    private val deleteConfirmationPreferences: DeleteConfirmationPreferences,
    private val trashRetentionPreferences: TrashRetentionPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val appContext = context.applicationContext

    private val mutableState = MutableStateFlow(
        SettingsUiState(
            rootPath = rootPath,
            themeMode = themePreference.mode.value,
            themeColor = themePreference.color.value,
            confirmDelete = deleteConfirmationPreferences.enabled.value,
            trashRetentionDays = trashRetentionPreferences.days.value
        )
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            themePreference.mode.collect { mode ->
                mutableState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            themePreference.color.collect { color ->
                mutableState.update { it.copy(themeColor = color) }
            }
        }
        viewModelScope.launch {
            deleteConfirmationPreferences.enabled.collect { enabled ->
                mutableState.update { it.copy(confirmDelete = enabled) }
            }
        }
        viewModelScope.launch {
            trashRetentionPreferences.days.collect { days ->
                mutableState.update { it.copy(trashRetentionDays = days) }
            }
        }
        loadComponents()
        refreshCacheSize()
    }

    fun setTheme(mode: ThemeMode) {
        themePreference.setMode(mode)
    }

    fun setThemeColor(color: ThemeColor) {
        themePreference.setColor(color)
    }

    fun setConfirmDelete(enabled: Boolean) {
        deleteConfirmationPreferences.setEnabled(enabled)
    }

    fun setTrashRetentionDays(days: Int) {
        trashRetentionPreferences.setDays(days)
    }

    fun exportSchema() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val schema = withContext(ioDispatcher) { repository.exportSchemaJson() }
                val directory = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val file = File(directory, "aster-schema.json")
                withContext(ioDispatcher) { file.writeText(schema, Charsets.UTF_8) }
                mutableState.update {
                    it.copy(
                        loading = false,
                        schemaJson = schema,
                        exportedFile = file,
                        message = "schema 已导出"
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "schema 导出失败")
                }
            }
        }
    }

    fun consumeExportedFile() {
        mutableState.update { it.copy(exportedFile = null) }
    }

    fun consumeExportedCrashLog() {
        mutableState.update { it.copy(exportedCrashLog = null) }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = withContext(ioDispatcher) { CacheManager.sizeBytes(appContext.cacheDir) }
            mutableState.update { it.copy(cacheSizeBytes = size) }
        }
    }

    fun clearCache() {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null) }
            try {
                val freed = withContext(ioDispatcher) { CacheManager.clear(appContext.cacheDir) }
                val remaining = withContext(ioDispatcher) {
                    CacheManager.sizeBytes(appContext.cacheDir)
                }
                val mb = String.format(Locale.ROOT, "%.1f", freed / (1024.0 * 1024.0))
                mutableState.update {
                    it.copy(
                        loading = false,
                        cacheSizeBytes = remaining,
                        message = "已清理 " + mb + " MB 缓存"
                    )
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "缓存清理失败")
                }
            }
        }
    }

    fun exportCrashLogs() {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null) }
            try {
                val target = File(appContext.cacheDir, "exports/aster-crash-logs.zip")
                val exported = withContext(ioDispatcher) {
                    CrashLogManager.store(appContext).exportZip(target)
                }
                if (exported == null) {
                    mutableState.update {
                        it.copy(loading = false, message = "暂无崩溃日志")
                    }
                } else {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            exportedCrashLog = exported,
                            message = "崩溃日志已打包"
                        )
                    }
                }
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(loading = false, error = e.message ?: "崩溃日志导出失败")
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

    private fun loadComponents() {
        viewModelScope.launch {
            val components = withContext(ioDispatcher) { readComponentsFromAssets() }
            mutableState.update { it.copy(components = components) }
        }
    }

    private fun readComponentsFromAssets(): List<OpenSourceComponent> {
        val fallback = fallbackComponents()
        return try {
            val text = appContext.assets.open("open-source-components.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val objectValue = JSONObject(text)
            val array = objectValue.optJSONArray("components") ?: JSONArray()
            val result = ArrayList<OpenSourceComponent>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result.add(
                    OpenSourceComponent(
                        name = item.optString("name"),
                        version = item.optString("packageVersion"),
                        license = item.optString("licenseExpression"),
                        packageUrl = item.optString("packageUrl"),
                        isCopyleft = item.optBoolean("isCopyleft", false),
                        boundary = item.optString("integrationBoundary")
                    )
                )
            }
            result.ifEmpty { fallback }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun fallbackComponents(): List<OpenSourceComponent> = listOf(
        OpenSourceComponent("Kotlin", "1.9.0", "Apache-2.0", "https://kotlinlang.org/", false, "APK"),
        OpenSourceComponent("Kotlin Coroutines", "1.7.3", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", false, "APK"),
        OpenSourceComponent("AndroidX Core", "1.10.1", "Apache-2.0", "https://developer.android.com/jetpack/androidx", false, "APK"),
        OpenSourceComponent("Jetpack Compose", "2023.06.01 BOM", "Apache-2.0", "https://developer.android.com/jetpack/compose", false, "APK"),
        OpenSourceComponent("Material Icons Extended", "1.4.x", "Apache-2.0", "https://fonts.google.com/icons", false, "APK"),
        OpenSourceComponent("JUnit 4", "4.13.2", "EPL-1.0", "https://junit.org/junit4/", false, "测试依赖，不随 APK 打包")
    )
}
