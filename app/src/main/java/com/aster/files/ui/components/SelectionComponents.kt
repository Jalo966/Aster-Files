package com.aster.files.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aster.files.ui.selection.SelectionStats
import com.aster.files.ui.selection.SelectionStatsCalculator

/** 多选时统一放大到 32dp，方便连续点击；未进入多选时保持 24dp。 */
@Composable
fun SelectionCheckbox(
    checked: Boolean,
    selectionMode: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selectionMode) 1f else 20f / 32f,
        label = "selection_checkbox_scale"
    )
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            .size(32.dp)
            .scale(scale)
    )
}

data class FileSheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionBottomSheet(
    title: String,
    actions: List<FileSheetAction>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            actions.forEach { action ->
                TextButton(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (action.icon != null) {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        action.label,
                        modifier = Modifier.weight(1f),
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SelectionActionBar(
    count: Int,
    onClose: () -> Unit,
    onDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onConvert: (() -> Unit)? = null,
    onVault: (() -> Unit)? = null,
    onProperties: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onZip: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "退出多选")
            }
            Text(
                "已选 " + count,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End
            ) {
                onDownload?.let { SelectionAction("下载", Icons.Outlined.Download, it) }
                onConvert?.let { SelectionAction("转换", Icons.Outlined.Autorenew, it) }
                onVault?.let { SelectionAction("保险箱", Icons.Outlined.Lock, it) }
                onProperties?.let { SelectionAction("属性", Icons.Outlined.Info, it) }
                onRename?.let { SelectionAction("重命名", Icons.Outlined.Edit, it) }
                onCopy?.let { SelectionAction("复制", Icons.Outlined.ContentCopy, it) }
                onMove?.let { SelectionAction("移动", Icons.Outlined.DriveFileMove, it) }
                onZip?.let { SelectionAction("压缩", Icons.Outlined.Archive, it) }
                onDelete?.let {
                    SelectionAction("删除", Icons.Outlined.Delete, it, destructive = true)
                }
                onDetails?.let { SelectionAction("详情", Icons.Outlined.Info, it) }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .width(48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
fun SelectionDetailsDialog(
    paths: List<String>,
    onDismiss: () -> Unit
) {
    var loading by remember(paths) { mutableStateOf(true) }
    var stats by remember(paths) { mutableStateOf<SelectionStats?>(null) }
    var error by remember(paths) { mutableStateOf<String?>(null) }

    LaunchedEffect(paths) {
        loading = true
        error = null
        try {
            stats = SelectionStatsCalculator.calculate(paths)
        } catch (e: Exception) {
            error = e.message ?: "统计失败"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选中详情") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.TopStart
            ) {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在递归统计大小…")
                }
                error != null -> Text(error ?: "统计失败")
                else -> {
                    val value = stats ?: SelectionStats(0, 0, 0L)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            buildString {
                                append("文件夹总数：")
                                append(value.folderCount)
                                append('\n')
                                append("文件总数：")
                                append(value.fileCount)
                                append('\n')
                                append("总占用大小：")
                                append(formatBytes(value.totalBytes))
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "文件夹内部文件已递归统计；符号链接不计入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
