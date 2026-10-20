package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileEntry
import com.aster.files.services.ArchiveService
import com.aster.files.services.ZipArchiveInfo
import com.aster.files.services.ZipPasswordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AdvancedZipActionDialog(
    entry: FileEntry,
    archiveService: ArchiveService,
    passwordStore: ZipPasswordStore,
    onPreview: () -> Unit,
    onExtractHere: (password: String?, deleteSource: Boolean) -> Unit,
    onExtractTo: (password: String?, deleteSource: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var info by remember(entry.path) { mutableStateOf<ZipArchiveInfo?>(null) }
    var loading by remember(entry.path) { mutableStateOf(true) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    var password by remember(entry.path) { mutableStateOf("") }
    var autoFilled by remember(entry.path) { mutableStateOf(false) }
    var deleteSource by remember(entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.path) {
        loading = true
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val archive = archiveService.inspectZip(entry.path)
                val saved = passwordStore.find(entry.path)
                archive to saved
            }
            info = loaded.first
            if (!loaded.second.isNullOrBlank()) {
                password = loaded.second ?: ""
                autoFilled = true
            }
        } catch (e: Exception) {
            error = e.message ?: "ZIP 检测失败"
        } finally {
            loading = false
        }
    }

    fun validateAndExtract(extract: (String?, Boolean) -> Unit) {
        val current = info
        if (current?.encrypted == true && password.isBlank()) {
            error = "该 ZIP 已加密，请输入密码"
            return
        }
        extract(if (current?.encrypted == true) password else null, deleteSource)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在检测 ZIP…")
                    }
                } else {
                    val current = info
                    if (current != null) {
                        Text(
                            buildString {
                                append(if (current.encrypted) "加密 ZIP" else "普通 ZIP")
                                append(" · ")
                                append(current.entryCount)
                                append(" 项")
                                if (current.split) append(" · 分卷")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (current.encrypted) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; error = null },
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (autoFilled) {
                                Text(
                                    "已从本地加密记录自动回填密码",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (error != null) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("解压后删除原 ZIP", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "删除操作经过 FileGuard 并写入审计日志。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deleteSource,
                            onCheckedChange = { deleteSource = it }
                        )
                    }
                    if (info?.encrypted != true) {
                        TextButton(onClick = onPreview) {
                            Text("预览内部")
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = { validateAndExtract(onExtractTo) },
                    enabled = info != null && !loading
                ) { Text("选择目录…") }
                TextButton(
                    onClick = { validateAndExtract(onExtractHere) },
                    enabled = info != null && !loading
                ) { Text("解压到当前目录") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
