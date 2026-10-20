package com.aster.files.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.aster.files.data.AuditCategory
import com.aster.files.data.AuditStatus
import com.aster.files.security.FileGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

enum class ImageOutputFormat(val extension: String) {
    JPG("jpg"),
    PNG("png"),
    WEBP("webp")
}

data class ConversionResult(
    val success: Boolean,
    val message: String,
    val outputPath: String? = null
)

/** 原生图片格式转换与视频音频提取；不引入 FFmpeg/额外原生库。 */
class MediaConversionService(
    private val guard: FileGuard,
    private val fileService: FileService,
    private val audit: AuditTrail,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun convertImage(
        path: String,
        format: ImageOutputFormat,
        quality: Int
    ): ConversionResult = withContext(ioDispatcher) {
        try {
            val source = guard.guardReadableFile(path).file
            val bitmap = BitmapFactory.decodeFile(source.absolutePath)
                ?: return@withContext ConversionResult(false, "无法解码图片")
            val parent = source.parentFile ?: return@withContext ConversionResult(false, "父目录不存在")
            val directory = ensureConvertedDirectory(parent)
            var index = 0
            var plan = guard.prepareCreate(
                directory.path,
                ConversionPlanner.outputName(source.name, format)
            )
            while (plan.target.exists() && index < 50) {
                index++
                plan = guard.prepareCreate(
                    directory.path,
                    ConversionPlanner.outputName(source.name, format, index)
                )
            }
            guard.verifyCreationTarget(plan)
            plan.target.outputStream().buffered().use { output ->
                val compressFormat = when (format) {
                    ImageOutputFormat.JPG -> Bitmap.CompressFormat.JPEG
                    ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
                    ImageOutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                }
                if (!bitmap.compress(compressFormat, ConversionPlanner.normalizedQuality(quality), output)) {
                    bitmap.recycle()
                    plan.target.delete()
                    return@withContext ConversionResult(false, "图片编码失败")
                }
            }
            bitmap.recycle()
            audit.record(
                category = AuditCategory.FILE,
                operation = "CONVERT_IMAGE",
                status = AuditStatus.SUCCESS,
                sourcePath = source.absolutePath,
                targetPath = plan.target.absolutePath,
                reason = format.name + " quality=" + quality
            )
            ConversionResult(true, "已生成 " + plan.target.name, plan.target.absolutePath)
        } catch (e: Exception) {
            ConversionResult(false, e.message ?: "图片转换失败")
        }
    }

    suspend fun extractAudio(path: String): ConversionResult = withContext(ioDispatcher) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            val source = guard.guardReadableFile(path).file
            extractor.setDataSource(source.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                return@withContext ConversionResult(false, "视频中没有音频轨道")
            }
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val extension = when (mime) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> "m4a"
                MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
                else -> return@withContext ConversionResult(
                    false,
                    "当前原生引擎暂不支持该视频音频直接提取"
                )
            }
            extractor.selectTrack(audioTrack)
            val parent = source.parentFile ?: return@withContext ConversionResult(false, "父目录不存在")
            val base = source.name.substringBeforeLast('.', source.name) + "_audio"
            var index = 0
            var plan = guard.prepareCreate(parent.path, base + "." + extension)
            while (plan.target.exists() && index < 50) {
                index++
                plan = guard.prepareCreate(
                    parent.path,
                    base + " (" + index + ")." + extension
                )
            }
            guard.verifyCreationTarget(plan)
            if (extension == "mp3") {
                java.io.FileOutputStream(plan.target).buffered().use { output ->
                    val buffer = ByteBuffer.allocateDirect(256 * 1024)
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val bytes = ByteArray(size)
                        buffer.position(0)
                        buffer.get(bytes)
                        output.write(bytes)
                        extractor.advance()
                    }
                }
            } else {
                val output = MediaMuxer(
                    plan.target.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = output
                val outputTrack = output.addTrack(format)
                output.start()
                val buffer = ByteBuffer.allocateDirect(256 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    output.writeSampleData(outputTrack, buffer, info)
                    extractor.advance()
                }
                output.stop()
            }
            audit.record(
                category = AuditCategory.FILE,
                operation = "EXTRACT_AUDIO",
                status = AuditStatus.SUCCESS,
                sourcePath = source.absolutePath,
                targetPath = plan.target.absolutePath,
                reason = mime
            )
            ConversionResult(true, "已提取音频 " + plan.target.name, plan.target.absolutePath)
        } catch (e: Exception) {
            ConversionResult(false, "当前视频音频编码无法提取，请尝试其他格式")
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun ensureConvertedDirectory(parent: File): File {
        guard.guardForWrite(parent.absolutePath)
        val target = File(parent, "converted")
        if (!target.exists() && !target.mkdirs()) {
            throw IllegalStateException("无法创建 converted 目录")
        }
        return target
    }
}
