package com.aster.files.vault

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import com.aster.files.services.FileService
import com.aster.files.ui.components.DirectoryPickerDialog
import com.aster.files.ui.components.FilePreviewDialog
import com.aster.files.ui.components.FileThumbnail
import com.aster.files.ui.components.ImageViewerDialog
import com.aster.files.ui.components.formatBytes
import com.aster.files.ui.components.formatTime
import com.aster.files.ui.screens.VideoPlayerScreen
import com.aster.files.ui.viewmodel.FilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 隐私保险箱：PIN 解锁、私有目录浏览、预览与移出，后台 1 分钟自动上锁。 */
@Composable
fun VaultScreen(
    vaultManager: VaultManager,
    fileService: FileService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pinStore = remember { VaultPinStore(context) }
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(!pinStore.hasPin()) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var moveOutEntry by remember { mutableStateOf<FileEntry?>(null) }
    var imagePreview by remember { mutableStateOf<File?>(null) }
    var imageName by remember { mutableStateOf("") }
    var videoPreview by remember { mutableStateOf<File?>(null) }
    var videoName by remember { mutableStateOf("") }
    var textPreview by remember { mutableStateOf<FilePreview.Text?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var backgroundAt by remember { mutableStateOf<Long?>(null) }
    var biometricPrompted by remember { mutableStateOf(false) }
    val activity = context as? FragmentActivity
    val biometricAvailable = remember {
        activity != null && BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun wipePreview() {
        imagePreview?.delete()
        videoPreview?.delete()
        imagePreview = null
        videoPreview = null
        textPreview = null
        imageName = ""
        videoName = ""
        vaultManager.clearPreviewCache(context.cacheDir)
    }

    fun promptBiometric() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    pin = ""
                    error = null
                    biometricPrompted = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    error = "生物识别未完成，请使用 PIN"
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁隐私保险箱")
                .setSubtitle("使用指纹或面容验证")
                .setNegativeButtonText("使用 PIN")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                .build()
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (unlocked) backgroundAt = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_START -> {
                    val at = backgroundAt
                    if (unlocked && at != null && System.currentTimeMillis() - at > 60_000L) {
                        unlocked = false
                        pin = ""
                        entries = emptyList()
                        wipePreview()
                        biometricPrompted = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unlocked = false
            wipePreview()
        }
    }

    LaunchedEffect(unlocked, setup, biometricAvailable) {
        if (!unlocked && !setup && biometricAvailable && !biometricPrompted) {
            biometricPrompted = true
            promptBiometric()
        }
    }

    fun refresh() {
        loading = true
        scope.launch {
            entries = vaultManager.list()
            loading = false
        }
    }

    LaunchedEffect(unlocked) {
        if (unlocked) refresh()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!unlocked) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text("隐私保险箱", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (setup) "首次使用，请设置 4 位数字密码" else "输入 4 位密码解锁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!setup && !biometricAvailable) {
                    Text(
                        "当前设备不支持生物识别，请使用 PIN 码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
                    label = { Text("4 位密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (setup) {
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.length <= 4 && it.all(Char::isDigit)) confirmPin = it
                        },
                        label = { Text("确认密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    error = null
                    if (setup) {
                        if (pin != confirmPin) {
                            error = "两次输入不一致"
                        } else if (!pinStore.setPin(pin)) {
                            error = "请输入 4 位数字密码"
                        } else {
                            unlocked = true
                            setup = false
                            pin = ""
                            confirmPin = ""
                        }
                    } else if (!pinStore.verify(pin)) {
                        error = "密码错误"
                    } else {
                        unlocked = true
                        pin = ""
                    }
                }) { Text(if (setup) "设置并进入" else "解锁") }
                if (!setup && biometricAvailable) {
                    TextButton(onClick = { promptBiometric() }) {
                        Text("使用指纹 / 面容解锁")
                    }
                }
                TextButton(onClick = onBack) { Text("返回工具") }
            }
            return@Surface
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { unlocked = false; wipePreview(); onBack() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "退出保险箱")
                }
                Text("隐私保险箱", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { unlocked = false; pin = ""; wipePreview(); biometricPrompted = false }) {
                    Icon(Icons.Outlined.Lock, contentDescription = "锁定")
                }
            }
            Text(
                "文件仅存放在应用私有 vault 目录；退出或后台超过 1 分钟自动上锁。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (loading || previewLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            }
            if (entries.isEmpty() && !loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("保险箱为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    previewLoading = true
                                    error = null
                                    scope.launch {
                                        runCatching {
                                            when (entry.kind) {
                                                FileKind.IMAGE -> {
                                                    imageName = entry.name
                                                    imagePreview = vaultManager.decryptToCache(
                                                        entry.name,
                                                        context.cacheDir
                                                    )
                                                }
                                                FileKind.VIDEO -> {
                                                    videoName = entry.name
                                                    videoPreview = vaultManager.decryptToCache(
                                                        entry.name,
                                                        context.cacheDir
                                                    )
                                                }
                                                else -> {
                                                    textPreview = FilePreview.Text(
                                                        entry.name,
                                                        entry.path,
                                                        vaultManager.readText(entry.name)
                                                    )
                                                }
                                            }
                                        }.onFailure {
                                            error = it.message ?: "解密预览失败"
                                        }
                                        previewLoading = false
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileThumbnail(entry = entry, size = 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    formatBytes(entry.sizeBytes) + " · " + formatTime(entry.modifiedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { moveOutEntry = entry }) { Text("移出") }
                        }
                    }
                }
            }
        }

        moveOutEntry?.let { entry ->
            DirectoryPickerDialog(
                initialPath = fileService.rootPath,
                rootPath = fileService.rootPath,
                fileService = fileService,
                onDismiss = { moveOutEntry = null },
                onSelect = { target ->
                    moveOutEntry = null
                    scope.launch {
                        runCatching { vaultManager.moveOut(entry.name, target) }
                            .onSuccess {
                                Toast.makeText(context, "已移出保险箱", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                            .onFailure {
                                Toast.makeText(context, it.message ?: "移出失败", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            )
        }

        imagePreview?.let { file ->
            ImageViewerDialog(
                name = imageName,
                model = file,
                onDismiss = {
                    file.delete()
                    imagePreview = null
                }
            )
        }

        videoPreview?.let { file ->
            VideoPlayerScreen(
                entry = FileEntry(
                    name = videoName,
                    path = file.absolutePath,
                    isDirectory = false,
                    isSymlink = false,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    extension = videoName.substringAfterLast('.', ""),
                    kind = FileKind.VIDEO
                ),
                onBack = {
                    file.delete()
                    videoPreview = null
                }
            )
        }

        textPreview?.let { preview ->
            FilePreviewDialog(preview = preview, onDismiss = { textPreview = null })
        }
    }
}
