package com.aster.files.support

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 测试辅助：直接修改 ZIP 中央目录的 external attributes / flags，
 * 用于构造符号链接条目与加密条目。仅测试使用。
 */
object ZipPatcher {

    fun setSymlinkEntry(zipFile: File, entryName: String) {
        setExternalAttributes(zipFile, entryName, 0xA1FF)
    }

    fun setExternalAttributes(zipFile: File, entryName: String, unixMode: Int) {
        RandomAccessFile(zipFile, "rw").use { raf ->
            val eocd = findEocd(raf)
            val totalEntries = readU16(raf, eocd + 10L)
            val centralOffset = readU32(raf, eocd + 16L)
            var position = centralOffset

            repeat(totalEntries) {
                require(readU32(raf, position) == 0x02014b50L) {
                    "invalid central directory at $position"
                }
                val nameLength = readU16(raf, position + 28L)
                val extraLength = readU16(raf, position + 30L)
                val commentLength = readU16(raf, position + 32L)
                val name = String(
                    readBytes(raf, position + 46L, nameLength),
                    StandardCharsets.UTF_8
                )
                if (name == entryName) {
                    writeU16(raf, position + 4L, 0x0314) // Unix, version 2.0
                    writeU32(
                        raf,
                        position + 38L,
                        (unixMode.toLong() and 0xFFFFL) shl 16
                    )
                    return
                }
                position += 46L + nameLength + extraLength + commentLength
            }
            throw IllegalArgumentException("ZIP entry not found: $entryName")
        }
    }

    fun setEncryptedFlag(zipFile: File, entryName: String) {
        RandomAccessFile(zipFile, "rw").use { raf ->
            val eocd = findEocd(raf)
            val totalEntries = readU16(raf, eocd + 10L)
            val centralOffset = readU32(raf, eocd + 16L)
            var position = centralOffset

            repeat(totalEntries) {
                require(readU32(raf, position) == 0x02014b50L)
                val nameLength = readU16(raf, position + 28L)
                val extraLength = readU16(raf, position + 30L)
                val commentLength = readU16(raf, position + 32L)
                val name = String(
                    readBytes(raf, position + 46L, nameLength),
                    StandardCharsets.UTF_8
                )
                if (name == entryName) {
                    val localOffset = readU32(raf, position + 42L)
                    writeU16(raf, position + 8L, readU16(raf, position + 8L) or 0x1)
                    writeU16(raf, localOffset + 6L, readU16(raf, localOffset + 6L) or 0x1)
                    return
                }
                position += 46L + nameLength + extraLength + commentLength
            }
            throw IllegalArgumentException("ZIP entry not found: $entryName")
        }
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val length = raf.length()
        val start = (length - 22L - 65535L).coerceAtLeast(0L)
        var position = length - 22L
        while (position >= start) {
            if (readU32(raf, position) == 0x06054b50L) {
                val commentLength = readU16(raf, position + 20L)
                if (position + 22L + commentLength <= length) return position
            }
            position--
        }
        throw IllegalArgumentException("EOCD not found")
    }

    private fun readBytes(raf: RandomAccessFile, position: Long, length: Int): ByteArray {
        raf.seek(position)
        val bytes = ByteArray(length)
        raf.readFully(bytes)
        return bytes
    }

    private fun readU16(raf: RandomAccessFile, position: Long): Int {
        raf.seek(position)
        val low = raf.read()
        val high = raf.read()
        require(low >= 0 && high >= 0)
        return low or (high shl 8)
    }

    private fun readU32(raf: RandomAccessFile, position: Long): Long {
        raf.seek(position)
        var value = 0L
        for (index in 0 until 4) {
            val byte = raf.read()
            require(byte >= 0)
            value = value or (byte.toLong() shl (index * 8))
        }
        return value
    }

    private fun writeU16(raf: RandomAccessFile, position: Long, value: Int) {
        raf.seek(position)
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(raf: RandomAccessFile, position: Long, value: Long) {
        raf.seek(position)
        for (index in 0 until 4) {
            raf.write(((value ushr (index * 8)) and 0xFF).toInt())
        }
    }
}
