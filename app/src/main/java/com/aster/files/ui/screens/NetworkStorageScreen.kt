package com.aster.files.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aster.files.network.NetworkClientFactory
import com.aster.files.network.NetworkNode
import com.aster.files.network.NetworkProtocol
import com.aster.files.network.RemoteException
import com.aster.files.ui.viewmodel.NetworkStorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkStorageScreen(
    viewModel: NetworkStorageViewModel,
    snackbarMessage: (String) -> Unit,
    onOpenNode: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<NetworkNode?>(null) }
    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (message != null) {
            snackbarMessage(message)
            if (state.message != null) viewModel.consumeMessage()
            if (state.error != null) viewModel.clearError()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回工具")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "网络存储",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "FTP / WebDAV 配置管理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = {
                    editingNode = null
                    showAdd = true
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("添加")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("占位与基础框架", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本轮保存服务器地址、用户名和密码。实际网络文件浏览将在 V3.1.0 实现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.nodes.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("尚未添加网络节点")
                        Text(
                            "点击右上角“添加”配置 FTP 或 WebDAV。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.nodes, key = { it.id }) { node ->
                NetworkNodeCard(
                    node = node,
                    onOpen = { onOpenNode(node.id) },
                    onEdit = {
                        editingNode = node
                        showAdd = true
                    },
                    onDelete = { viewModel.remove(node.id) }
                )
            }
        }
    }

    if (showAdd) {
        AddNetworkNodeDialog(
            initial = editingNode,
            onDismiss = {
                showAdd = false
                editingNode = null
            },
            onAdd = { name, protocol, address, username, password ->
                val editing = editingNode
                showAdd = false
                editingNode = null
                if (editing == null) {
                    viewModel.add(name, protocol, address, username, password)
                } else {
                    viewModel.update(editing.id, name, protocol, address, username, password)
                }
            }
        )
    }
}

@Composable
private fun NetworkNodeCard(
    node: NetworkNode,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    node.protocol.label + " · " + node.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (node.username.isNotBlank()) {
                    Text(
                        "用户 " + node.username,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑网络节点",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除网络节点",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNetworkNodeDialog(
    initial: NetworkNode?,
    onDismiss: () -> Unit,
    onAdd: (String, NetworkProtocol, String, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var username by remember(initial?.id) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial?.id) { mutableStateOf(initial?.password.orEmpty()) }
    var protocol by remember(initial?.id) {
        mutableStateOf(initial?.protocol ?: NetworkProtocol.FTP)
    }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var lastTestSuccess by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加网络存储" else "编辑网络存储") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkProtocol.values().forEach { value ->
                        FilterChip(
                            selected = protocol == value,
                            onClick = { protocol = value },
                            label = { Text(value.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true
                )
                if (protocol == NetworkProtocol.SMB) {
                    Text(
                        "SMB 格式：smb://192.168.1.x/ShareName（默认端口 445）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("服务器地址（host 或 URL）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（可选）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = !testing && name.isNotBlank() && address.isNotBlank(),
                        onClick = {
                            testing = true
                            testMessage = null
                            lastTestSuccess = false
                            val node = NetworkNode(
                                name = name.trim(),
                                protocol = protocol,
                                address = address.trim(),
                                username = username.trim(),
                                password = password
                            )
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        NetworkClientFactory.create(node).use { it.test() }
                                    }
                                }
                                testing = false
                                lastTestSuccess = result.isSuccess
                                testMessage = result.exceptionOrNull()?.let {
                                    if (it is RemoteException) it.message else
                                        "连接失败：" + (it.message ?: "网络不可用")
                                } ?: "连接成功，账号密码有效"
                            }
                        }
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (testing) "正在测试…" else "测试连接")
                    }
                    testMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lastTestSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
                Text(
                    "保存后可在网络存储列表点击节点，以多标签方式浏览。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, protocol, address, username, password) },
                enabled = name.isNotBlank() && address.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
