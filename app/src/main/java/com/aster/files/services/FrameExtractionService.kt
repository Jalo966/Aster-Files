package com.aster.files.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.image.ApngDetector
import com.aster.files.security.FileGuard
import com.aster.files.security.FileGuardException
import com.github.penfeizhou.animation.apng.decode.APNGDecoder
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.loader.FileLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

enum class AnimatedImageType {
    GIF,
    APNG
}

data class AnimatedImageInfo(
    val type: AnimatedImageType,
    val frameCount: Int,
    val width: Int,
    val height: Int
)

enum class FrameImageFormat(val extension: String) {
    PNG("png"),
    JPG("jpg")
}

data class FrameSaveResult(
    val success: Boolean,
    val message: String,
    val folderName: String? = null,
    val savedCount: Int = 0,
    val targetPath: String? = null
)

/**
 * GIF/APNG 动图抽帧。
 *
 * 总帧数先解析；缩略图/保存时一次只解码一帧并立即回收，绝不把全部全尺寸
 * Bitmap 留在内存。目标目录、帧文件写入全部经过 FileGuard，并写 FILE 审计。
 */
class FrameExtractionService(
    private val guard: FileGuard,
    private val audit: AuditTrail,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun inspect(path: String): AnimatedImageInfo? = withContext(ioDispatcher) {
        val file = guard.guardReadableFile(path).file
        inspectFile(file)
    }

    suspend fun loadFrame(
        path: String,
        type: AnimatedImageType,
        index: Int,
        maxDimension: Int
    ): Bitmap? = withContext(ioDispatcher) {
        if (maxDimension < 0 || index < 0) return@withContext null
        val file = guard.guardReadableFile(path).file
        when (type) {
            AnimatedImageType.GIF -> loadGifFrame(file, index, maxDimension)
            AnimatedImageType.APNG -> loadApngFrame(file, index, maxDimension)
        }
    }

    suspend fun saveFrames(
        sourcePath: String,
        type: AnimatedImageType,
        indices: Collection<Int>,
        format: FrameImageFormat
    ): FrameSaveResult = withContext(ioDispatcher) {
        val selected = indices.toSortedSet()
        if (selected.isEmpty()) {
            return@withContext FrameSaveResult(false, "请选择至少一帧")
        }

        val source = guard.guardReadableFile(sourcePath)
        val parent = source.file.parentFile
            ?: return@withContext FrameSaveResult(false, "无法解析动图所在目录")
        val baseName = source.file.nameWithoutExtension.ifBlank { "animation" }
        val folderName = (baseName + "_frames").take(120)
        var targetFolder: String? = null
        var saved = 0
        try {
            targetFolder = ensureDirectory(parent.absolutePath, folderName)
            val info = inspectFile(source.file)
                ?: throw IOException("无法解析动图帧")
            for (index in selected) {
                if (index !in 0 until info.frameCount) {
                    throw IOException("帧序号越界：" + index)
                }
                val bitmap = when (type) {
                    AnimatedImageType.GIF -> loadGifFrame(source.file, index, 0)
                    AnimatedImageType.APNG -> loadApngFrame(source.file, index, 0)
                } ?: throw IOException("无法解码第 " + (index + 1) + " 帧")
                try {
                    val frameName = String.format(
                        Locale.ROOT,
                        "frame_%03d.%s",
                        index + 1,
                        format.extension
                    )
                    writeFrame(targetFolder, frameName, bitmap, format)
                    saved++
                } finally {
                    bitmap.recycle()
                }
            }
            val result = FrameSaveResult(
                success = true,
                message = "已保存 " + saved + " 帧到 " + folderName,
                folderName = folderName,
                savedCount = saved,
                targetPath = targetFolder
            )
            audit.record(
                category = AuditCategory.FILE,
                operation = "EXTRACT_FRAMES",
                status = AuditStatus.SUCCESS,
                sourcePath = source.canonicalPath,
                targetPath = targetFolder,
                detailJson = "{\"format\":\"" + format.name + "\",\"count\":" + saved + "}"
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            audit.record(
                category = AuditCategory.FILE,
                operation = "EXTRACT_FRAMES",
                status = AuditStatus.FAILED,
                sourcePath = source.canonicalPath,
                targetPath = targetFolder,
                reason = e.message ?: "抽帧保存失败"
            )
            FrameSaveResult(false, e.message ?: "抽帧保存失败", folderName, saved, targetFolder)
        }
    }

    private fun inspectFile(file: File): AnimatedImageInfo? {
        if (!file.isFile) return null
        return if (file.extension.equals("gif", ignoreCase = true) || isGif(file)) {
            val gif = GifFrameParser.parse(file) ?: return null
            AnimatedImageInfo(AnimatedImageType.GIF, gif.frameCount, gif.width, gif.height)
        } else if (ApngDetector.isApng(file)) {
            val decoder = openApng(file) ?: return null
            try {
                val bounds = decoder.bounds
                AnimatedImageInfo(
                    type = AnimatedImageType.APNG,
                    frameCount = decoder.frameCount,
                    width = bounds.width(),
                    height = bounds.height()
                )
            } finally {
                decoder.stop()
            }
        } else {
            null
        }
    }

    private fun loadGifFrame(file: File, index: Int, maxDimension: Int): Bitmap? {
        val gif = GifFrameParser.parse(file) ?: return null
        if (index !in 0 until gif.frameCount) return null
        val movie = Movie.decodeFile(file.absolutePath) ?: return null
        val time = if (gif.delaysMs.size == gif.frameCount) {
            gif.delaysMs.take(index).sum()
        } else {
            (movie.duration().toLong() * index / gif.frameCount).toInt()
        }
        movie.setTime(time)
        val width = movie.width().coerceAtLeast(1)
        val height = movie.height().coerceAtLeast(1)
        val scale = if (maxDimension > 0) {
            minOf(1f, maxDimension.toFloat() / maxOf(width, height))
        } else {
            1f
        }
        val bitmap = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        return bitmap
    }

    private fun loadApngFrame(
        file: File,
        index: Int,
        maxDimension: Int
    ): Bitmap? {
        val decoder = openApng(file) ?: return null
        return try {
            if (maxDimension > 0) {
                decoder.setDesiredSize(maxDimension, maxDimension)
            }
            val frame = decoder.getFrameBitmap(index) ?: return null
            if (maxDimension > 0 &&
            maxOf(frame.width, frame.height) > maxDimension
        ) {
            val scale = maxDimension.toFloat() / maxOf(frame.width, frame.height)
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
            if (scaled !== frame) frame.recycle()
            scaled
            } else {
                frame
            }
        } finally {
            decoder.stop()
        }
    }

    private fun openApng(file: File): APNGDecoder? = try {
        APNGDecoder(
            FileLoader(file.absolutePath),
            object : FrameSeqDecoder.RenderListener {
                override fun onStart() = Unit
                override fun onRender(frame: ByteBuffer?) = Unit
                override fun onEnd() = Unit
            }
        )
    } catch (_: Exception) {
        null
    }

    private fun writeFrame(
        folder: String,
        name: String,
        bitmap: Bitmap,
        format: FrameImageFormat
    ) {
        val targetPath = File(folder, name).absolutePath
        val exists = File(targetPath).exists()
        val guarded = if (exists) {
            guard.guardForWrite(targetPath)
        } else {
            val plan = guard.prepareCreate(folder, name)
            guard.verifyCreationTarget(plan)
            null
        }
        val outFile = if (guarded != null) guarded.file else File(targetPath)
        val source = if (format == FrameImageFormat.JPG && bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { flat ->
                val canvas = Canvas(flat)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else {
            null
        }
        val outputBitmap = source ?: bitmap
        try {
            val bytes = ByteArrayOutputStream()
            val quality = if (format == FrameImageFormat.JPG) 92 else 100
            if (!outputBitmap.compress(
                    if (format == FrameImageFormat.JPG) Bitmap.CompressFormat.JPEG
                    else Bitmap.CompressFormat.PNG,
                    quality,
                    bytes
                )
            ) {
                throw IOException("帧压缩失败")
            }
            Files.write(
                outFile.toPath(),
                bytes.toByteArray(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        } finally {
            source?.recycle()
        }
    }

    private fun ensureDirectory(parentPath: String, name: String): String {
        val candidate = File(parentPath, name).absolutePath
        try {
            val guarded = guard.guardTargetDirectory(candidate)
            if (guarded.isSymlink) {
                throw FileGuardException(
                    com.aster.files.security.GuardErrorCode.SYMLINK_FORBIDDEN,
                    candidate
                )
            }
            return guarded.canonicalPath
        } catch (_: FileGuardException) {
            // 不存在则创建。
        }
        val plan = guard.prepareCreate(parentPath, name)
        guard.verifyCreationTarget(plan)
        try {
            Files.createDirectory(plan.target.toPath())
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // 竞态下已创建。
        }
        return guard.guardTargetDirectory(plan.targetPath).canonicalPath
    }

    private fun isGif(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(6)
            input.read(header) == 6 &&
                (String(header, 0, 3, Charsets.US_ASCII) == "GIF")
        }
    } catch (_: Exception) {
        false
    }
}

private data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val delaysMs: List<Int>
)

private object GifFrameParser {

    fun parse(file: File): GifInfo? {
        val bytes = file.readBytes()
        if (bytes.size < 13) return null
        if (String(bytes, 0, 3, Charsets.US_ASCII) != "GIF") return null
        val width = readLe16(bytes, 6)
        val height = readLe16(bytes, 8)
        val packed = bytes[10].toInt() and 0xFF
        var p = 13
        if (packed and 0x80 != 0) {
            p += 3 * (1 shl ((packed and 0x07) + 1))
        }
        val delays = ArrayList<Int>()
        var currentDelay = 10
        var frames = 0
        while (p < bytes.size) {
            when (bytes[p].toInt() and 0xFF) {
                0x3B -> break
                0x21 -> {
                    if (p + 1 >= bytes.size) break
                    val label = bytes[p + 1].toInt() and 0xFF
                    p += 2
                    if (label == 0xF9 && p < bytes.size) {
                        val size = bytes[p].toInt() and 0xFF
                        if (size >= 4 && p + 4 < bytes.size) {
                            currentDelay = (readLe16(bytes, p + 2) * 10).coerceAtLeast(10)
                        }
                        p += 1 + size
                        while (p < bytes.size && (bytes[p].toInt() and 0xFF) != 0) p += 1 + (bytes[p].toInt() and 0xFF)
                        if (p < bytes.size) p++
                    } else {
                        p = skipSubBlocks(bytes, p)
                    }
                }
                0x2C -> {
                    if (p + 10 >= bytes.size) break
                    frames++
                    delays.add(currentDelay)
                    currentDelay = 10
                    val localPacked = bytes[p + 9].toInt() and 0xFF
                    p += 10
                    if (localPacked and 0x80 != 0) {
                        p += 3 * (1 shl ((localPacked and 0x07) + 1))
                    }
                    if (p < bytes.size) p++ // LZW minimum code size
                    p = skipSubBlocks(bytes, p)
                }
                else -> p++
            }
        }
        if (frames == 0) return null
        return GifInfo(width, height, frames, delays)
    }

    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int {
        var p = start
        while (p < bytes.size) {
            val size = bytes[p].toInt() and 0xFF
            p++
            if (size == 0) return p
            p += size
        }
        return p
    }

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
