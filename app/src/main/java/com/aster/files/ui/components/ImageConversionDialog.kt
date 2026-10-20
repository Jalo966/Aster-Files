package com.aster.files.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aster.files.services.ImageOutputFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageConversionDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (ImageOutputFormat, Int) -> Unit
) {
    var format by remember { mutableStateOf(ImageOutputFormat.JPG) }
    var quality by remember { mutableStateOf(80) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量图片转换") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("共 $count 张图片，输出到原目录 converted/ 子目录。")
                Text("输出格式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageOutputFormat.entries.forEach { option ->
                        FilterChip(
                            selected = format == option,
                            onClick = { format = option },
                            label = { Text(option.name) }
                        )
                    }
                }
                Text("质量", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 80).forEach { value ->
                        FilterChip(
                            selected = quality == value,
                            onClick = { quality = value },
                            label = { Text(if (value == 100) "100% 无损" else "80% 压缩") }
                        )
                    }
                }
                Text(
                    if (format == ImageOutputFormat.PNG) "PNG 无损，质量参数仅对 JPG/WebP 生效。" else "转换期间请勿退出应用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(format, quality) }) { Text("开始转换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
