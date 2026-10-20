package com.aster.files.integrity

import com.aster.files.data.CheckLevel
import com.aster.files.data.IntegrityReason
import com.aster.files.data.MediaType
import com.aster.files.data.ReasonCode
import com.aster.files.data.ReasonLevel
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

data class MediaProbeResult(
    val reasons: List<IntegrityReason>,
    val checksPerformed: List<CheckLevel>
)

/**
 * 纯 JVM 的媒体结构探针，执行 L1_METADATA 与 L2_PARSE。
 *
 * 不依赖 Android；真实 Android 解码在 DecodeVerifier 中完成。
 * 解析失败即短路，不进入更高层。
 */
object MediaProbe {

    private const val MAX_EXIF_BYTES = 8 * 1024 * 1024
    private const val MAX_JPEG_SEGMENT = 16 * 1024 * 1024
    private const val MAX_TIFF_BYTES = 8 * 1024 * 1024
    private const val MAX_MP4_SAMPLES = 10_000_000L
    private const val MAX_MP4_CHUNKS = 10_000_000L
    private const val MAX_BOX_DEPTH = 16

    fun inspect(file: File, mediaType: MediaType): MediaProbeResult {
        val reasons = ArrayList<IntegrityReason>()
        val checks = ArrayList<CheckLevel>()
        val length = file.length()

        if (length == 0L) {
            checks.add(CheckLevel.L1_METADATA)
            reasons.add(reason(ReasonCode.ZERO_BYTE, "文件为 0 字节"))
            return MediaProbeResult(reasons, checks)
        }

        val extension = MediaFormats.extensionOf(file.name)
        when (mediaType) {
            MediaType.PHOTO -> inspectPhoto(file, extension, length, reasons, checks)
            MediaType.VIDEO -> inspectVideo(file, extension, length, reasons, checks)
        }
        return MediaProbeResult(reasons, checks)
    }

    private fun inspectPhoto(
        file: File,
        extension: String,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        when (extension) {
            "jpg", "jpeg" -> inspectJpeg(file, length, reasons, checks)
            "png" -> inspectPng(file, length, reasons, checks)
            "gif" -> inspectGif(file, length, reasons, checks)
            "webp" -> inspectRiff(file, length, "WEBP", reasons, checks)
            "bmp" -> inspectBmp(file, length, reasons, checks)
            "tif", "tiff" -> inspectTiff(file, length, reasons, checks)
            else -> unsupported(checks, reasons, "不支持的图片格式")
        }
    }

    private fun inspectVideo(
        file: File,
        extension: String,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        when (extension) {
            "mp4", "m4v", "mov", "3gp", "3g2" -> inspectMp4(file, length, reasons, checks)
            "mkv", "webm" -> inspectMkv(file, length, reasons, checks)
            "avi" -> inspectRiff(file, length, "AVI ", reasons, checks)
            "ts", "m2ts", "mts" -> inspectTs(file, length, reasons, checks)
            "flv" -> inspectFlv(file, length, reasons, checks)
            "wmv" -> inspectAsf(file, length, reasons, checks)
            "mpg", "mpeg" -> inspectMpeg(file, length, reasons, checks)
            else -> unsupported(checks, reasons, "不支持的视频格式")
        }
    }

    // ---------------------------------------------------------------------
    // JPEG
    // ---------------------------------------------------------------------

