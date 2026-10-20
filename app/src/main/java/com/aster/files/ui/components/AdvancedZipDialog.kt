package com.aster.files.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aster.files.services.ZipAlgorithm
import com.aster.files.services.ZipEncryptionMode
import com.aster.files.services.ZipFormat
import com.aster.files.services.ZipOptions
import com.aster.files.services.ZipSizeUnit
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedZipDialog(
    sourcePaths: List<String>,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, ZipOptions) -> Unit
) {
    var name by remember(sourcePaths, initialName) { mutableStateOf(initialName) }
    var level by remember { mutableStateOf(5) }
    var format by remember { mutableStateOf(ZipFormat.ZIP) }
    var algorithm by remember { mutableStateOf(ZipAlgorithm.DEFLATE) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var encryption by remember { mutableStateOf(ZipEncryptionMode.AES_256) }
    var splitSizeText by remember { mutableStateOf("0") }
    var splitUnit by remember { mutableStateOf(ZipSizeUnit.MB) }
    var splitUnitMenu by remember { mutableStateOf(false) }
    var deleteSources by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = "请输入压缩包名称"
            return
        }
        if (protect && password.isBlank()) {
            error = "请输入密码"
            return
        }
        val rawSize = splitSizeText.trim()
        val sizeValue = if (rawSize.isEmpty()) 0.0 else rawSize.toDoubleOrNull()
        if (sizeValue == null) {
            error = "请输入合法的分卷大小"
            return
        }
        if (sizeValue < 0.0) {
            error = "分卷大小不能为负数"
            return
        }
        val splitBytes = if (sizeValue == 0.0) {
            0L
        } else {
            val multiplier = if (splitUnit == ZipSizeUnit.GB) {
                1024.0 * 1024.0 * 1024.0
            } else {
                1024.0 * 1024.0
            }
            val bytesDouble = sizeValue * multiplier
            if (!bytesDouble.isFinite() || bytesDouble > ZipSizeUnit.MAX_SPLIT_BYTES.toDouble()) {
                error = "分卷大小不能超过 100 GB"
                return
            }
            bytesDouble.toLong().coerceAtLeast(64L * 1024L)
        }
        onConfirm(
            trimmed,
            ZipOptions(
                compressionLevel = level,
                password = if (protect) password else null,
                encryption = encryption,
                splitSizeBytes = splitBytes,
                deleteSources = deleteSources,
                format = format,
                algorithm = algorithm
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级压缩") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("压缩包名称", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("压缩格式", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZipFormat.values().forEach { item ->
                        FilterChip(
                            selected = format == item,
                            enabled = item.supported,
                            onClick = { format = item },
                            label = {
                                Text(
                                    if (item.supported) item.label
                                    else item.label + "（占位）"
                                )
                            }
                        )
                    }
                }
                Text(
                    "当前 zip4j 支持 ZIP；7Z、TAR.GZ 为 UI 占位。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("压缩算法", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZipAlgorithm.values().forEach { item ->
                        FilterChip(
                            selected = algorithm == item,
                            enabled = item.supported,
                            onClick = { algorithm = item },
                            label = {
                                Text(
                                    if (item.supported) item.label
                                    else item.label + "（占位）"
                                )
                            }
                        )
                    }
                }
                Text(
                    "当前底层支持 Deflate；Bzip2 为 UI 占位。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("压缩级别", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "存储", 1 to "最快", 5 to "标准", 9 to "极限")
                        .chunked(2)
                        .forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (value, label) ->
                                    FilterChip(
                                        selected = level == value,
                                        onClick = { level = value },
                                        label = { Text("$label($value)") }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("密码保护", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "密码将保存在 Android Keystore 加密的本地存储中，解压时自动匹配。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = protect, onCheckedChange = { protect = it; error = null })
                }
                if (protect) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("加密方式", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ZipEncryptionMode.values().forEach { mode ->
                            FilterChip(
                                selected = encryption == mode,
                                onClick = { encryption = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
                Text("分卷压缩", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = splitSizeText,
                        onValueChange = { splitSizeText = it; error = null },
                        label = { Text("每卷大小（0 为不分卷）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        TextButton(onClick = { splitUnitMenu = true }) {
                            Text(splitUnit.label + " ▾")
                        }
                        DropdownMenu(
                            expanded = splitUnitMenu,
                            onDismissRequest = { splitUnitMenu = false }
                        ) {
                            ZipSizeUnit.values().forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.label) },
                                    onClick = {
                                        splitUnit = unit
                                        splitUnitMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                val previewValue = splitSizeText.trim().toDoubleOrNull()
                val previewMb = when {
                    previewValue == null || previewValue <= 0.0 -> 0.0
                    splitUnit == ZipSizeUnit.GB -> previewValue * 1024.0
                    else -> previewValue
                }
                Text(
                    if (previewMb <= 0.0) {
                        "当前：不分卷"
                    } else {
                        "实际分卷大小：" + previewMb.toString().removeSuffix(".0") + " MB"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("压缩后删除源文件", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "删除操作经过 FileGuard 并写入审计日志。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deleteSources,
                        onCheckedChange = { deleteSources = it }
                    )
                }
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "共 " + sourcePaths.size + " 项源文件/文件夹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = ::start) { Text("开始压缩") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
