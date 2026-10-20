package com.aster.files.image

import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import java.io.File

/**
 * Coil APNG 解码器。
 *
 * Coil 的 BitmapFactory/ImageDecoder 链路不播放 APNG；此工厂只对包含 acTL 的 PNG
 * 生效，返回 APNG4Android 的 Drawable，让 AsyncImage 与 GIF 一样自动播放。
 */
class ApngDecoderFactory : Decoder.Factory {

    override fun create(
        result: SourceResult,
        options: Options,
        imageLoader: ImageLoader
    ): Decoder? {
        val path = result.source.fileOrNull()?.toString() ?: return null
        if (!ApngDetector.isApng(File(path))) return null
        return ApngDecoder(path)
    }

    private class ApngDecoder(private val path: String) : Decoder {
        override suspend fun decode(): DecodeResult {
            val drawable = APNGDrawable.fromFile(path)
            drawable.start()
            return DecodeResult(drawable, false)
        }
    }
}

object ApngDetector {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun isApng(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (total < 8) return false
                for (index in PNG_SIGNATURE.indices) {
                    if (buffer[index] != PNG_SIGNATURE[index]) return false
                }
                var cursor = 8
                while (cursor + 8 <= total) {
                    val length = readInt(buffer, cursor)
                    if (length < 0 || cursor + 12L + length > total) break
                    val type = String(buffer, cursor + 4, 4, Charsets.US_ASCII)
                    if (type == "acTL") return true
                    if (type == "IDAT") return false
                    cursor += 12 + length
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
}
