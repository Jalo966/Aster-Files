package com.aster.files.support

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater

/** 构造正常/损坏媒体样本，供 L1/L2/L3 测试使用（不依赖 java.awt）。 */
object IntegrityFixtures {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun writeJpeg(file: File) {
        file.parentFile?.mkdirs()
        // SOI + 最小 APP0(JFIF) + SOS 头 + EOI；结构足够 L1/L2 解析。
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0xFF.toByte(), 0xD9.toByte()
        )
        file.writeBytes(bytes)
    }

    fun writePng(file: File) {
        file.parentFile?.mkdirs()
        val width = 4
        val height = 4
        val ihdrData = ByteArray(13)
        putIntBE(ihdrData, 0, width)
        putIntBE(ihdrData, 4, height)
        ihdrData[8] = 8 // bit depth
        ihdrData[9] = 2 // RGB
        ihdrData[10] = 0
        ihdrData[11] = 0
        ihdrData[12] = 0

        val raw = ByteArray(height * (1 + width * 3))
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            compressed.write(buffer, 0, count)
        }
        deflater.end()

        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        output.write(pngChunk("IHDR", ihdrData))
        output.write(pngChunk("IDAT", compressed.toByteArray()))
        output.write(pngChunk("IEND", ByteArray(0)))
        file.writeBytes(output.toByteArray())
    }

    fun truncate(file: File, bytes: Int) {
        val data = file.readBytes()
        require(bytes in 1..data.size)
        file.writeBytes(data.copyOf(data.size - bytes))
    }

    fun writeEmpty(file: File) {
        file.parentFile?.mkdirs()
        Files.createFile(file.toPath())
    }

    fun corruptPngCrc(file: File) {
        val bytes = file.readBytes()
        require(bytes.size > 32)
        val chunkType = String(bytes, 12, 4, StandardCharsets.US_ASCII)
        require(chunkType == "IHDR") { "首个 PNG 块不是 IHDR" }
        val length = ((bytes[8].toInt() and 0xFF) shl 24) or
            ((bytes[9].toInt() and 0xFF) shl 16) or
            ((bytes[10].toInt() and 0xFF) shl 8) or
            (bytes[11].toInt() and 0xFF)
        val crcOffset = 8 + 4 + 4 + length
        require(crcOffset + 4 <= bytes.size)
        bytes[crcOffset] = (bytes[crcOffset].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)
    }

    fun writeJpegWithInvalidExif(file: File) {
        file.parentFile?.mkdirs()
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(),
            0x2A, 0x00,
            0x00, 0x10, 0x00, 0x00
        )
        val payload = "Exif\u0000\u0000".toByteArray(StandardCharsets.ISO_8859_1) + tiff
        val app1Length = payload.size + 2
        val output = ByteArrayOutputStream()
        output.write(0xFF)
        output.write(0xD8)
        output.write(0xFF)
        output.write(0xE1)
        output.write((app1Length ushr 8) and 0xFF)
        output.write(app1Length and 0xFF)
        output.write(payload)
        output.write(0xFF)
        output.write(0xDA)
        output.write(0x00)
        output.write(0x02)
        output.write(0xFF)
        output.write(0xD9)
        file.writeBytes(output.toByteArray())
    }

    fun writeValidMp4(file: File) {
        file.parentFile?.mkdirs()
        val ftyp = box("ftyp", "isom".toByteArray(StandardCharsets.US_ASCII) + ByteArray(4))
        val stsz = box("stsz", u32(0) + u32(4) + u32(2))
        val stco = box("stco", u32(0) + u32(1) + u32(0))
        val stbl = box("stbl", stsz + stco)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val mdat = box("mdat", ByteArray(4))
        file.writeBytes(ftyp + moov + mdat)
    }

    fun writeMp4WithBadStsz(file: File) {
        file.parentFile?.mkdirs()
        val ftyp = box("ftyp", "isom".toByteArray(StandardCharsets.US_ASCII) + ByteArray(4))
        val stsz = box("stsz", u32(0) + u32(0) + u32(100))
        val stco = box("stco", u32(0) + u32(1) + u32(0))
        val stbl = box("stbl", stsz + stco)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        file.writeBytes(ftyp + moov)
    }

    fun writeTruncatedMp4(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(u32(100) + "ftyp".toByteArray(StandardCharsets.US_ASCII))
    }

    fun writeValidMkv(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(
            byteArrayOf(
                0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(),
                0x80.toByte(),
                0x18, 0x53, 0x80.toByte(), 0x67,
                0x80.toByte()
            )
        )
    }

    fun writeTruncatedMkv(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte())
        )
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        writeIntBE(output, data.size)
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeIntBE(output, crc.value.toInt())
        return output.toByteArray()
    }

    private fun writeIntBE(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun putIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return u32(size) +
            type.toByteArray(StandardCharsets.US_ASCII) +
            payload
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}
