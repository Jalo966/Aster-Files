package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aster.files.services.BatchRenameMode
import com.aster.files.services.BatchRenameOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameDialog(
    fileCount: Int,
    sampleName: String,
    onDismiss: () -> Unit,
    onConfirm: (BatchRenameOptions) -> Unit
) {
    var mode by remember { mutableStateOf(BatchRenameMode.PREFIX_SEQUENCE) }
    var prefix by remember { mutableStateOf("file_") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        val options = when (mode) {
            BatchRenameMode.PREFIX_SEQUENCE -> {
                if (prefix.isBlank()) {
                    error = "请输入前缀"
                    return
                }
                BatchRenameOptions(mode = mode, prefix = prefix)
            }
            BatchRenameMode.FIND_REPLACE -> {
                if (find.isEmpty()) {
                    error = "请输入查找内容"
                    return
                }
                BatchRenameOptions(mode = mode, find = find, replace = replace)
            }
        }
        onConfirm(options)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "已选 " + fileCount + " 个文件 · 示例：" + sampleName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == BatchRenameMode.PREFIX_SEQUENCE,
                        onClick = { mode = BatchRenameMode.PREFIX_SEQUENCE; error = null },
                        label = { Text("前缀 + 序号") }
                    )
                    FilterChip(
                        selected = mode == BatchRenameMode.FIND_REPLACE,
                        onClick = { mode = BatchRenameMode.FIND_REPLACE; error = null },
                        label = { Text("查找与替换") }
                    )
                }
                when (mode) {
                    BatchRenameMode.PREFIX_SEQUENCE -> {
                        OutlinedTextField(
                            value = prefix,
                            onValueChange = { prefix = it; error = null },
                            label = { Text("前缀") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "示例：" + prefix + "001" + sampleExtension(sampleName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BatchRenameMode.FIND_REPLACE -> {
                        OutlinedTextField(
                            value = find,
                            onValueChange = { find = it; error = null },
                            label = { Text("查找内容") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = replace,
                            onValueChange = { replace = it; error = null },
                            label = { Text("替换为") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "仅替换主文件名，保留扩展名。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "所有目标文件名会先经过 FileGuard 与冲突预检；任一冲突将整体中止。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) { Text("开始重命名") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun sampleExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot) else ""
}
