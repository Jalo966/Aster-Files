package com.aster.files.ui.screens

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileCategory
import com.aster.files.data.RootInfo
import com.aster.files.network.NetworkNode
import com.aster.files.services.CategoryScanResult
import com.aster.files.ui.components.categoryIcon
import com.aster.files.ui.components.categoryLabel
import com.aster.files.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    rootPath: String,
    categoryResult: CategoryScanResult?,
    categoryLoading: Boolean,
    onOpenCategory: (FileCategory) -> Unit,
    onOpenCategories: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenApps: () -> Unit,
    networkNodes: List<NetworkNode> = emptyList(),
    onOpenNetworkStorage: () -> Unit = {},
    onOpenNetworkNode: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val storage by produceState<RootInfo?>(initialValue = null, key1 = rootPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stat = StatFs(rootPath)
                RootInfo(rootPath, stat.availableBytes, stat.totalBytes)
            }.getOrNull()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Aster Files",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "本地文件中心",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "存储空间",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            storage?.let { formatBytes(it.freeBytes) + " 可用" } ?: "读取中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val total = storage?.totalBytes ?: 0L
                    val free = storage?.freeBytes ?: 0L
                    val used = (total - free).coerceAtLeast(0L)
                    LinearProgressIndicator(
                        progress = if (total > 0L) used.toFloat() / total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已用 " + formatBytes(used) + " / 共 " + formatBytes(total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = rootPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text("快捷操作", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeActionCard(
                    icon = Icons.Outlined.Folder,
                    title = "文件",
                    subtitle = "浏览与管理",
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f)
                )
                HomeActionCard(
                    icon = Icons.Outlined.Category,
                    title = "分类",
                    subtitle = "按类型查找",
                    onClick = onOpenCategories,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeActionCard(
                    icon = Icons.Outlined.Insights,
                    title = "分析",
                    subtitle = "大文件与重复",
                    onClick = onOpenAnalysis,
                    modifier = Modifier.weight(1f)
                )
                HomeActionCard(
                    icon = Icons.Outlined.Apps,
                    title = "应用",
                    subtitle = "卸载与提取",
                    onClick = onOpenApps,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text("按类型浏览", style = MaterialTheme.typography.titleMedium)
        }
        item {
            CategoryShortcutRow(
                categories = listOf(FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.AUDIO),
                result = categoryResult,
                loading = categoryLoading,
                onOpenCategory = onOpenCategory
            )
        }
        item {
            CategoryShortcutRow(
                categories = listOf(FileCategory.DOCUMENT, FileCategory.APK, FileCategory.ARCHIVE),
                result = categoryResult,
                loading = categoryLoading,
                onOpenCategory = onOpenCategory
            )
        }

        if (networkNodes.isNotEmpty()) {
            item {
                Text("网络存储", style = MaterialTheme.typography.titleMedium)
            }
            items(networkNodes, key = { it.id }) { node ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNetworkNode(node.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                node.protocol.label + " · " + node.address,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("管理", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTools),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Build, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("工具", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "局域网传输、网络存储、回收站与设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text("进入", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryShortcutRow(
    categories: List<FileCategory>,
    result: CategoryScanResult?,
    loading: Boolean,
    onOpenCategory: (FileCategory) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { category ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenCategory(category) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        categoryIcon(category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(categoryLabel(category), style = MaterialTheme.typography.labelMedium)
                    Text(
                        when {
                            loading -> "…"
                            result == null -> "—"
                            else -> result.count(category).toString() + " 个"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
