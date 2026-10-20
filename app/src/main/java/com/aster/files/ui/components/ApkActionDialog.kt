package com.aster.files.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aster.files.data.FileEntry
import java.io.File

object ApkInstaller {

    const val APK_MIME = "application/vnd.android.package-archive"

    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        )

    fun installIntent(context: Context, apkPath: String): Intent {
        val file = File(apkPath).canonicalFile
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * APK 点击菜单：安装，或作为压缩包查看内部条目。
 */
@Composable
fun ApkActionDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onViewArchive: () -> Unit
) {
    val context = LocalContext.current
    var pendingInstall by remember { mutableStateOf<String?>(null) }
    var showSigning by remember { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val path = pendingInstall
        pendingInstall = null
        if (path != null) {
            if (ApkInstaller.canInstall(context)) {
                launchInstaller(context, path)
                onDismiss()
            } else {
                Toast.makeText(context, "尚未允许安装未知应用", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun install() {
        if (!ApkInstaller.canInstall(context)) {
            pendingInstall = entry.path
            try {
                settingsLauncher.launch(ApkInstaller.settingsIntent(context))
                Toast.makeText(context, "请允许 Aster Files 安装未知应用", Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "无法打开未知来源设置", Toast.LENGTH_LONG).show()
            }
            return
        }
        launchInstaller(context, entry.path)
        onDismiss()
    }

    if (showSigning) {
        ApkSigningDialog(
            entry = entry,
            onDismiss = {
                showSigning = false
                onDismiss()
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text(
                    "APK 可以安装，也可以只查看内部文件列表。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        install()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) { Text("安装应用") }
                TextButton(
                    onClick = {
                        onDismiss()
                        onViewArchive()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("作为压缩包查看") }
                TextButton(
                    onClick = { showSigning = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("修改签名") }
                if (!ApkInstaller.canInstall(context)) {
                    Text(
                        "首次安装需要允许“安装未知应用”；授权后会自动重试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun launchInstaller(context: Context, path: String) {
    try {
        val intent = ApkInstaller.installIntent(context, path)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的 APK 安装器", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "无法打开安装器", Toast.LENGTH_LONG).show()
    }
}
