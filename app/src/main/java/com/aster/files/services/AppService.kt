package com.aster.files.services

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.security.CreationPlan
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.LinkedHashSet
import java.util.Locale

/** 可启动应用的最小展示信息。 */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean
)

/**
 * 已安装应用列表与 APK 提取。
 *
 * 应用来源路径由 PackageManager 提供，位于 /data/app 等系统目录，不能经过
 * FileGuard 的可浏览根；写入目标仍然全部经过 FileGuard。
 */
class AppService(
    private val context: Context,
    private val guard: FileGuard,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun listApps(): List<InstalledAppInfo> = withContext(ioDispatcher) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        val activities = packageManager.queryIntentActivities(launcherIntent, 0)
        val packages = LinkedHashSet<String>()
        activities.forEach { resolve ->
            resolve.activityInfo?.packageName?.let(packages::add)
        }

        packages.mapNotNull { packageName ->
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                InstalledAppInfo(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString(),
                    versionName = packageInfo.versionName ?: "未知版本",
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedWith(
            compareBy<InstalledAppInfo> { it.label.lowercase(Locale.getDefault()) }
                .thenBy { it.packageName }
        )
    }

    /** 默认导出到 Download/AsterFiles/APKs。 */
    suspend fun ensureExtractionDirectory(): String = withContext(ioDispatcher) {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath
        val asterFiles = ensureDirectory(downloads, "AsterFiles")
        ensureDirectory(asterFiles, "APKs")
    }

    /**
     * 提取应用的基础 APK。带 split 的应用只提取 base APK。
     * 目标文件已存在时自动追加序号，不覆盖已有文件。
     */
    suspend fun extractApk(packageName: String): OperationResult = withContext(ioDispatcher) {
        var plan: CreationPlan? = null
        var sourcePath: String? = null
        try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val source = File(appInfo.sourceDir)
            sourcePath = source.absolutePath
            if (!source.isFile) {
                return@withContext OperationResult.failed(
                    message = "应用 APK 源文件不可读",
                    errorCode = "APK_SOURCE_UNAVAILABLE",
                    sourcePath = sourcePath
                )
            }

            val targetDirectory = ensureExtractionDirectory()
            val label = packageManager.getApplicationLabel(appInfo).toString()
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "unknown"
            val baseName = sanitizeName(label + "-" + version).ifBlank { packageName }
            plan = createUniqueApkPlan(targetDirectory, baseName)
            guard.verifyCreationTarget(plan)

            source.inputStream().use { input ->
                Files.newOutputStream(plan.target.toPath()).use { output ->
                    input.copyTo(output)
                }
            }
            if (Files.size(plan.target.toPath()) != source.length()) {
                throw java.io.IOException("写入后大小不一致")
            }
            FileTransferSupport.verifyCreated(guard, plan)

            val result = OperationResult.ok(
                message = "已提取到 " + plan.targetPath,
                sourcePath = sourcePath,
                targetPath = plan.targetPath
            )
            auditApp("EXTRACT_APK", result, packageName)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            plan?.let { FileTransferSupport.cleanupTarget(guard, it) }
            val result = OperationResult.failed(
                message = e.message ?: "提取 APK 失败",
                errorCode = if (e is FileGuardException) e.stableCode else "APK_EXTRACT_FAILED",
                sourcePath = sourcePath,
                targetPath = plan?.targetPath
            )
            auditApp("EXTRACT_APK", result, packageName)
            result
        }
    }

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName))

    private suspend fun auditApp(
        operation: String,
        result: OperationResult,
        packageName: String
    ) {
        audit.record(
            category = AuditCategory.APP,
            operation = operation,
            status = if (result.success) AuditStatus.SUCCESS else AuditStatus.FAILED,
            sourcePath = result.sourcePath,
            targetPath = result.targetPath,
            reason = if (result.success) null else result.message,
            detailJson = "{\"packageName\":\"" + packageName + "\"}"
        )
    }

    private fun ensureDirectory(parentPath: String, name: String): String {
        val candidate = File(parentPath, name).absolutePath
        try {
            return guard.guardTargetDirectory(candidate).canonicalPath
        } catch (_: FileGuardException) {
            // 不存在时继续创建；存在但不是目录会由 prepareCreate 报错。
        }

        val plan = guard.prepareCreate(parentPath, name)
        guard.verifyCreationTarget(plan)
        try {
            Files.createDirectory(plan.target.toPath())
        } catch (_: FileAlreadyExistsException) {
            // 竞态下已存在，下面重新校验。
        }
        return guard.guardTargetDirectory(plan.targetPath).canonicalPath
    }

    private fun createUniqueApkPlan(targetDirectory: String, baseName: String): CreationPlan {
        for (index in 0 until 100) {
            val name = if (index == 0) "$baseName.apk" else "$baseName ($index).apk"
            try {
                return guard.prepareCreate(targetDirectory, name)
            } catch (e: FileGuardException) {
                if (e.code != GuardErrorCode.ALREADY_EXISTS) throw e
            }
        }
        throw FileGuardException(
            GuardErrorCode.ALREADY_EXISTS,
            targetDirectory,
            "同名 APK 过多"
        )
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .trim('.')
        return cleaned.take(80)
    }
}
