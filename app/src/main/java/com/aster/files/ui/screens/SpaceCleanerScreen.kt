package com.aster.files.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.services.CleanCandidate
import com.aster.files.services.CleanKind
import com.aster.files.services.SpaceCleaner
import com.aster.files.services.TrashService
import com.aster.files.ui.components.ConfirmDialog
import com.aster.files.ui.components.SelectionActionBar
import com.aster.files.ui.components.SelectionCheckbox
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.selection.SelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 空间清理大师：只读扫描，所有清理统一走 TrashService 软删除。 */
@Composable
fun SpaceCleanerScreen(
    cleaner: SpaceCleaner,
    trashService: TrashService,
    rootPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = remember { SelectionState() }
    var selectedKind by remember { mutableStateOf(CleanKind.EMPTY_DIRECTORY) }
    var results by remember { mutableStateOf<List<CleanCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var cleanupPaths by remember { mutableStateOf<List<String>?>(null) }
    var releasedBytes by remember { mutableStateOf(0L) }

    fun scan(kind: CleanKind) {
        selectedKind = kind
        selection.clear()
        loading = true
        scope.launch {
            results = cleaner.scan(kind, rootPath)
            loading = false
        }
    }

    fun cleanup(paths: List<String>) {
        scope.launch {
            loading = true
            val bytes = results.filter { it.path in paths }.sumOf { it.sizeBytes }
            val batch = withContext(Dispatchers.IO) { trashService.softDeleteBatch(paths) }
            val failed = batch.results.count { !it.success }
            releasedBytes = bytes
            loading = false
            selection.clear()
            Toast.makeText(
                context,
                if (failed == 0) "已释放 " + formatBytes(bytes) + " 空间" else "部分清理失败：" + failed + " 项",
                Toast.LENGTH_LONG
            ).show()
            results = cleaner.scan(selectedKind, rootPath)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text("空间清理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { scan(selectedKind) }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "重新扫描")
                }
            }
            Text(
                "只读扫描；清理统一移入回收站，可恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CleanKind.entries.forEach { kind ->
                    Card(
                        modifier = Modifier.weight(1f).clickable { scan(kind) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Icon(
                                Icons.Outlined.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (kind == selectedKind) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(kind.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            }
            Text(
                "扫描结果：" + results.size + " 项" +
                    if (releasedBytes > 0) " · 上次释放 " + formatBytes(releasedBytes) else "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results, key = { it.path }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selection.toggle(item.path)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SelectionCheckbox(
                                checked = item.path in selection.selectedPaths,
                                selectionMode = selection.active,
                                onCheckedChange = { selection.toggle(item.path) }
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.kind.label + " · " + formatBytes(item.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(item.path, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (selection.active) {
                    SelectionActionBar(
                        count = selection.count,
                        onDelete = { cleanupPaths = selection.selectedPaths.toList() },
                        onClose = { selection.clear() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        cleanupPaths?.let { paths ->
            ConfirmDialog(
                title = "移入回收站",
                text = "将选中的 " + paths.size + " 项移入回收站，可随时恢复。",
                confirmLabel = "清理",
                destructive = true,
                onConfirm = {
                    cleanupPaths = null
                    cleanup(paths)
                },
                onDismiss = { cleanupPaths = null }
            )
        }
    }
}
