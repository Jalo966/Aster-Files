package com.aster.files.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aster.files.image.ApngDetector
import com.aster.files.ui.viewmodel.FilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilePreviewDialog(
    preview: FilePreview,
    onDismiss: () -> Unit,
    onExtractFrames: ((String) -> Unit)? = null
) {
    if (preview is FilePreview.Image) {
        var apng by remember(preview.path) { mutableStateOf(false) }
        LaunchedEffect(preview.path) {
            apng = withContext(Dispatchers.IO) {
                ApngDetector.isApng(File(preview.path))
            }
        }
        val isAnimated = preview.name.endsWith(".gif", ignoreCase = true) ||
            preview.name.endsWith(".apng", ignoreCase = true) ||
            apng
        ImageViewerDialog(
            name = preview.name,
            model = File(preview.path),
            onDismiss = onDismiss,
            apngPath = null,
            onExtractFrames = if (isAnimated && onExtractFrames != null) {
                {
                    onDismiss()
                    onExtractFrames(preview.path)
                }
            } else {
                null
            }
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = when (preview) {
                        is FilePreview.Text -> preview.name
                        is FilePreview.Metadata -> preview.name
                        is FilePreview.Image -> preview.name
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))

                when (preview) {
                    is FilePreview.Text -> {
                        Text(
                            text = preview.content,
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    is FilePreview.Metadata -> {
                        Text(
                            text = "该文件类型暂不支持内嵌预览。\n大小：" + formatBytes(preview.sizeBytes) +
                                "\n路径：" + preview.path,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is FilePreview.Image -> Unit
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
