package com.aster.files.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aster.files.transfer.TransferCenter
import com.aster.files.transfer.TransferStatus
import com.aster.files.transfer.TransferTask

@Composable
fun TransferCenterDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        "传输列表",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { TransferCenter.clearFinished() },
                        enabled = TransferCenter.tasks.any {
                            it.status == TransferStatus.COMPLETED ||
                                it.status == TransferStatus.FAILED ||
                                it.status == TransferStatus.CANCELLED
                        }
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清理已结束任务")
                    }
                }
                val tasks: List<TransferTask> = TransferCenter.tasks
                val totalSpeed = tasks
                    .filter { it.status == TransferStatus.RUNNING }
                    .sumOf { it.speedBytesPerSecond }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "当前总速度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        formatTransferBytes(totalSpeed) + "/s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (tasks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("暂无传输任务")
                            Text(
                                "网络上传与下载会显示在这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            TransferTaskCard(task)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferTaskCard(task: TransferTask) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        task.direction + " · " + task.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (task.status) {
                            TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                            TransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    (task.progress * 100).toInt().coerceIn(0, 100).toString() + "%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            if (task.status == TransferStatus.RUNNING || task.status == TransferStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = task.progress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTransferBytes(task.copiedBytes) + " / " +
                        if (task.totalBytes > 0) formatTransferBytes(task.totalBytes) else "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (task.speedBytesPerSecond > 0) {
                    Text(
                        formatTransferBytes(task.speedBytesPerSecond) + "/s",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            task.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.status) {
                    TransferStatus.RUNNING -> {
                        TextButton(onClick = { TransferCenter.pause(task.id) }) {
                            Icon(Icons.Outlined.Pause, contentDescription = null)
                            Text("暂停")
                        }
                        TextButton(onClick = { TransferCenter.cancel(task.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                            Text("取消")
                        }
                    }
                    TransferStatus.PAUSED -> {
                        TextButton(onClick = { TransferCenter.resume(task.id) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Text("继续")
                        }
                        TextButton(onClick = { TransferCenter.cancel(task.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                            Text("取消")
                        }
                    }
                    TransferStatus.WAITING -> {
                        TextButton(onClick = { TransferCenter.cancel(task.id) }) {
                            Text("取消")
                        }
                    }
                    TransferStatus.FAILED -> {
                        TextButton(onClick = { TransferCenter.retry(task.id) }) {
                            Text("重试")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun formatTransferBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}
