package com.aster.files.services

import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.aster.files.security.GuardErrorCode
import com.aster.files.security.GuardedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel as Zip4jCompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod as Zip4jCompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/**
 * ZIP 压缩与解压服务。
 *
 * 创建：流式写入目标目录临时文件，成功后 rename 到最终名称，失败清理临时文件。
 * 解压：先由 ZipSecurityInspector 检查路径、符号链接、加密、条目数与总大小，
 * 再逐项经过 FileGuard 写入；失败时只清理本次新建的文件/目录。
 */
class ArchiveService(
    private val guard: FileGuard,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalUncompressedBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxSourceItems: Int = FileService.MAX_BATCH_ITEMS,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val passwordStore: ZipPasswordStore? = null
) {

    init {
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
        require(maxTotalUncompressedBytes > 0) { "maxTotalUncompressedBytes 必须大于 0" }
        require(maxSourceItems > 0) { "maxSourceItems 必须大于 0" }
    }

    // ---------------------------------------------------------------------
    // ZIP 创建
    // ---------------------------------------------------------------------

    suspend fun zip(
        sources: List<String>,
        targetDirectory: String,
        zipName: String
    ): OperationResult = withContext(ioDispatcher) {
        val result = zipInternal(sources, targetDirectory, zipName)
        auditArchive("ZIP", result)
        result
    }

    // ---------------------------------------------------------------------
    // ZIP 解压
    // ---------------------------------------------------------------------

    suspend fun unzip(
        zipPath: String,
        targetDirectory: String
    ): OperationResult = withContext(ioDispatcher) {
        val result = unzipInternal(zipPath, targetDirectory)
        auditArchive("UNZIP", result)
        result
    }


    /**
     * zip4j 高级压缩：压缩级别、密码/加密、分卷、压缩后删除源文件。
     * 所有源路径仍先经过 FileGuard 与条目安全检查；密码不会写入审计。
     */
    suspend fun zipAdvanced(
        sources: List<String>,
        targetDirectory: String,
        zipName: String,
        options: ZipOptions,
        onProgress: ((Float) -> Unit)? = null
    ): OperationResult = withContext(ioDispatcher) {
        if (!options.format.supported) {
            val result = OperationResult.failed(
                options.format.label + " 创建暂未支持，当前仅支持 ZIP",
                "ZIP_FORMAT_UNSUPPORTED"
            )
            auditArchive("ZIP_ADVANCED", result)
            return@withContext result
        }
        if (!options.algorithm.supported) {
            val result = OperationResult.failed(
                options.algorithm.label + " 算法暂未支持，当前仅支持 Deflate",
                "ZIP_ALGORITHM_UNSUPPORTED"
            )
            auditArchive("ZIP_ADVANCED", result)
            return@withContext result
        }
        val result = zipAdvancedInternal(
            sources = sources,
            targetDirectory = targetDirectory,
            zipName = zipName,
            options = options,
            onProgress = onProgress
        )
        auditArchive("ZIP_ADVANCED", result)
        onProgress?.invoke(1f)
        result
    }

    /**
     * zip4j 高级解压：自动识别加密/分卷，支持密码与解压后删除源 ZIP。
     */
    suspend fun unzipAdvanced(
        zipPath: String,
        targetDirectory: String,
        password: String? = null,
        deleteSource: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): OperationResult = withContext(ioDispatcher) {
        val result = unzipAdvancedInternal(
            zipPath = zipPath,
            targetDirectory = targetDirectory,
            password = password,
            deleteSource = deleteSource,
            onProgress = onProgress
        )
        auditArchive("UNZIP_ADVANCED", result)
        onProgress?.invoke(1f)
        result
    }

    /** 读取 ZIP 基本信息，用于 UI 判断是否显示密码输入与自动回填。 */
    suspend fun inspectZip(zipPath: String): ZipArchiveInfo = withContext(ioDispatcher) {
        val archive = guard.guardReadableFile(zipPath)
        inspectZip4j(archive.file)
    }

    private suspend fun zipAdvancedInternal(
        sources: List<String>,
        targetDirectory: String,
        zipName: String,
        options: ZipOptions,
        onProgress: ((Float) -> Unit)?
    ): OperationResult {
        if (sources.isEmpty()) {
            return OperationResult.failed("没有可压缩的文件", "ZIP_EMPTY_SOURCE")
        }
        val uniqueSources = sources.distinct()
        if (uniqueSources.size > maxSourceItems) {
            return OperationResult.failed("单次最多压缩 $maxSourceItems 项", "ZIP_TOO_MANY_SOURCES")
        }

        var tempPath: String? = null
        var tempCreated = false
        var finalPath: String? = null
        return try {
            val targetDir = guard.guardTargetDirectory(targetDirectory)
            if (targetDir.isSymlink) {
                throw FileGuardException(
                    GuardErrorCode.SYMLINK_FORBIDDEN,
                    targetDir.canonicalPath,
                    "ZIP 目标目录不允许是符号链接"
                )
            }
            val finalPlan = guard.prepareCreate(targetDir.canonicalPath, normalizeZipName(zipName))
            finalPath = finalPlan.targetPath
            guard.verifyCreationTarget(finalPlan)

            // 复用既有安全检查：拒绝符号链接、重名条目、超量/超大条目。
            val specs = ArrayList<ArchiveSpec>()
            val names = HashSet<String>()
            val topLevel = ArrayList<GuardedPath>()
            for (sourcePath in uniqueSources) {
                val source = guard.guardForWrite(sourcePath)
                topLevel.add(source)
                collectSpecs(guard, source, source.file.name, specs, names)
            }
            if (specs.isEmpty()) {
                throw ZipSecurityException(ZipSecurityInspector.CODE_INVALID, "没有可压缩的条目")
            }

            val split = options.splitSizeBytes > 0L
            val archivePath: String
            if (split) {
                archivePath = finalPlan.targetPath
                guard.verifyCreationTarget(finalPlan)
            } else {
                val tempPlan = guard.prepareCreate(
                    targetDir.canonicalPath,
                    ".aster-zip4j-" + idGenerator() + ".tmp"
                )
                tempPath = tempPlan.targetPath
                guard.verifyCreationTarget(tempPlan)
                archivePath = tempPlan.targetPath
                tempCreated = true
            }

            onProgress?.invoke(0.02f)
            val password = options.password?.takeIf { it.isNotBlank() }
            writeAdvancedArchive(archivePath, specs, options, split, onProgress)

            if (!split) {
                val movingFrom = File(archivePath).toPath()
                guard.verifyCreationTarget(finalPlan)
                try {
                    Files.move(movingFrom, finalPlan.target.toPath())
                } catch (e: FileAlreadyExistsException) {
                    throw FileGuardException(GuardErrorCode.ALREADY_EXISTS, finalPlan.targetPath)
                }
                tempCreated = false
                FileTransferSupport.verifyCreated(guard, finalPlan)
            } else {
                FileTransferSupport.verifyCreated(guard, finalPlan)
            }

            if (password != null) {
                try {
                    passwordStore?.save(finalPlan.targetPath, password)
                } catch (_: Exception) {
                    // 密码保存失败不推翻已成功的 ZIP；用户仍可手动输入。
                }
            }

            var warning: String? = null
            if (options.deleteSources) {
                val failures = ArrayList<String>()
                for (source in topLevel) {
                    if (isInsideSourceDirectory(source, finalPlan.targetPath)) {
                        failures.add(source.file.name)
                        continue
                    }
                    if (!deleteGuardedPath(source, "ZIP_DELETE_SOURCE", finalPlan.targetPath)) {
                        failures.add(source.file.name)
                    }
                }
                if (failures.isNotEmpty()) {
                    warning = "部分源文件未能删除：" + failures.joinToString("、")
                }
            }

            OperationResult.ok(
                message = buildString {
                    append(if (split) "分卷 ZIP 已创建" else "ZIP 已创建")
                    if (warning != null) append("；").append(warning)
                },
                sourcePath = if (uniqueSources.size == 1) uniqueSources.first() else null,
                targetPath = finalPlan.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (tempCreated && tempPath != null) cleanupTemporaryFile(guard, tempPath)
            finalPath?.let { cleanupSplitArchiveParts(it) }
            failure(e, targetDirectory, finalPath)
        }
    }

    private suspend fun unzipAdvancedInternal(
        zipPath: String,
        targetDirectory: String,
        password: String?,
        deleteSource: Boolean,
        onProgress: ((Float) -> Unit)?
    ): OperationResult {
        val createdFiles = ArrayList<File>()
        val createdDirectories = ArrayList<File>()
        return try {
            onProgress?.invoke(0.02f)
            val zipGuard = guard.guardReadableFile(zipPath)
            val targetGuard = guard.guardTargetDirectory(targetDirectory)
            if (targetGuard.isSymlink) {
                throw ZipSecurityException(
                    ZipSecurityInspector.CODE_SYMLINK,
                    "解压目标目录不允许是符号链接"
                )
            }
            val info = inspectZip4j(zipGuard.file)
            if (info.encrypted && password.isNullOrBlank()) {
                throw ZipSecurityException("ZIP_PASSWORD_REQUIRED", "该 ZIP 需要密码")
            }

            val zipFile = Zip4jFile(zipGuard.file)
            val suppliedPassword = password?.takeIf { it.isNotBlank() }
            suppliedPassword?.let { zipFile.setPassword(it.toCharArray()) }
            val headers = zipFile.fileHeaders
            val targetRoot = targetGuard.file
            val totalBytes = headers.sumOf { it.uncompressedSize.coerceAtLeast(0L) }
            var writtenBytes = 0L
            var completedEntries = 0
            val names = HashSet<String>()

            fun reportProgress() {
                val fraction = if (totalBytes > 0) {
                    writtenBytes.toDouble() / totalBytes.toDouble()
                } else {
                    completedEntries.toDouble() / headers.size.coerceAtLeast(1).toDouble()
                }
                onProgress?.invoke((0.05 + 0.90 * fraction).toFloat().coerceIn(0.05f, 0.95f))
            }

            for (header in headers) {
                ZipSecurityInspector.validateEntryName(header.fileName)
                if (!names.add(header.fileName.trimEnd('/'))) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_DUPLICATE_ENTRY,
                        "ZIP 包含重名条目：" + header.fileName
                    )
                }
                val segments = segmentsOf(header.fileName)
                if (header.isDirectory) {
                    ensureDirectories(targetRoot, segments, createdDirectories)
                    completedEntries++
                    reportProgress()
                    continue
                }
                if (segments.isEmpty()) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_INVALID,
                        "ZIP 文件条目路径为空"
                    )
                }
                val fileName = segments.last()
                val parent = ensureDirectories(
                    targetRoot,
                    segments.dropLast(1),
                    createdDirectories
                )
                val plan = guard.prepareCreate(parent.absolutePath, fileName)
                guard.verifyCreationTarget(plan)
                zipFile.extractFile(header, parent.absolutePath, fileName)
                createdFiles.add(plan.target)
                writtenBytes += header.uncompressedSize.coerceAtLeast(0L)
                completedEntries++
                reportProgress()
            }

            if (info.encrypted && suppliedPassword != null) {
                try {
                    passwordStore?.save(zipGuard.canonicalPath, suppliedPassword)
                } catch (_: Exception) {
                    // 自动保存密码失败不影响解压。
                }
            }

            var warning: String? = null
            if (deleteSource) {
                val deleted = deleteGuardedPath(zipGuard, "UNZIP_DELETE_SOURCE", targetGuard.canonicalPath)
                if (!deleted) {
                    warning = "解压成功，但原 ZIP 删除失败"
                } else if (info.split) {
                    deleteSplitPartsGuarded(zipGuard.canonicalPath, targetGuard.canonicalPath)
                }
            }

            OperationResult.ok(
                message = if (warning == null) "解压完成" else "解压完成；" + warning,
                sourcePath = zipGuard.canonicalPath,
                targetPath = targetGuard.canonicalPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupCreated(guard, createdFiles, createdDirectories)
            if (e is Zip4jException && e.message?.contains("password", ignoreCase = true) == true) {
                OperationResult.failed(
                    e.message ?: "ZIP 密码错误",
                    "ZIP_PASSWORD_INVALID",
                    sourcePath = zipPath,
                    targetPath = targetDirectory
                )
            } else {
                failure(e, zipPath, targetDirectory)
            }
        }
    }

    private fun writeAdvancedArchive(
        archivePath: String,
        specs: List<ArchiveSpec>,
        options: ZipOptions,
        split: Boolean,
        onProgress: ((Float) -> Unit)?
    ) {
        val totalBytes = specs.sumOf { it.source?.sizeBytes ?: 0L }
        var copiedBytes = 0L
        var completedEntries = 0
        fun reportProgress() {
            val fraction = if (totalBytes > 0) {
                copiedBytes.toDouble() / totalBytes.toDouble()
            } else {
                completedEntries.toDouble() / specs.size.coerceAtLeast(1).toDouble()
            }
            onProgress?.invoke((0.05 + 0.90 * fraction).toFloat().coerceIn(0.05f, 0.95f))
        }
        val rawOutput = if (split) {
            SplitOutputStream(File(archivePath), options.splitSizeBytes)
        } else {
            Files.newOutputStream(
                File(archivePath).toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        }
        val password = options.password?.takeIf { it.isNotBlank() }?.toCharArray()
        rawOutput.use { output ->
            val zipOutput = if (password != null) {
                Zip4jOutputStream(output, password)
            } else {
                Zip4jOutputStream(output)
            }
            zipOutput.use { zip ->
                for (spec in specs) {
                    val parameters = zipParameters(options)
                    parameters.fileNameInZip = spec.entryName
                    parameters.entrySize = spec.source?.sizeBytes ?: 0L
                    parameters.lastModifiedFileTime =
                        spec.source?.modifiedAt ?: System.currentTimeMillis()
                    zip.putNextEntry(parameters)
                    if (!spec.isDirectory) {
                        val source = spec.source
                            ?: throw ZipSecurityException(
                                ZipSecurityInspector.CODE_INVALID,
                                "ZIP 条目缺少源文件"
                            )
                        guard.verifyUnchanged(source)
                        Files.newInputStream(source.file.toPath()).use { input ->
                            copyStreaming(input, zip) {
                                copiedBytes += it.toLong()
                                reportProgress()
                            }
                        }
                        guard.verifyUnchanged(source)
                    }
                    zip.closeEntry()
                    completedEntries++
                    reportProgress()
                }
            }
        }
    }

    private fun zipParameters(options: ZipOptions): ZipParameters {
        val password = options.password?.takeIf { it.isNotBlank() }
        val parameters = ZipParameters()
        parameters.compressionMethod = if (options.compressionLevel == 0) {
            Zip4jCompressionMethod.STORE
        } else {
            Zip4jCompressionMethod.DEFLATE
        }
        parameters.compressionLevel = when (options.compressionLevel) {
            0 -> Zip4jCompressionLevel.NO_COMPRESSION
            1 -> Zip4jCompressionLevel.FASTEST
            5 -> Zip4jCompressionLevel.NORMAL
            else -> Zip4jCompressionLevel.ULTRA
        }
        if (password != null) {
            parameters.isEncryptFiles = true
            when (options.encryption) {
                ZipEncryptionMode.ZIP_STANDARD -> {
                    parameters.encryptionMethod = EncryptionMethod.ZIP_STANDARD
                }
                ZipEncryptionMode.AES_128 -> {
                    parameters.encryptionMethod = EncryptionMethod.AES
                    parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_128
                }
                ZipEncryptionMode.AES_256 -> {
                    parameters.encryptionMethod = EncryptionMethod.AES
                    parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
        }
        return parameters
    }

    private fun inspectZip4j(file: File): ZipArchiveInfo {
        val zipFile = Zip4jFile(file)
        val headers = zipFile.fileHeaders
        var total = 0L
        if (headers.size > maxEntries) {
            throw ZipSecurityException(
                ZipSecurityInspector.CODE_TOO_MANY_ENTRIES,
                "ZIP 条目数超过上限：$maxEntries"
            )
        }
        for (header in headers) {
            ZipSecurityInspector.validateEntryName(header.fileName)
            if (header.uncompressedSize > 0) {
                total += header.uncompressedSize
                if (total > maxTotalUncompressedBytes) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_TOO_LARGE,
                        "ZIP 总解压大小超过上限"
                    )
                }
            }
        }
        return ZipArchiveInfo(
            encrypted = zipFile.isEncrypted,
            entryCount = headers.size,
            split = zipFile.isSplitArchive
        )
    }

    private fun normalizeZipName(zipName: String): String {
        val trimmed = zipName.trim()
        if (trimmed.isEmpty()) throw ZipSecurityException(ZipSecurityInspector.CODE_INVALID, "ZIP 名称不能为空")
        return if (trimmed.endsWith(".zip", ignoreCase = true)) trimmed else "$trimmed.zip"
    }

    private fun isInsideSourceDirectory(source: GuardedPath, archivePath: String): Boolean {
        if (!source.isDirectory) return false
        return try {
            File(archivePath).canonicalPath.startsWith(source.file.canonicalPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    /** 只删除 FileGuard 允许的路径，并为每个源文件写独立审计。 */
    private suspend fun deleteGuardedPath(
        guarded: GuardedPath,
        operation: String,
        targetPath: String?
    ): Boolean {
        return try {
            guard.verifyUnchanged(guarded)
            val removed = guarded.file.deleteRecursively()
            audit.record(
                category = AuditCategory.ARCHIVE,
                operation = operation,
                status = if (removed) AuditStatus.SUCCESS else AuditStatus.FAILED,
                sourcePath = guarded.canonicalPath,
                targetPath = targetPath,
                reason = if (removed) null else "删除失败"
            )
            removed
        } catch (e: Exception) {
            runCatching {
                audit.record(
                    category = AuditCategory.ARCHIVE,
                    operation = operation,
                    status = AuditStatus.FAILED,
                    sourcePath = guarded.canonicalPath,
                    targetPath = targetPath,
                    reason = e.message
                )
            }
            false
        }
    }

    private suspend fun deleteSplitPartsGuarded(archivePath: String, targetPath: String) {
        val archive = File(archivePath)
        val directory = archive.parentFile ?: return
        val stem = archive.name.substringBeforeLast('.', archive.name)
        val partPattern = Regex("^" + Regex.escape(stem) + "\\.z\\d+$")
        val parts = directory.listFiles()
            ?.filter { it.isFile && it.name.matches(partPattern) }
            ?.sortedBy { it.name }
            ?: return
        for (part in parts) {
            try {
                val guarded = guard.guardForWrite(part.absolutePath)
                deleteGuardedPath(guarded, "UNZIP_DELETE_SOURCE", targetPath)
            } catch (_: Exception) {
                // 分卷分片删除失败时，主 ZIP 已删除；保留分片供用户手动处理。
            }
        }
    }

    private fun cleanupSplitArchiveParts(archivePath: String) {
        val file = File(archivePath)
        val directory = file.parentFile ?: return
        val stem = file.name.substringBeforeLast('.', file.name)
        runCatching {
            directory.listFiles()?.forEach { candidate ->
                val name = candidate.name
                if (name == file.name ||
                    (name.startsWith(stem + ".") && name.matches(Regex(stem.replace(".", "\\.") + "\\.z\\d+")))
                ) {
                    candidate.delete()
                }
            }
        }
    }

    /**
     * 只读取 ZIP/APK 的中央目录，不创建任何文件；APK 可直接复用。
     */
    suspend fun listEntries(archivePath: String): ZipInspection = withContext(ioDispatcher) {
        val archive = guard.guardReadableFile(archivePath)
        ZipSecurityInspector.inspect(
            file = archive.file,
            maxEntries = maxEntries,
            maxTotalBytes = maxTotalUncompressedBytes
        )
    }

    /**
     * 读取单个 ZIP 条目内容，用于 APK 内部图片预览。
     *
     * 条目名必须通过 ZipSecurityInspector；限制单条目最大字节数，避免把
     * APK 内的巨大资源直接读入内存。
     */
    suspend fun readEntryBytes(
        archivePath: String,
        entryName: String,
        maxBytes: Int = DEFAULT_MAX_ENTRY_PREVIEW_BYTES
    ): ByteArray = withContext(ioDispatcher) {
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        ZipSecurityInspector.validateEntryName(entryName)
        val archive = guard.guardReadableFile(archivePath)
        ZipFile(archive.file).use { zipFile ->
            val entry = zipFile.getEntry(entryName)
                ?: throw ZipSecurityException(
                    ZipSecurityInspector.CODE_INVALID,
                    "ZIP 条目不存在：" + entryName
                )
            if (entry.isDirectory) {
                throw ZipSecurityException(ZipSecurityInspector.CODE_INVALID, "不能预览目录条目")
            }
            if (entry.size > maxBytes.toLong()) {
                throw ZipSecurityException(
                    ZipSecurityInspector.CODE_TOO_LARGE,
                    "条目过大，无法预览（上限 " + maxBytes + " 字节）"
                )
            }
            val output = java.io.ByteArrayOutputStream()
            zipFile.getInputStream(entry).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw ZipSecurityException(
                            ZipSecurityInspector.CODE_TOO_LARGE,
                            "条目内容超过预览上限"
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    // ---------------------------------------------------------------------
    // ZIP 创建实现
    // ---------------------------------------------------------------------

    private fun zipInternal(
        sources: List<String>,
        targetDirectory: String,
        zipName: String
    ): OperationResult {
        var tempPath: String? = null
        var tempCreated = false
        var finalPath: String? = null

        if (sources.isEmpty()) {
            return OperationResult.failed("没有可压缩的文件", "ZIP_EMPTY_SOURCE")
        }
        val uniqueSources = sources.distinct()
        if (uniqueSources.size > maxSourceItems) {
            return OperationResult.failed(
                "单次最多压缩 $maxSourceItems 项",
                "ZIP_TOO_MANY_SOURCES"
            )
        }

        return try {
            val targetDir = guard.guardTargetDirectory(targetDirectory)
            if (targetDir.isSymlink) {
                throw FileGuardException(
                    GuardErrorCode.SYMLINK_FORBIDDEN,
                    targetDir.canonicalPath,
                    "ZIP 目标目录不允许是符号链接"
                )
            }

            val finalPlan = guard.prepareCreate(targetDir.canonicalPath, zipName)
            finalPath = finalPlan.targetPath
            guard.verifyCreationTarget(finalPlan)

            val specs = ArrayList<ArchiveSpec>()
            val names = HashSet<String>()
            for (sourcePath in uniqueSources) {
                val source = guard.guardForWrite(sourcePath)
                collectSpecs(guard, source, source.file.name, specs, names)
            }
            if (specs.isEmpty()) {
                throw ZipSecurityException(ZipSecurityInspector.CODE_INVALID, "没有可压缩的条目")
            }

            val tempName = ".aster-zip-" + idGenerator() + ".tmp"
            val tempPlan = guard.prepareCreate(targetDir.canonicalPath, tempName)
            tempPath = tempPlan.targetPath
            guard.verifyCreationTarget(tempPlan)

            val tempFile = tempPlan.target.toPath()
            val rawOutput = Files.newOutputStream(
                tempFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            tempCreated = true
            rawOutput.use { output ->
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    for (spec in specs) {
                        if (spec.isDirectory) {
                            zip.putNextEntry(ZipEntry(spec.entryName))
                            zip.closeEntry()
                        } else {
                            val source = spec.source
                                ?: throw ZipSecurityException(
                                    ZipSecurityInspector.CODE_INVALID,
                                    "ZIP 条目缺少源文件"
                                )
                            guard.verifyUnchanged(source)
                            ZipSecurityInspector.validateEntryName(spec.entryName)
                            val entry = ZipEntry(spec.entryName)
                            entry.time = source.modifiedAt
                            zip.putNextEntry(entry)
                            BufferedInputStream(
                                Files.newInputStream(source.file.toPath())
                            ).use { input ->
                                copyStreaming(input, zip)
                            }
                            zip.closeEntry()
                            guard.verifyUnchanged(source)
                        }
                    }
                }
            }

            guard.verifyCreationTarget(finalPlan)
            try {
                Files.move(tempFile, finalPlan.target.toPath())
            } catch (e: FileAlreadyExistsException) {
                throw FileGuardException(GuardErrorCode.ALREADY_EXISTS, finalPlan.targetPath)
            }
            tempCreated = false
            FileTransferSupport.verifyCreated(guard, finalPlan)

            OperationResult.ok(
                message = "ZIP 已创建",
                sourcePath = if (uniqueSources.size == 1) uniqueSources.first() else null,
                targetPath = finalPlan.targetPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (tempCreated && tempPath != null) {
                cleanupTemporaryFile(guard, tempPath)
            }
            failure(e, targetDirectory, finalPath)
        }
    }

    private fun collectSpecs(
        guard: FileGuard,
        source: GuardedPath,
        entryName: String,
        specs: MutableList<ArchiveSpec>,
        names: MutableSet<String>
    ) {
        ZipSecurityInspector.validateEntryName(entryName)
        if (source.isDirectory) {
            val directoryName = entryName.trimEnd('/') + "/"
            addSpec(
                specs,
                names,
                ArchiveSpec(source = null, entryName = directoryName, isDirectory = true)
            )
            val children = source.file.listFiles()?.sortedBy { it.name }
                ?: throw FileGuardException(
                    GuardErrorCode.IO_ERROR,
                    source.canonicalPath,
                    "无法读取目录内容"
                )
            for (child in children) {
                val childSource = guard.guardForRead(child.absolutePath)
                if (childSource.isSymlink) {
                    throw FileGuardException(
                        GuardErrorCode.SYMLINK_FORBIDDEN,
                        child.absolutePath,
                        "目录内包含符号链接，禁止压缩"
                    )
                }
                collectSpecs(
                    guard,
                    childSource,
                    directoryName + child.name,
                    specs,
                    names
                )
            }
        } else {
            addSpec(
                specs,
                names,
                ArchiveSpec(source = source, entryName = entryName, isDirectory = false)
            )
        }
    }

    private fun addSpec(
        specs: MutableList<ArchiveSpec>,
        names: MutableSet<String>,
        spec: ArchiveSpec
    ) {
        ZipSecurityInspector.validateEntryName(spec.entryName)
        // 文件和同名目录在文件系统上冲突，ZIP 中也应拒绝。
        val key = spec.entryName.trimEnd('/')
        if (!names.add(key)) {
            throw ZipSecurityException(
                ZipSecurityInspector.CODE_DUPLICATE_ENTRY,
                "ZIP 内条目重名：${spec.entryName}"
            )
        }
        specs.add(spec)
        if (specs.size > maxEntries) {
            throw ZipSecurityException(
                ZipSecurityInspector.CODE_TOO_MANY_ENTRIES,
                "ZIP 条目数超过上限：$maxEntries"
            )
        }
    }

    // ---------------------------------------------------------------------
    // ZIP 解压实现
    // ---------------------------------------------------------------------

    private fun unzipInternal(
        zipPath: String,
        targetDirectory: String
    ): OperationResult {
        val createdFiles = ArrayList<File>()
        val createdDirectories = ArrayList<File>()
        var targetCanonicalPath: String? = null

        return try {
            val zipGuard = guard.guardReadableFile(zipPath)
            val targetGuard = guard.guardTargetDirectory(targetDirectory)
            if (targetGuard.isSymlink) {
                throw ZipSecurityException(
                    ZipSecurityInspector.CODE_SYMLINK,
                    "解压目标目录不允许是符号链接"
                )
            }
            val targetRoot = targetGuard.file
            targetCanonicalPath = targetGuard.canonicalPath

            val inspection = ZipSecurityInspector.inspect(
                file = zipGuard.file,
                maxEntries = maxEntries,
                maxTotalBytes = maxTotalUncompressedBytes
            )

            ZipFile(zipGuard.file).use { zipFile ->
                if (zipFile.size() != inspection.entries.size) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_INVALID,
                        "ZIP 中央目录条目数不一致"
                    )
                }

                var totalWritten = 0L
                for (info in inspection.entries) {
                    val entry = zipFile.getEntry(info.name)
                        ?: throw ZipSecurityException(
                            ZipSecurityInspector.CODE_INVALID,
                            "ZIP 条目缺失：${info.name}"
                        )
                    ZipSecurityInspector.validateEntryName(entry.name)
                    if (entry.name != info.name ||
                        entry.isDirectory != info.isDirectory ||
                        entry.method != info.method ||
                        (entry.size >= 0 && entry.size != info.uncompressedSize)
                    ) {
                        throw ZipSecurityException(
                            ZipSecurityInspector.CODE_INVALID,
                            "ZIP 条目与中央目录不一致：${info.name}"
                        )
                    }

                    val segments = segmentsOf(info.name)
                    if (info.isDirectory) {
                        ensureDirectories(targetRoot, segments, createdDirectories)
                        continue
                    }
                    if (segments.isEmpty()) {
                        throw ZipSecurityException(
                            ZipSecurityInspector.CODE_INVALID,
                            "ZIP 文件条目路径为空"
                        )
                    }

                    val fileName = segments.last()
                    val parent = ensureDirectories(
                        targetRoot,
                        segments.dropLast(1),
                        createdDirectories
                    )
                    val plan = guard.prepareCreate(parent.absolutePath, fileName)
                    guard.verifyCreationTarget(plan)

                    zipFile.getInputStream(entry).use { input ->
                        Files.newOutputStream(
                            plan.target.toPath(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                        ).use { rawOutput ->
                            createdFiles.add(plan.target)
                            val output = BufferedOutputStream(rawOutput)
                            val crc = CRC32()
                            val buffer = ByteArray(BUFFER_SIZE)
                            var entryWritten = 0L

                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                entryWritten += read
                                totalWritten += read
                                if (entryWritten > info.uncompressedSize ||
                                    totalWritten > maxTotalUncompressedBytes
                                ) {
                                    throw ZipSecurityException(
                                        ZipSecurityInspector.CODE_TOO_LARGE,
                                        "ZIP 解压数据超过声明大小"
                                    )
                                }
                                crc.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                            output.flush()

                            if (entryWritten != info.uncompressedSize) {
                                throw ZipSecurityException(
                                    ZipSecurityInspector.CODE_INVALID,
                                    "ZIP 条目解压大小不一致：${info.name}"
                                )
                            }
                            if (entry.crc >= 0 && crc.value != entry.crc) {
                                throw ZipSecurityException(
                                    ZipSecurityInspector.CODE_INVALID,
                                    "ZIP 条目 CRC 校验失败：${info.name}"
                                )
                            }
                        }
                    }
                }
            }

            OperationResult.ok(
                message = "解压完成",
                sourcePath = zipGuard.canonicalPath,
                targetPath = targetCanonicalPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupCreated(guard, createdFiles, createdDirectories)
            failure(e, zipPath, targetCanonicalPath)
        }
    }

    private fun ensureDirectories(
        root: File,
        segments: List<String>,
        createdDirectories: MutableList<File>
    ): File {
        var current = root
        for (segment in segments) {
            val candidate = File(current, segment)
            val existing = try {
                guard.guardForRead(candidate.absolutePath)
            } catch (e: FileGuardException) {
                if (e.code == GuardErrorCode.SOURCE_NOT_FOUND) null else throw e
            }
            if (existing != null) {
                if (existing.isSymlink) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_SYMLINK,
                        "解压路径中存在符号链接：${candidate.absolutePath}"
                    )
                }
                if (!existing.isDirectory) {
                    throw ZipSecurityException(
                        ZipSecurityInspector.CODE_INVALID,
                        "解压路径与已有文件冲突：${candidate.absolutePath}"
                    )
                }
                current = existing.file
            } else {
                val plan = guard.prepareCreate(current.absolutePath, segment)
                guard.verifyCreationTarget(plan)
                Files.createDirectory(plan.target.toPath())
                createdDirectories.add(plan.target)
                current = plan.target
            }
        }
        return current
    }

    private fun segmentsOf(entryName: String): List<String> {
        val trimmed = if (entryName.endsWith("/")) entryName.dropLast(1) else entryName
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('/')
    }

    private fun cleanupCreated(
        guard: FileGuard,
        createdFiles: List<File>,
        createdDirectories: List<File>
    ) {
        for (file in createdFiles.asReversed()) {
            try {
                val guarded = guard.guardForWrite(file.absolutePath)
                Files.deleteIfExists(guarded.file.toPath())
            } catch (_: Exception) {
                // 只清理本次新建路径；失败不覆盖原始错误。
            }
        }
        for (directory in createdDirectories.asReversed()) {
            try {
                val guarded = guard.guardForWrite(directory.absolutePath)
                Files.deleteIfExists(guarded.file.toPath())
            } catch (_: Exception) {
                // 目录非空或被替换时保留。
            }
        }
    }

    private fun cleanupTemporaryFile(guard: FileGuard, path: String) {
        try {
            val guarded = guard.guardForWrite(path)
            Files.deleteIfExists(guarded.file.toPath())
        } catch (_: Exception) {
            // 临时文件清理失败不覆盖原始错误。
        }
    }

    private fun copyStreaming(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        onChunk: ((Int) -> Unit)? = null
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            onChunk?.invoke(read)
        }
    }

    private suspend fun auditArchive(operation: String, result: OperationResult) {
        audit.record(
            category = AuditCategory.ARCHIVE,
            operation = operation,
            status = if (result.success) AuditStatus.SUCCESS else AuditStatus.FAILED,
            sourcePath = result.sourcePath,
            targetPath = result.targetPath,
            reason = if (result.success) null else result.message
        )
    }

    private fun failure(
        error: Exception,
        sourcePath: String?,
        targetPath: String?
    ): OperationResult {
        val code = when (error) {
            is ZipSecurityException -> error.code
            is FileGuardException -> error.stableCode
            is FileAlreadyExistsException -> GuardErrorCode.ALREADY_EXISTS.code
            is ZipException -> ZipSecurityInspector.CODE_INVALID
            is Zip4jException -> if (
                error.message?.contains("password", ignoreCase = true) == true
            ) {
                "ZIP_PASSWORD_INVALID"
            } else {
                ZipSecurityInspector.CODE_INVALID
            }
            else -> "IO_ERROR"
        }
        return OperationResult.failed(
            message = error.message ?: "操作失败",
            errorCode = code,
            sourcePath = sourcePath,
            targetPath = targetPath
        )
    }

    private data class ArchiveSpec(
        val source: GuardedPath?,
        val entryName: String,
        val isDirectory: Boolean
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES = 10_000
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRY_PREVIEW_BYTES = 20 * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
