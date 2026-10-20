package com.aster.files.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aster.files.ui.components.TransferCenterDialog

@Composable
fun ToolsScreen(
    appsCount: Int,
    onOpenTrash: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenNetworkStorage: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSpaceCleaner: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTransfers by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "工具",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "局域网传输、网络存储、回收站与设置集中在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ToolCard(
                icon = Icons.Outlined.Wifi,
                title = "Wi-Fi 传文件",
                subtitle = "同一局域网浏览器浏览 / 上传 / 下载",
                onClick = onOpenWifi
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.CloudQueue,
                title = "网络存储",
                subtitle = "FTP / WebDAV / SMB 浏览与文件管理",
                onClick = onOpenNetworkStorage
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.SwapVert,
                title = "传输列表",
                subtitle = "上传/下载进度、速度、暂停、继续与取消",
                onClick = { showTransfers = true }
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.CleaningServices,
                title = "空间清理",
                subtitle = "空文件夹、空文件、缓存残留与相似图片",
                onClick = onOpenSpaceCleaner
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.Lock,
                title = "隐私保险箱",
                subtitle = "4 位 PIN、私有 vault 目录、后台自动上锁",
                onClick = onOpenVault
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.DeleteOutline,
                title = "回收站",
                subtitle = "查看并恢复软删除的文件",
                onClick = onOpenTrash
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.Apps,
                title = "应用管理",
                subtitle = "已安装应用 " + appsCount + " 个 · 卸载 / 提取 APK",
                onClick = onOpenApps
            )
        }
        item {
            ToolCard(
                icon = Icons.Outlined.Settings,
                title = "设置",
                subtitle = "主题、开源组件、数据库 schema",
                onClick = onOpenSettings
            )
        }
    }
    if (showTransfers) {
        TransferCenterDialog(onDismiss = { showTransfers = false })
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}
