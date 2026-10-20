package com.aster.files.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.files.network.LanAddressResolver
import com.aster.files.network.LanWebServer
import com.aster.files.security.FileGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WifiTransferState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val ip: String? = null,
    val port: Int = 8080,
    val url: String? = null,
    val error: String? = null
)

class WifiTransferViewModel(
    private val context: Context,
    private val guard: FileGuard,
    private val rootPath: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutableState = MutableStateFlow(WifiTransferState())
    val state: StateFlow<WifiTransferState> = mutableState.asStateFlow()
    private var server: LanWebServer? = null

    fun start() {
        if (server != null || mutableState.value.starting) return
        viewModelScope.launch {
            mutableState.update { it.copy(starting = true, error = null) }
            val ip = withContext(ioDispatcher) {
                LanAddressResolver.resolve(context)
            }
            val newServer = LanWebServer(8080, rootPath, guard)
            try {
                withContext(ioDispatcher) {
                    newServer.start(5000, false)
                }
                server = newServer
                mutableState.update {
                    it.copy(
                        starting = false,
                        running = true,
                        ip = ip,
                        url = "http://" + (ip ?: "<设备IP>") + ":8080"
                    )
                }
            } catch (e: Exception) {
                runCatching { newServer.stop() }
                mutableState.update {
                    it.copy(
                        starting = false,
                        running = false,
                        error = "无法启动 8080 端口：" + (e.message ?: "未知错误")
                    )
                }
            }
        }
    }

    fun stop() {
        val current = server
        server = null
        runCatching { current?.stop() }
        mutableState.update {
            it.copy(starting = false, running = false, url = null, ip = null, error = null)
        }
    }

    override fun onCleared() {
        runCatching { server?.stop() }
        server = null
        super.onCleared()
    }
}
