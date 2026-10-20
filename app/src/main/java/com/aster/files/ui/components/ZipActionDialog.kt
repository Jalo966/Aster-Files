package com.aster.files.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aster.files.data.FileEntry

/** ZIP 点击后的三种操作，避免直接进入预览而无法解压。 */
@Composable
fun ZipActionDialog(
    entry: FileEntry,
    onPreview: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text("选择 ZIP 操作：")
                TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("预览内部")
                }
                TextButton(onClick = onExtractHere, modifier = Modifier.fillMaxWidth()) {
                    Text("解压到当前目录")
                }
                TextButton(onClick = onExtractTo, modifier = Modifier.fillMaxWidth()) {
                    Text("解压到…")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