    private fun inspectJpeg(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 4L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "JPEG 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val head = readAt(raf, 0L, 2)
            if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "JPEG 缺少 SOI"))
                return
            }
            val tail = readAt(raf, length - 2L, 2)
            if (tail[0] != 0xFF.toByte() || tail[1] != 0xD9.toByte()) {
                reasons.add(reason(ReasonCode.TRUNCATED, "JPEG 缺少 EOI"))
                return
            }

            checks.add(CheckLevel.L2_PARSE)
            try {
                parseJpegSegments(raf, length)
            } catch (e: MetadataParseException) {
                reasons.add(reason(ReasonCode.METADATA_ERROR, e.message ?: "JPEG 元数据损坏"))
            }
        }
    }

    private fun parseJpegSegments(raf: RandomAccessFile, length: Long) {
        var position = 2L
        var sawSos = false

        while (position < length - 2L) {
            if (readByte(raf, position) != 0xFF) {
                throw MetadataParseException("JPEG 段标记非法")
            }
            var markerPosition = position + 1L
            var marker = readByte(raf, markerPosition)
            while (marker == 0xFF) {
                markerPosition++
                if (markerPosition >= length - 2L) {
                    throw MetadataParseException("JPEG 段标记越界")
                }
                marker = readByte(raf, markerPosition)
            }
            position = markerPosition + 1L

            if (marker == 0xD9) break
            if (marker == 0xD8) throw MetadataParseException("JPEG 出现重复 SOI")
            if (marker in 0xD0..0xD7 || marker == 0x01) continue

            if (position + 2L > length - 2L) {
                throw MetadataParseException("JPEG 段长度字段越界")
            }
            val segmentLength = readU16BE(raf, position)
            if (segmentLength < 2) throw MetadataParseException("JPEG 段长度非法")
            val payloadStart = position + 2L
            val payloadEnd = payloadStart + segmentLength - 2L
            if (payloadEnd > length - 2L) {
                throw MetadataParseException("JPEG 段超出文件边界")
            }

            if (marker == 0xE1 && segmentLength - 2 >= 6) {
                if (segmentLength > MAX_JPEG_SEGMENT) {
                    throw MetadataParseException("JPEG APP1 过大")
                }
                val payload = readAt(raf, payloadStart, segmentLength - 2)
                if (payload.size >= 6 &&
                    payload[0] == 'E'.code.toByte() &&
                    payload[1] == 'x'.code.toByte() &&
                    payload[2] == 'i'.code.toByte() &&
                    payload[3] == 'f'.code.toByte() &&
                    payload[4] == 0.toByte() &&
                    payload[5] == 0.toByte()
                ) {
                    parseExifPayload(payload)
                }
            }

            if (marker == 0xDA) {
                sawSos = true
                break
            }
            position = payloadEnd
        }

        if (!sawSos) {
            throw MetadataParseException("JPEG 缺少 SOS 扫描段")
        }
    }

    private fun parseExifPayload(payload: ByteArray) {
        if (payload.size < 6) throw MetadataParseException("Exif 段过短")
        val tiff = payload.copyOfRange(6, payload.size)
        parseTiff(tiff)
    }

    // ---------------------------------------------------------------------
    // PNG
    // ---------------------------------------------------------------------

    private fun inspectPng(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 8L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "PNG 文件过短"))
            return
        }

        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        RandomAccessFile(file, "r").use { raf ->
            if (!readAt(raf, 0L, 8).contentEquals(signature)) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "PNG 签名非法"))
                return
            }

            var position = 8L
            var chunkIndex = 0
            var sawIhdr = false
            var sawIend = false
            while (position + 12L <= length) {
                val chunkLength = readU32BE(raf, position)
                if (chunkLength > Int.MAX_VALUE.toLong() ||
                    position + 12L + chunkLength > length
                ) {
                    reasons.add(reason(ReasonCode.TRUNCATED, "PNG 块长度越界"))
                    return
                }
                val type = readFourCc(raf, position + 4L)
                if (chunkIndex == 0) {
                    if (type != "IHDR" || chunkLength != 13L) {
                        reasons.add(reason(ReasonCode.HEADER_MISMATCH, "PNG 首块不是 IHDR"))
                        return
                    }
                    sawIhdr = true
                }
                if (type == "IEND") {
                    sawIend = true
                    if (position + 12L + chunkLength != length) {
                        reasons.add(reason(ReasonCode.HEADER_MISMATCH, "PNG IEND 后存在多余数据"))
                        return
                    }
                    break
                }
                position += 12L + chunkLength
                chunkIndex++
            }

            if (!sawIhdr) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "PNG 缺少 IHDR"))
                return
            }
            if (!sawIend) {
                reasons.add(reason(ReasonCode.TRUNCATED, "PNG 缺少 IEND"))
                return
            }

            checks.add(CheckLevel.L2_PARSE)
            try {
                validatePngChunks(raf, length)
            } catch (e: MetadataParseException) {
                reasons.add(reason(ReasonCode.METADATA_ERROR, e.message ?: "PNG 元数据损坏"))
            }
        }
    }

    private fun validatePngChunks(raf: RandomAccessFile, length: Long) {
        var position = 8L
        var index = 0
        val buffer = ByteArray(64 * 1024)
        while (position + 12L <= length) {
            val chunkLength = readU32BE(raf, position)
            if (chunkLength > Int.MAX_VALUE.toLong() ||
                position + 12L + chunkLength > length
            ) {
                throw MetadataParseException("PNG 块长度越界")
            }
            val typeBytes = readAt(raf, position + 4L, 4)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            val crc = CRC32()
            crc.update(typeBytes)

            var remaining = chunkLength
            raf.seek(position + 8L)
            while (remaining > 0L) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw MetadataParseException("PNG 块数据意外结束")
                crc.update(buffer, 0, read)
                remaining -= read
            }
            val storedCrc = readU32BE(raf, position + 8L + chunkLength)
            if (crc.value != storedCrc) {
                throw MetadataParseException("PNG 块 CRC 校验失败")
            }

            if (type == "IHDR") {
                if (chunkLength != 13L) throw MetadataParseException("PNG IHDR 长度非法")
                val ihdr = readAt(raf, position + 8L, 13)
                val width = readU32(ihdr, 0, false)
                val height = readU32(ihdr, 4, false)
                if (width <= 0L || height <= 0L) {
                    throw MetadataParseException("PNG 宽高非法")
                }
            } else if (type == "eXIf") {
                if (chunkLength > MAX_EXIF_BYTES) {
                    throw MetadataParseException("PNG eXIf 过大")
                }
                val data = readAt(raf, position + 8L, chunkLength.toInt())
                parseTiff(data)
            } else if (type == "IEND") {
                if (chunkLength != 0L) throw MetadataParseException("PNG IEND 长度非法")
                break
            }

            position += 12L + chunkLength
            index++
        }
    }

    // ---------------------------------------------------------------------
    // MP4 / MOV
    // ---------------------------------------------------------------------

    private data class Mp4Box(
        val type: String,
        val start: Long,
        val size: Long,
        val headerSize: Long
    ) {
        val contentStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private class Mp4Stats {
        var trakCount = 0
        var stblCount = 0
        var stszCount = 0
        var stcoCount = 0
        var co64Count = 0
        var sampleCount = 0L
    }

    private fun inspectMp4(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 8L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "MP4 文件过短"))
            return
        }

        RandomAccessFile(file, "r").use { raf ->
            val topLevel = ArrayList<Mp4Box>()
            var position = 0L
            var hasFtyp = false
            var hasMoov = false
            var hasMoof = false

            while (position < length) {
                if (length - position < 8L) {
                    reasons.add(reason(ReasonCode.TRUNCATED, "MP4 顶层 box 头不完整"))
                    return
                }
                val size32 = readU32BE(raf, position)
                val type = readFourCc(raf, position + 4L)
                var headerSize = 8L
                var size = size32
                if (size32 == 1L) {
                    if (length - position < 16L) {
                        reasons.add(reason(ReasonCode.TRUNCATED, "MP4 64 位 box 头不完整"))
                        return
                    }
                    size = readU64BE(raf, position + 8L)
                    headerSize = 16L
                } else if (size32 == 0L) {
                    size = length - position
                }
                if (size < headerSize || size > length - position) {
                    reasons.add(reason(ReasonCode.TRUNCATED, "MP4 box 超出文件边界"))
                    return
                }
                when (type) {
                    "ftyp" -> hasFtyp = true
                    "moov" -> hasMoov = true
                    "moof" -> hasMoof = true
                }
                topLevel.add(Mp4Box(type, position, size, headerSize))
                position += size
            }

            if (!hasFtyp) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "MP4 缺少 ftyp"))
                return
            }
            if (!hasMoov && !hasMoof) {
                reasons.add(reason(ReasonCode.TRUNCATED, "MP4 缺少 moov/moof"))
                return
            }

            checks.add(CheckLevel.L2_PARSE)
            if (!hasMoov) {
                reasons.add(reason(ReasonCode.METADATA_ERROR, "MP4 仅有 moof，缺少 moov"))
                return
            }
            val moov = topLevel.first { it.type == "moov" }
            try {
                val stats = Mp4Stats()
                parseMp4Container(raf, moov.contentStart, moov.end, 0, stats, length)
                if (stats.trakCount == 0) throw MetadataParseException("MP4 缺少 trak")
                if (stats.stblCount == 0) throw MetadataParseException("MP4 缺少 stbl")
                if (stats.stszCount == 0) throw MetadataParseException("MP4 缺少 stsz")
                if (stats.stcoCount == 0 && stats.co64Count == 0) {
                    throw MetadataParseException("MP4 缺少 stco/co64")
                }
                if (stats.sampleCount <= 0L) throw MetadataParseException("MP4 采样数为 0")
            } catch (e: MetadataParseException) {
                reasons.add(reason(ReasonCode.METADATA_ERROR, e.message ?: "MP4 元数据损坏"))
            }
            Unit
        }
    }

    private fun parseMp4Container(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        stats: Mp4Stats,
        fileLength: Long
    ) {
        if (depth > MAX_BOX_DEPTH) throw MetadataParseException("MP4 box 嵌套过深")
        var position = start
        while (position < end) {
            if (end - position < 8L) throw MetadataParseException("MP4 子 box 头不完整")
            val size32 = readU32BE(raf, position)
            val type = readFourCc(raf, position + 4L)
            var headerSize = 8L
            var size = size32
            if (size32 == 1L) {
                if (end - position < 16L) throw MetadataParseException("MP4 64 位子 box 头不完整")
                size = readU64BE(raf, position + 8L)
                headerSize = 16L
            } else if (size32 == 0L) {
                size = end - position
            }
            if (size < headerSize || size > end - position) {
                throw MetadataParseException("MP4 子 box 越界")
            }

            val box = Mp4Box(type, position, size, headerSize)
            when (type) {
                "trak" -> {
                    stats.trakCount++
                    parseMp4Container(raf, box.contentStart, box.end, depth + 1, stats, fileLength)
                }
                "stbl" -> {
                    stats.stblCount++
                    parseMp4Container(raf, box.contentStart, box.end, depth + 1, stats, fileLength)
                }
                "moov", "mdia", "minf" -> {
                    parseMp4Container(raf, box.contentStart, box.end, depth + 1, stats, fileLength)
                }
                "stsz" -> {
                    stats.stszCount++
                    parseStsz(raf, box, stats)
                }
                "stco" -> {
                    stats.stcoCount++
                    parseStco(raf, box, fileLength, eightByteOffsets = false)
                }
                "co64" -> {
                    stats.co64Count++
                    parseStco(raf, box, fileLength, eightByteOffsets = true)
                }
                else -> Unit
            }
            position += size
        }
        if (position != end) throw MetadataParseException("MP4 子 box 边界不一致")
    }

    private fun parseStsz(raf: RandomAccessFile, box: Mp4Box, stats: Mp4Stats) {
        val payloadLength = box.size - box.headerSize
        if (payloadLength < 12L) throw MetadataParseException("MP4 stsz 过短")
        val sampleSize = readU32BE(raf, box.contentStart + 4L)
        val sampleCount = readU32BE(raf, box.contentStart + 8L)
        if (sampleCount <= 0L || sampleCount > MAX_MP4_SAMPLES) {
            throw MetadataParseException("MP4 stsz 采样数非法")
        }
        if (sampleSize == 0L) {
            val required = 12L + sampleCount * 4L
            if (required > payloadLength) throw MetadataParseException("MP4 stsz 样本表越界")
        }
        stats.sampleCount += sampleCount
        if (stats.sampleCount > MAX_MP4_SAMPLES) {
            throw MetadataParseException("MP4 采样总数过大")
        }
    }

    private fun parseStco(
        raf: RandomAccessFile,
        box: Mp4Box,
        fileLength: Long,
        eightByteOffsets: Boolean
    ) {
        val payloadLength = box.size - box.headerSize
        if (payloadLength < 8L) throw MetadataParseException("MP4 stco 过短")
        val entryCount = readU32BE(raf, box.contentStart + 4L)
        if (entryCount <= 0L || entryCount > MAX_MP4_CHUNKS) {
            throw MetadataParseException("MP4 stco 条目数非法")
        }
        val entrySize = if (eightByteOffsets) 8L else 4L
        if (8L + entryCount * entrySize > payloadLength) {
            throw MetadataParseException("MP4 stco 条目越界")
        }
        val count = entryCount.toInt()
        for (index in 0 until count) {
            val entryOffset = box.contentStart + 8L + index.toLong() * entrySize
            val mediaOffset = if (eightByteOffsets) {
                readU64BE(raf, entryOffset)
            } else {
                readU32BE(raf, entryOffset)
            }
            if (mediaOffset < 0L || mediaOffset >= fileLength) {
                throw MetadataParseException("MP4 媒体偏移越界")
            }
        }
    }

    // ---------------------------------------------------------------------
    // MKV / WebM
    // ---------------------------------------------------------------------

    private fun inspectMkv(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 8L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "MKV 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val magic = readAt(raf, 0L, 4)
            if (magic[0] != 0x1A.toByte() || magic[1] != 0x45.toByte() ||
                magic[2] != 0xDF.toByte() || magic[3] != 0xA3.toByte()
            ) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "MKV 缺少 EBML 头"))
                return
            }
            val ebmlSize = readEbmlSize(raf, 4L, length)
                ?: run {
                    reasons.add(reason(ReasonCode.TRUNCATED, "MKV EBML 头大小缺失"))
                    return
                }
            val ebmlEnd = 4L + ebmlSize.second + ebmlSize.first
            if (ebmlEnd > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "MKV EBML 头越界"))
                return
            }

            var segmentFound = false
            var position = ebmlEnd
            val scanEnd = minOf(length - 4L, ebmlEnd + 64L)
            while (position <= scanEnd) {
                if (readU32BE(raf, position) == 0x18538067L) {
                    segmentFound = true
                    break
                }
                position++
            }
            if (!segmentFound) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "MKV 缺少 Segment"))
            }
        }
    }

    // ---------------------------------------------------------------------
    // 其他格式的 L1
    // ---------------------------------------------------------------------

    private fun inspectGif(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 14L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "GIF 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val header = String(readAt(raf, 0L, 6), StandardCharsets.US_ASCII)
            if (header != "GIF87a" && header != "GIF89a") {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "GIF 头非法"))
                return
            }
            if (readByte(raf, length - 1L) != 0x3B) {
                reasons.add(reason(ReasonCode.TRUNCATED, "GIF 缺少尾标记"))
            }
        }
    }

    private fun inspectRiff(
        file: File,
        length: Long,
        expectedForm: String,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 12L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "RIFF 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            if (readFourCc(raf, 0L) != "RIFF") {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "RIFF 头非法"))
                return
            }
            val declaredSize = readU32LE(raf, 4L)
            if (declaredSize < 4L || declaredSize + 8L > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "RIFF 声明大小越界"))
                return
            }
            val form = readFourCc(raf, 8L)
            if (form != expectedForm) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "RIFF 子类型不匹配"))
                return
            }
            checks.add(CheckLevel.L2_PARSE)
            try {
                val end = declaredSize + 8L
                var position = 12L
                while (position + 8L <= end) {
                    val chunkSize = readU32LE(raf, position + 4L)
                    val dataEnd = position + 8L + chunkSize
                    if (dataEnd > end) throw MetadataParseException("RIFF 子块越界")
                    position = dataEnd + (if (chunkSize % 2L == 1L) 1L else 0L)
                }
                if (position > end) throw MetadataParseException("RIFF 子块填充越界")
            } catch (e: MetadataParseException) {
                reasons.add(reason(ReasonCode.METADATA_ERROR, e.message ?: "RIFF 元数据损坏"))
            }
            Unit
        }
    }

    private fun inspectBmp(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 14L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "BMP 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            if (readByte(raf, 0L) != 'B'.code || readByte(raf, 1L) != 'M'.code) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "BMP 头非法"))
                return
            }
            val declaredSize = readU32LE(raf, 2L)
            if (declaredSize < 14L || declaredSize > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "BMP 声明大小越界"))
            }
        }
    }

    private fun inspectTiff(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 8L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "TIFF 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val header = readAt(raf, 0L, 8)
            val little = when {
                header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> true
                header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> false
                else -> {
                    reasons.add(reason(ReasonCode.HEADER_MISMATCH, "TIFF 字节序非法"))
                    return
                }
            }
            if (readU16(header, 2, little) != 42) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "TIFF 魔数非法"))
                return
            }
            val ifdOffset = readU32(header, 4, little)
            if (ifdOffset < 8L || ifdOffset + 2L > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "TIFF IFD 偏移越界"))
                return
            }
            if (length <= MAX_TIFF_BYTES) {
                checks.add(CheckLevel.L2_PARSE)
                try {
                    parseTiff(readAt(raf, 0L, length.toInt()))
                } catch (e: MetadataParseException) {
                    reasons.add(reason(ReasonCode.METADATA_ERROR, e.message ?: "TIFF 元数据损坏"))
                }
            }
        }
    }

    private fun inspectTs(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 376L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "TS 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            if (readByte(raf, 0L) != 0x47 || readByte(raf, 188L) != 0x47) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "TS 同步字节非法"))
            }
        }
    }

    private fun inspectFlv(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 9L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "FLV 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val header = String(readAt(raf, 0L, 3), StandardCharsets.US_ASCII)
            if (header != "FLV") {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "FLV 头非法"))
                return
            }
            val dataOffset = readU32BE(raf, 5L)
            if (dataOffset < 9L || dataOffset > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "FLV 数据偏移越界"))
            }
        }
    }

    private fun inspectAsf(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 30L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "WMV/ASF 文件过短"))
            return
        }
        val guid = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75,
            0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(),
            0x00, 0x62, 0xCE.toByte(), 0x6C
        )
        RandomAccessFile(file, "r").use { raf ->
            if (!readAt(raf, 0L, 16).contentEquals(guid)) {
                reasons.add(reason(ReasonCode.HEADER_MISMATCH, "WMV/ASF GUID 非法"))
                return
            }
            val headerSize = readU64LE(raf, 16L)
            if (headerSize < 30L || headerSize > length) {
                reasons.add(reason(ReasonCode.TRUNCATED, "WMV/ASF 头大小越界"))
            }
        }
    }

    private fun inspectMpeg(
        file: File,
        length: Long,
        reasons: MutableList<IntegrityReason>,
        checks: MutableList<CheckLevel>
    ) {
        checks.add(CheckLevel.L1_METADATA)
        if (length < 4L) {
            reasons.add(reason(ReasonCode.TRUNCATED, "MPEG 文件过短"))
            return
        }
        RandomAccessFile(file, "r").use { raf ->
            val bytes = readAt(raf, 0L, 4)
            val valid = bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 1.toByte() &&
                (bytes[3] == 0xBA.toByte() || bytes[3] == 0xB3.toByte())
            if (!valid) reasons.add(reason(ReasonCode.HEADER_MISMATCH, "MPEG 起始码非法"))
        }
    }

    // ---------------------------------------------------------------------
    // TIFF / EXIF IFD
    // ---------------------------------------------------------------------

    private fun parseTiff(bytes: ByteArray) {
        if (bytes.size < 8) throw MetadataParseException("TIFF 头过短")
        val little = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> throw MetadataParseException("TIFF 字节序非法")
        }
        if (readU16(bytes, 2, little) != 42) throw MetadataParseException("TIFF 魔数非法")
        val ifdOffset = readU32(bytes, 4, little)
        if (ifdOffset < 8L || ifdOffset + 2L > bytes.size) {
            throw MetadataParseException("TIFF IFD0 偏移越界")
        }
        val visited = HashSet<Long>()
        parseIfd(bytes, ifdOffset, little, 0, visited)
    }

    private fun parseIfd(
        bytes: ByteArray,
        offset: Long,
        little: Boolean,
        depth: Int,
        visited: MutableSet<Long>
    ) {
        if (depth > 8) throw MetadataParseException("TIFF IFD 嵌套过深")
        if (!visited.add(offset)) throw MetadataParseException("TIFF IFD 循环引用")
        if (offset < 0L || offset + 2L > bytes.size) {
            throw MetadataParseException("TIFF IFD 偏移越界")
        }
        val entryCount = readU16(bytes, offset, little)
        val entriesStart = offset + 2L
        val entriesEnd = entriesStart + entryCount.toLong() * 12L
        if (entriesEnd + 4L > bytes.size) {
            throw MetadataParseException("TIFF IFD 表项越界")
        }

        for (index in 0 until entryCount) {
            val entryOffset = entriesStart + index * 12L
            val tag = readU16(bytes, entryOffset, little)
            val type = readU16(bytes, entryOffset + 2L, little)
            val valueCount = readU32(bytes, entryOffset + 4L, little)
            val unitSize = tiffTypeSize(type)
                ?: throw MetadataParseException("TIFF 字段类型未知")
            val valueSize = unitSize * valueCount
            if (valueSize < 0L) throw MetadataParseException("TIFF 字段大小溢出")

            var valueOffset = entryOffset + 8L
            if (valueSize > 4L) {
                val pointer = readU32(bytes, entryOffset + 8L, little)
                if (pointer < 0L || pointer + valueSize > bytes.size) {
                    throw MetadataParseException("TIFF 字段值越界")
                }
                valueOffset = pointer
            }

            if (tag == 0x8769 || tag == 0x8825) {
                if (valueSize < 4L) throw MetadataParseException("TIFF 子 IFD 指针非法")
                val pointer = if (valueSize > 4L) {
                    valueOffset
                } else {
                    readU32(bytes, valueOffset, little)
                }
                if (pointer != 0L) {
                    parseIfd(bytes, pointer, little, depth + 1, visited)
                }
            }
        }

        val nextIfd = readU32(bytes, entriesEnd, little)
        if (nextIfd != 0L) {
            parseIfd(bytes, nextIfd, little, depth + 1, visited)
        }
    }

    private fun tiffTypeSize(type: Int): Long? = when (type) {
        1, 2, 6, 7 -> 1L
        3, 8 -> 2L
        4, 9, 11 -> 4L
        5, 10, 12 -> 8L
        else -> null
    }

    private fun readU16(bytes: ByteArray, offset: Long, little: Boolean): Int {
        val index = offset.toInt()
        if (index < 0 || index + 2 > bytes.size) throw MetadataParseException("TIFF 读取越界")
        val b0 = bytes[index].toInt() and 0xFF
        val b1 = bytes[index + 1].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun readU32(bytes: ByteArray, offset: Long, little: Boolean): Long {
        val index = offset.toInt()
        if (index < 0 || index + 4 > bytes.size) throw MetadataParseException("TIFF 读取越界")
        var value = 0L
        if (little) {
            for (i in 0 until 4) {
                value = value or ((bytes[index + i].toLong() and 0xFFL) shl (i * 8))
            }
        } else {
            for (i in 0 until 4) {
                value = (value shl 8) or (bytes[index + i].toLong() and 0xFFL)
            }
        }
        return value
    }

    // ---------------------------------------------------------------------
    // 通用读取
    // ---------------------------------------------------------------------

    private fun unsupported(
        checks: MutableList<CheckLevel>,
        reasons: MutableList<IntegrityReason>,
        message: String
    ) {
        checks.add(CheckLevel.L1_METADATA)
        reasons.add(reason(ReasonCode.HEADER_MISMATCH, message))
    }

    private fun reason(code: ReasonCode, detail: String): IntegrityReason =
        IntegrityReason(code, detail, ReasonLevel.ERROR)

    private fun readAt(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
        if (offset < 0L || size < 0 || offset + size > raf.length()) {
            throw MetadataParseException("读取越过文件边界")
        }
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    private fun readByte(raf: RandomAccessFile, offset: Long): Int {
        if (offset < 0L || offset >= raf.length()) {
            throw MetadataParseException("读取越过文件边界")
        }
        raf.seek(offset)
        return raf.read()
    }

    private fun readU16BE(raf: RandomAccessFile, offset: Long): Int {
        val bytes = readAt(raf, offset, 2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun readU32BE(raf: RandomAccessFile, offset: Long): Long {
        val bytes = readAt(raf, offset, 4)
        var value = 0L
        for (index in bytes.indices) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readU64BE(raf: RandomAccessFile, offset: Long): Long {
        val bytes = readAt(raf, offset, 8)
        var value = 0L
        for (index in bytes.indices) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        if (value < 0L) throw MetadataParseException("64 位长度溢出")
        return value
    }

    private fun readU32LE(raf: RandomAccessFile, offset: Long): Long {
        val bytes = readAt(raf, offset, 4)
        var value = 0L
        for (index in 0 until 4) {
            value = value or ((bytes[index].toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    private fun readU64LE(raf: RandomAccessFile, offset: Long): Long {
        val bytes = readAt(raf, offset, 8)
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[index].toLong() and 0xFFL) shl (index * 8))
        }
        if (value < 0L) throw MetadataParseException("64 位长度溢出")
        return value
    }

    private fun readFourCc(raf: RandomAccessFile, offset: Long): String =
        String(readAt(raf, offset, 4), StandardCharsets.US_ASCII)

    /** 返回 (sizeValue, sizeFieldLength(含 length 位))；unknown size 返回 null。 */
    private fun readEbmlSize(raf: RandomAccessFile, offset: Long, fileLength: Long): Pair<Long, Long>? {
        if (offset < 0L || offset >= fileLength) return null
        val first = readByte(raf, offset)
        var length = 1
        var mask = 0x80
        while (length <= 8 && (first and mask) == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 8 || offset + length > fileLength) return null

        val bytes = readAt(raf, offset, length)
        var value = (bytes[0].toLong() and 0xFFL) and (mask - 1).toLong()
        var allOnes = value == (mask - 1).toLong()
        for (index in 1 until length) {
            val byte = bytes[index].toLong() and 0xFFL
            value = (value shl 8) or byte
            if (byte != 0xFFL) allOnes = false
        }
        if (allOnes) return null
        return value to length.toLong()
    }

    private class MetadataParseException(message: String) : Exception(message)
}
