package com.aster.files.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aster.files.services.AnimatedImageType
import com.aster.files.services.FrameImageFormat
import com.aster.files.ui.components.EmptyState
import com.aster.files.ui.viewmodel.FrameExtractUiState
import com.aster.files.ui.viewmodel.FrameExtractViewModel
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack

@Composable
fun FrameExtractScreen(
    viewModel: FrameExtractViewModel,
    path: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFormatDialog by remember { mutableStateOf(false) }
    var saveAllMode by remember { mutableStateOf(false) }

    LaunchedEffect(path) { viewModel.load(path) }
    BackHandler(enabled = !state.saving) { onBack() }
    LaunchedEffect(state.saveMessage) {
        val message = state.saveMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeSaveMessage()
        onSaved()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, enabled = !state.saving) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回图片")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "抽帧选择",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.info?.let {
                        (if (it.type == AnimatedImageType.GIF) "GIF" else "APNG") +
                            " · " + it.frameCount + " 帧"
                    } ?: "正在解析动图…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.info != null) {
                TextButton(
                    onClick = {
                        if (state.selected.size == state.info?.frameCount) {
                            viewModel.clearSelection()
                        } else {
                            viewModel.selectAll()
                        }
                    }
                ) {
                    Text(
                        if (state.selected.size == state.info?.frameCount) "取消全选" else "全选"
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("正在读取帧…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.info == null -> EmptyState(
                    "无法抽帧",
                    state.error ?: "仅支持 GIF/APNG 动图",
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(count = state.info?.frameCount ?: 0, key = { it }) { index ->
                        FrameCell(
                            index = index,
                            selected = index in state.selected,
                            state = state,
                            onToggle = { viewModel.toggle(index) },
                            load = { viewModel.ensureThumbnail(index) }
                        )
                    }
                }
            }
        }

        if (state.info != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选择 " + state.selected.size + " 帧",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    onClick = {
                        saveAllMode = true
                        showFormatDialog = true
                    },
                    enabled = !state.saving
                ) {
                    Text("导出所有帧")
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        saveAllMode = false
                        showFormatDialog = true
                    },
                    enabled = state.selected.isNotEmpty() && !state.saving
                ) {
                    Text("保存")
                }
            }
        }
    }

    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            title = { Text("选择保存格式") },
            text = { Text("PNG 支持透明背景；JPG 体积更小。将保存到「动图文件名_frames」文件夹。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFormatDialog = false
                        if (saveAllMode) viewModel.saveAll(path, FrameImageFormat.PNG)
                        else viewModel.save(path, FrameImageFormat.PNG)
                    }
                ) { Text("PNG") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFormatDialog = false
                        if (saveAllMode) viewModel.saveAll(path, FrameImageFormat.JPG)
                        else viewModel.save(path, FrameImageFormat.JPG)
                    }
                ) { Text("JPG") }
            }
        )
    }
}

@Composable
private fun FrameCell(
    index: Int,
    selected: Boolean,
    state: FrameExtractUiState,
    onToggle: () -> Unit,
    load: () -> Unit
) {
    LaunchedEffect(index) { load() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onToggle)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val bitmap = state.thumbnails[index]
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "第 " + (index + 1) + " 帧",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Text(
                "#" + (index + 1),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
