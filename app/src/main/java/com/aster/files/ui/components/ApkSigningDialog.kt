package com.aster.files.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aster.files.data.FileEntry
import com.aster.files.security.ApkSigningEngine
import com.aster.files.security.FileGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** APK 重签名：真实读取 keystore、剥离旧签名，产出 *_signed.apk。 */
@Composable
fun ApkSigningDialog(
    entry: FileEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ApkSigningConfigStore(context) }
    val guard = remember { FileGuard() }
    val initial = remember { store.load() }
    var keystoreUri by remember { mutableStateOf(initial.keystoreUri) }
    var keystorePath by remember { mutableStateOf(initial.keystorePath) }
    var storePassword by remember { mutableStateOf(initial.storePassword) }
    var keyAlias by remember { mutableStateOf(initial.keyAlias) }
    var keyPassword by remember { mutableStateOf(initial.keyPassword) }
    var signing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedPath by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            keystoreUri = uri.toString()
            keystorePath = ""
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    fun startSigning() {
        if (signing) return
        if (keystoreUri.isBlank() && keystorePath.isBlank()) {
            error = "请先选择 .jks / .keystore 文件，或填写绝对路径"
            return
        }
        if (keyAlias.isBlank()) {
            error = "Key Alias 不能为空"
            return
        }
        store.save(
            ApkSigningConfig(
                keystoreUri = keystoreUri,
                keystorePath = keystorePath,
                storePassword = storePassword,
                keyAlias = keyAlias,
                keyPassword = keyPassword
            )
        )
        signing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = File(entry.path)
                    require(apk.isFile) { "APK 文件不存在" }
                    val directKeystore = keystorePath.trim().takeIf { it.isNotBlank() }?.let(::File)
                    val keystore = directKeystore ?: File(context.cacheDir, "keystore_" + System.currentTimeMillis())
                    try {
                        if (directKeystore != null) {
                            require(directKeystore.isFile) { "keystore 路径不存在：" + directKeystore.path }
                        } else {
                            context.contentResolver.openInputStream(Uri.parse(keystoreUri))?.use { input ->
                                keystore.outputStream().use { output -> input.copyTo(output) }
                            } ?: error("无法读取 keystore")
                        }
                        val tempOutput = File(context.cacheDir, "signed_" + System.currentTimeMillis() + ".apk")
                        ApkSigningEngine.sign(
                            inputApk = apk,
                            outputApk = tempOutput,
                            keystoreFile = keystore,
                            storePassword = storePassword.toCharArray(),
                            alias = keyAlias,
                            keyPassword = keyPassword.toCharArray()
                        )
                        try {
                            val parent = apk.parentFile ?: error("APK 父目录不存在")
                            val baseName = apk.name.removeSuffix(".apk")
                            var plan: com.aster.files.security.CreationPlan? = null
                            var index = 0
                            while (plan == null && index < 30) {
                                val candidate = if (index == 0) {
                                    baseName + "_signed.apk"
                                } else {
                                    baseName + "_signed (" + index + ").apk"
                                }
                                plan = runCatching {
                                    guard.prepareCreate(parent.absolutePath, candidate)
                                }.getOrNull()
                                if (plan != null && plan.target.exists()) plan = null
                                index++
                            }
                            val creation = plan ?: error("无法创建签名输出文件")
                            guard.verifyCreationTarget(creation)
                            tempOutput.inputStream().use { input ->
                                creation.target.outputStream().use { output -> input.copyTo(output) }
                            }
                            guard.verifyCreationTarget(creation)
                            creation.target.absolutePath
                        } finally {
                            tempOutput.delete()
                        }
                    } finally {
                        if (directKeystore == null) keystore.delete()
                    }
                }
            }
            signing = false
            result.onSuccess { signedPath = it }
                .onFailure { error = it.message ?: "签名失败" }
        }
    }

    signedPath?.let { path ->
        AlertDialog(
            onDismissRequest = { signedPath = null; onDismiss() },
            title = { Text("签名完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已生成新的已签名 APK：")
                    Text(path, style = MaterialTheme.typography.bodySmall)
                    Text("已通过 FileGuard 写入并完成 v1/v2 签名。")
                }
            },
            confirmButton = {
                Button(onClick = {
                    installApk(context, path)
                    signedPath = null
                    onDismiss()
                }) { Text("直接安装") }
            },
            dismissButton = {
                TextButton(onClick = { signedPath = null; onDismiss() }) { Text("完成") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!signing) onDismiss() },
        title = { Text("修改 APK 签名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "选择 .jks / .keystore，读取别名后剥离旧 META-INF 签名，重新生成 v1/v2 签名 APK。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = !signing,
                    onClick = {
                        runCatching {
                            picker.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/x-java-keystore",
                                    "*/*"
                                )
                            )
                        }.onFailure {
                            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text(if (keystoreUri.isBlank()) "选择 keystore 文件" else "重新选择 keystore") }
                if (keystoreUri.isNotBlank()) {
                    Text(
                        keystoreUri,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = keystorePath,
                    onValueChange = { keystorePath = it },
                    label = { Text("keystore 绝对路径（可选）") },
                    placeholder = { Text("/sdcard/Download/my.jks") },
                    singleLine = true,
                    enabled = !signing
                )
                OutlinedTextField(
                    value = storePassword,
                    onValueChange = { storePassword = it },
                    label = { Text("Store Password") },
                    singleLine = true,
                    enabled = !signing,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = keyAlias,
                    onValueChange = { keyAlias = it },
                    label = { Text("Key Alias") },
                    singleLine = true,
                    enabled = !signing
                )
                OutlinedTextField(
                    value = keyPassword,
                    onValueChange = { keyPassword = it },
                    label = { Text("Key Password") },
                    singleLine = true,
                    enabled = !signing,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (signing) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("正在重签名，请稍候…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = !signing &&
                        (keystoreUri.isNotBlank() || keystorePath.isNotBlank()) &&
                        keyAlias.isNotBlank(),
                    onClick = { startSigning() }
                ) { Text("开始签名") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !signing, onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun installApk(context: android.content.Context, path: String) {
    try {
        val intent = ApkInstaller.installIntent(context, path)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "无法启动安装器", Toast.LENGTH_LONG).show()
    }
}
