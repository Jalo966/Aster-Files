package com.aster.files.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 只负责崩溃堆栈的落盘与轮转：
 * - 每个文件最大 2MB；
 * - 文件名为 crash_yyyyMMdd_n.log；
 * - 最多保留 5 个文件，超出删除最旧。
 */
class CrashLogStore(
    private val directory: File,
    private val deviceInfo: String = "",
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Synchronized
    fun writeCrash(threadName: String, throwable: Throwable): File? {
        return runCatching {
            directory.mkdirs()
            val now = clock()
            val body = buildString {
                appendLine("=== Aster Files crash ===")
                appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(now)))
                appendLine("thread=" + threadName)
                if (deviceInfo.isNotBlank()) appendLine("device=" + deviceInfo)
                appendLine()
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                append(writer.toString())
                appendLine()
            }.toByteArray(Charsets.UTF_8)

            val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now))
            val files = filesForDay(day).sortedBy { indexOf(it, day) }
            val latest = files.lastOrNull()
            val latestIndex = latest?.let { indexOf(it, day) } ?: 0
            val appendLatest = latest != null &&
                latest.length() + body.size <= maxFileBytes &&
                latest.length() < maxFileBytes

            val target = if (appendLatest) {
                latest!!
            } else {
                File(directory, "crash_" + day + "_" + (latestIndex + 1) + ".log")
            }
            FileOutputStream(target, appendLatest).use { output ->
                if (appendLatest && target.length() > 0) {
                    output.write('\n'.code)
                }
                val remaining = (maxFileBytes - target.length()).coerceAtLeast(0L)
                if (remaining > 0) {
                    output.write(body, 0, minOf(body.size.toLong(), remaining).toInt())
                }
            }
            trimOldFiles()
            target
        }.getOrNull()
    }

    fun listLogs(): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun exportZip(target: File): File? {
        val logs = listLogs()
        if (logs.isEmpty()) return null
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            logs.forEach { log ->
                zip.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    private fun filesForDay(day: String): List<File> {
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith("crash_" + day + "_") && file.name.endsWith(".log")
        }?.toList().orEmpty()
    }

    private fun indexOf(file: File, day: String): Int {
        return file.name.removePrefix("crash_" + day + "_").removeSuffix(".log").toIntOrNull() ?: 0
    }

    private fun trimOldFiles() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (files.size > maxFiles) {
            files.take(files.size - maxFiles).forEach { it.delete() }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MAX_FILES = 5
    }
}
