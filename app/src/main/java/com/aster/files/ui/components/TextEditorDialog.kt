package com.aster.files.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aster.files.data.FileEntry
import com.aster.files.services.FileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全屏 TXT/文本编辑器；读取和保存都经过 FileGuard。 */
@Composable
fun TextEditorDialog(
    entry: FileEntry,
    fileService: FileService,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    var content by remember(entry.path) { mutableStateOf("") }
    var original by remember(entry.path) { mutableStateOf("") }
    var loading by remember(entry.path) { mutableStateOf(true) }
    var saving by remember(entry.path) { mutableStateOf(false) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val syntaxColors = SyntaxColors(
        keyword = MaterialTheme.colorScheme.primary,
        string = MaterialTheme.colorScheme.tertiary,
        comment = MaterialTheme.colorScheme.outline,
        number = MaterialTheme.colorScheme.secondary,
        tag = MaterialTheme.colorScheme.error,
        attribute = MaterialTheme.colorScheme.primary
    )
    val transformation = remember(entry.extension, syntaxColors) {
        if (SyntaxHighlighter.isCodeFile(entry.extension)) {
            SyntaxHighlightTransformation(entry.extension, syntaxColors)
        } else {
            VisualTransformation.None
        }
    }

    LaunchedEffect(entry.path) {
        loading = true
        try {
            val text = withContext(Dispatchers.IO) {
                fileService.readTextFile(entry.path)
            }
            content = text
            original = text
        } catch (e: Exception) {
            error = e.message ?: "无法读取文本文件"
        }
        loading = false
    }

    fun save() {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                fileService.writeTextFile(entry.path, content)
            }
            saving = false
            if (result.success) {
                onDismiss()
                snackbarHostState.showSnackbar(result.message)
            } else {
                error = result.message
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!saving) onDismiss() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (saving) "保存中…" else "文本编辑器",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = ::save,
                        enabled = !loading && !saving && content != original
                    ) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("保存")
                    }
                }

                if (loading || saving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(12.dp)
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(scrollState)
                ) {
                    val lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.45f
                    Text(
                        text = (1..content.count { it == '\n' } + 1).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        lineHeight = lineHeight,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 16.dp)
                    )
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            lineHeight = lineHeight
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = transformation,
                        readOnly = loading || saving
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
