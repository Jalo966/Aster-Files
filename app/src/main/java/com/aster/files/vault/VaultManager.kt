package com.aster.files.vault

import com.aster.files.data.FileEntry
import com.aster.files.services.FileClassifier
import com.aster.files.security.FileGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 应用私有保险箱：物理路径固定在 filesDir/vault，绝不使用 .trash。 */
class VaultManager(
    filesDir: File,
    private val guard: FileGuard,
    private val crypto: VaultCrypto = VaultCrypto(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    val root: File = File(filesDir, "vault").apply { mkdirs() }

    suspend fun list(): List<FileEntry> = withContext(ioDispatcher) {
        root.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.map { file ->
            val extension = file.name.substringAfterLast('.', "")
            FileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = false,
                isSymlink = false,
                sizeBytes = file.length(),
                modifiedAt = file.lastModified(),
                extension = extension,
                kind = FileClassifier.kindOf(file.name, false)
            )
        }.orEmpty()
    }

    suspend fun moveIn(sourcePath: String): String = withContext(ioDispatcher) {
        val source = guard.guardForWrite(sourcePath).file
        require(source.isFile) { "保险箱仅支持文件，不支持文件夹" }
        val target = uniqueTarget(source.name)
        crypto.encrypt(source, target)
        if (!source.delete()) {
            target.delete()
            error("无法从原位置删除文件")
        }
        target.name
    }

    suspend fun moveOut(name: String, targetDirectory: String): File = withContext(ioDispatcher) {
        val source = File(root, name)
        require(source.isFile) { "保险箱文件不存在" }
        val directory = guard.guardTargetDirectory(targetDirectory).file
        var index = 0
        var plan = guard.prepareCreate(directory.path, name)
        while (plan.target.exists() && index < 50) {
            index++
            plan = guard.prepareCreate(directory.path, addSuffix(name, index))
        }
        guard.verifyCreationTarget(plan)
        crypto.decrypt(source, plan.target)
        if (!source.delete()) {
            plan.target.delete()
            error("无法从保险箱移除文件")
        }
        plan.target
    }

    suspend fun readText(name: String): String = withContext(ioDispatcher) {
        crypto.decryptText(File(root, name))
    }

    suspend fun decryptToCache(name: String, cacheDir: File): File = withContext(ioDispatcher) {
        val source = File(root, name)
        val directory = File(cacheDir, "vault_preview").apply { mkdirs() }
        val target = File(directory, "preview_" + System.currentTimeMillis() + "_" + name)
        crypto.decrypt(source, target)
        target
    }

    fun clearPreviewCache(cacheDir: File) {
        File(cacheDir, "vault_preview").listFiles()?.forEach { it.delete() }
    }

    fun isVaultPath(path: String): Boolean {
        return runCatching {
            File(path).canonicalPath.startsWith(root.canonicalPath + File.separator)
        }.getOrDefault(false)
    }

    private fun uniqueTarget(name: String): File {
        var target = File(root, name)
        var index = 0
        while (target.exists() && index < 50) {
            index++
            target = File(root, addSuffix(name, index))
        }
        return target
    }

    private fun addSuffix(name: String, index: Int): String {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        return base + " (" + index + ")" + if (extension.isEmpty()) "" else "." + extension
    }
}
