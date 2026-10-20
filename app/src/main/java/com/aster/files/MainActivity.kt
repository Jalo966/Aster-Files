package com.aster.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aster.files.ui.AsterRoot
import com.aster.files.ui.screens.PermissionScreen
import com.aster.files.ui.screens.VideoPipState
import com.aster.files.ui.screens.StartupScreen
import com.aster.files.ui.theme.AsterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : FragmentActivity() {

    private val container: AppContainer by lazy {
        (application as AsterApplication).container
    }

    private var hasStorageAccess by mutableStateOf(false)
    private var pendingIncomingUri: Uri? = null
    private var importingIncoming = false
    private var audioOpenToken by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStorageAccess = hasStoragePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasStorageAccess = hasStoragePermission()
        handleIncomingIntent(intent)
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9901)
        }
        setContent {
            val themeMode by container.themePreference.mode.collectAsState()
            val themeColor by container.themePreference.color.collectAsState()
            val ready by container.ready.collectAsState()
            AsterTheme(mode = themeMode, themeColor = themeColor) {
                when {
                    !hasStorageAccess -> PermissionScreen(
                        onRequestPermission = ::requestStoragePermission
                    )
                    !ready -> StartupScreen()
                    else -> AsterRoot(container, audioOpenToken = audioOpenToken)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        VideoPipState.inPip = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            VideoPipState.screenOpen &&
            VideoPipState.playing
        ) {
            runCatching {
                VideoPipState.params()?.let { enterPictureInPictureMode(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasStorageAccess = hasStoragePermission()
        val pending = pendingIncomingUri
        if (hasStorageAccess && pending != null) {
            importIncomingUri(pending)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == com.aster.files.playback.AudioPlaybackService.ACTION_OPEN_AUDIO_PLAYER) {
            audioOpenToken++
            return
        }
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val uri = intent.data ?: stream ?: intent.clipData?.getItemAt(0)?.uri ?: return
        pendingIncomingUri = uri
        if (hasStorageAccess) importIncomingUri(uri)
    }

    private fun importIncomingUri(uri: Uri) {
        if (importingIncoming) return
        importingIncoming = true
        pendingIncomingUri = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment
                        ?: ("imported_" + System.currentTimeMillis())
                    val safeName = sanitizeFileName(displayName)
                    val downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    if (!downloads.exists() && !downloads.mkdirs()) {
                        error("无法创建 Download 目录")
                    }
                    var target = File(downloads, safeName)
                    var suffix = 1
                    while (target.exists()) {
                        val dot = safeName.lastIndexOf('.')
                        val base = if (dot > 0) safeName.substring(0, dot) else safeName
                        val ext = if (dot > 0) safeName.substring(dot) else ""
                        target = File(downloads, base + "_" + suffix + ext)
                        suffix++
                    }
                    val input = if (uri.scheme == "file") {
                        FileInputStream(File(uri.path ?: error("文件路径为空")))
                    } else {
                        contentResolver.openInputStream(uri)
                            ?: error("无法读取分享的文件流")
                    }
                    input.use { source ->
                        FileOutputStream(target).use { sink -> source.copyTo(sink) }
                    }
                    target
                }
            }
            importingIncoming = false
            result.onSuccess { file ->
                Toast.makeText(
                    this@MainActivity,
                    "已接收文件到 Download/" + file.name,
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "接收文件失败：" + (error.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = File(name).name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "imported_" + System.currentTimeMillis() }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        val read = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            try {
                startActivity(appIntent)
                return
            } catch (_: Exception) {
                // 回退到全局所有文件访问页面。
            }
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                return
            } catch (_: Exception) {
                // 回退到应用详情页。
            }
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)
                    )
                )
            } catch (_: Exception) {
                // 无可用的系统设置页面。
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
