package com.aster.files.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aster.files.network.NetworkNode
import com.aster.files.network.NetworkProtocol
import com.aster.files.network.NetworkStorageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetworkStorageUiState(
    val nodes: List<NetworkNode> = emptyList(),
    val message: String? = null,
    val error: String? = null
)

class NetworkStorageViewModel(
    private val store: NetworkStorageStore
) : ViewModel() {

    private val mutableState = MutableStateFlow(NetworkStorageUiState(nodes = store.load()))
    val state: StateFlow<NetworkStorageUiState> = mutableState.asStateFlow()

    fun add(
        name: String,
        protocol: NetworkProtocol,
        address: String,
        username: String,
        password: String
    ) {
        if (name.isBlank() || address.isBlank()) {
            mutableState.update { it.copy(error = "名称和地址不能为空") }
            return
        }
        store.add(
            NetworkNode(
                name = name.trim(),
                protocol = protocol,
                address = address.trim(),
                username = username.trim(),
                password = password
            )
        )
        mutableState.update {
            it.copy(nodes = store.load(), message = "已添加网络存储", error = null)
        }
    }

    fun update(
        id: String,
        name: String,
        protocol: NetworkProtocol,
        address: String,
        username: String,
        password: String
    ) {
        if (name.isBlank() || address.isBlank()) {
            mutableState.update { it.copy(error = "名称和地址不能为空") }
            return
        }
        store.update(
            NetworkNode(
                id = id,
                name = name.trim(),
                protocol = protocol,
                address = address.trim(),
                username = username.trim(),
                password = password
            )
        )
        mutableState.update {
            it.copy(nodes = store.load(), message = "已更新网络存储", error = null)
        }
    }

    fun remove(id: String) {
        store.remove(id)
        mutableState.update {
            it.copy(nodes = store.load(), message = "已删除网络存储", error = null)
        }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
