package com.aster.files.services

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** ZIP 安全检查失败；code 是稳定错误码。 */
class ZipSecurityException(val code: String, message: String) : IOException(message)

data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: Int,
    val externalAttributes: Long
)

data class ZipInspection(
    val entries: List<ZipEntryInfo>,
    val totalUncompressedBytes: Long
)

/**
 * 纯 JVM 的 ZIP 中央目录安全检查器。
 *
 * 在解压前检查：
 * - 绝对路径、..、反斜杠、空段、盘符；
 * - 条目数上限与总解压大小上限；
 * - 加密条目、非 STORED/DEFLATED 条目、ZIP64；
 * - Unix 符号链接条目；
 * - 重名条目；
 * - 本地文件头与中央目录名称/方法一致性。
 *
 * 该检查不依赖 Android，可在 src/test 中直接测试。
 */
object ZipSecurityInspector {

    const val CODE_INVALID = "ZIP_INVALID"
    const val CODE_TOO_MANY_ENTRIES = "ZIP_TOO_MANY_ENTRIES"
    const val CODE_TOO_LARGE = "ZIP_TOO_LARGE"
    const val CODE_PATH_TRAVERSAL = "ZIP_PATH_TRAVERSAL"
    const val CODE_ABSOLUTE_PATH = "ZIP_ABSOLUTE_PATH"
    const val CODE_BACKSLASH = "ZIP_BACKSLASH"
    const val CODE_SYMLINK = "ZIP_SYMLINK"
    const val CODE_ENCRYPTED = "ZIP_ENCRYPTED"
    const val CODE_UNSUPPORTED = "ZIP_UNSUPPORTED"
    const val CODE_DUPLICATE_ENTRY = "ZIP_DUPLICATE_ENTRY"
    const val CODE_ZIP64_UNSUPPORTED = "ZIP_ZIP64_UNSUPPORTED"

    const val MAX_ENTRY_NAME_LENGTH = 4096
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val MAX_COMMENT_LENGTH = 65535L

    fun inspect(file: File, maxEntries: Int, maxTotalBytes: Long): ZipInspection {
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
        require(maxTotalBytes > 0) { "maxTotalBytes 必须大于 0" }

        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 22L) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 文件过小")
            }

            val eocdOffset = findEocd(raf, length)
            raf.seek(eocdOffset)
            if (raf.readU32() != EOCD_SIGNATURE) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 结束记录签名非法")
            }

            val diskNumber = raf.readU16()
            val centralDisk = raf.readU16()
            val entriesOnDisk = raf.readU16()
            val totalEntries = raf.readU16()
            val centralSize = raf.readU32()
            val centralOffset = raf.readU32()
            val commentLength = raf.readU16()

            if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
                throw ZipSecurityException(CODE_INVALID, "不支持分卷 ZIP")
            }
            if (eocdOffset + 22L + commentLength > length) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 注释长度越界")
            }
            if (totalEntries > maxEntries) {
                throw ZipSecurityException(
                    CODE_TOO_MANY_ENTRIES,
                    "ZIP 条目数 $totalEntries 超过上限 $maxEntries"
                )
            }
            if (centralOffset > length || centralSize > length ||
                centralOffset + centralSize > length
            ) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 中央目录越界")
            }

            val entries = ArrayList<ZipEntryInfo>(totalEntries)
            val names = HashSet<String>(totalEntries)
            var totalUncompressed = 0L
            var position = centralOffset

            for (index in 0 until totalEntries) {
                raf.seek(position)
                if (raf.readU32() != CENTRAL_SIGNATURE) {
                    throw ZipSecurityException(CODE_INVALID, "ZIP 中央目录条目签名非法")
                }

                raf.readU16() // version made by
                raf.readU16() // version needed
                val flags = raf.readU16()
                val method = raf.readU16()
                raf.readU16() // mod time
                raf.readU16() // mod date
                val crc = raf.readU32()
                val compressedSize = raf.readU32()
                val uncompressedSize = raf.readU32()
                val nameLength = raf.readU16()
                val extraLength = raf.readU16()
                val entryCommentLength = raf.readU16()
                val diskStart = raf.readU16()
                raf.readU16() // internal attrs
                val externalAttributes = raf.readU32()
                val localOffset = raf.readU32()

                val entryEnd = position + 46L + nameLength + extraLength + entryCommentLength
                if (entryEnd > centralOffset + centralSize) {
                    throw ZipSecurityException(CODE_INVALID, "ZIP 中央目录条目越界")
                }
                if (diskStart != 0) {
                    throw ZipSecurityException(CODE_INVALID, "不支持分卷 ZIP 条目")
                }
                if (nameLength <= 0 || nameLength > MAX_ENTRY_NAME_LENGTH) {
                    throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称长度非法")
                }
                if (flags and 0x1 != 0) {
                    throw ZipSecurityException(CODE_ENCRYPTED, "ZIP 包含加密条目")
                }
                if (method != 0 && method != 8) {
                    throw ZipSecurityException(CODE_UNSUPPORTED, "ZIP 包含不支持的压缩方法 $method")
                }
                if (compressedSize == 0xFFFFFFFFL ||
                    uncompressedSize == 0xFFFFFFFFL ||
                    localOffset == 0xFFFFFFFFL
                ) {
                    throw ZipSecurityException(CODE_ZIP64_UNSUPPORTED, "暂不支持 ZIP64 条目")
                }

                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8)
                validateEntryName(name)

                // 目录 a/ 与文件 a 在文件系统上冲突，按去掉结尾斜杠的路径判重。
                if (!names.add(name.trimEnd('/'))) {
                    throw ZipSecurityException(CODE_DUPLICATE_ENTRY, "ZIP 包含重名条目：$name")
                }

                val isDirectory = name.endsWith("/")
                val unixMode = (externalAttributes ushr 16) and 0xFFFFL
                val unixType = unixMode and 0xF000L
                if (unixType == 0xA000L) {
                    throw ZipSecurityException(CODE_SYMLINK, "ZIP 包含符号链接条目：$name")
                }
                if (unixType == 0x4000L && !isDirectory) {
                    throw ZipSecurityException(CODE_INVALID, "ZIP 目录条目名称缺少结尾斜杠：$name")
                }
                if ((externalAttributes and 0x10L) != 0L && !isDirectory) {
                    throw ZipSecurityException(CODE_INVALID, "ZIP 目录条目名称缺少结尾斜杠：$name")
                }

                if (Long.MAX_VALUE - totalUncompressed < uncompressedSize) {
                    throw ZipSecurityException(CODE_TOO_LARGE, "ZIP 总解压大小溢出")
                }
                totalUncompressed += uncompressedSize
                if (totalUncompressed > maxTotalBytes) {
                    throw ZipSecurityException(
                        CODE_TOO_LARGE,
                        "ZIP 总解压大小超过上限：$maxTotalBytes 字节"
                    )
                }

                verifyLocalHeader(
                    raf = raf,
                    localOffset = localOffset,
                    centralMethod = method,
                    centralFlags = flags,
                    centralName = nameBytes,
                    compressedSize = compressedSize,
                    centralOffset = centralOffset
                )

                entries.add(
                    ZipEntryInfo(
                        name = name,
                        isDirectory = isDirectory,
                        uncompressedSize = uncompressedSize,
                        compressedSize = compressedSize,
                        crc = crc,
                        method = method,
                        externalAttributes = externalAttributes
                    )
                )
                position = entryEnd
            }

            if (position != centralOffset + centralSize) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 中央目录长度不一致")
            }
            return ZipInspection(entries, totalUncompressed)
        }
    }

    /**
     * 校验 ZIP 条目名称；返回原名称。
     *
     * 目录条目允许以 / 结尾，其余路径段不允许空、. 或 ..。
     */
    fun validateEntryName(name: String): String {
        if (name.isEmpty()) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称为空")
        }
        if (name.length > MAX_ENTRY_NAME_LENGTH) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称过长")
        }
        if (name.indexOf('\u0000') >= 0) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称包含 NUL")
        }
        if (name.startsWith('/')) {
            throw ZipSecurityException(CODE_ABSOLUTE_PATH, "ZIP 包含绝对路径条目：$name")
        }
        if (name.contains('\\')) {
            throw ZipSecurityException(CODE_BACKSLASH, "ZIP 包含反斜杠条目：$name")
        }
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
            throw ZipSecurityException(CODE_ABSOLUTE_PATH, "ZIP 包含盘符条目：$name")
        }

        val withoutTrailingSlash = if (name.endsWith("/")) name.dropLast(1) else name
        if (withoutTrailingSlash.isEmpty()) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称为空")
        }
        val segments = withoutTrailingSlash.split('/')
        if (segments.any { it.isEmpty() }) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称包含空路径段：$name")
        }
        if (segments.any { it == "." }) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目名称包含 . 路径段：$name")
        }
        if (segments.any { it == ".." }) {
            throw ZipSecurityException(CODE_PATH_TRAVERSAL, "ZIP 条目路径穿越：$name")
        }
        return name
    }

    private fun findEocd(raf: RandomAccessFile, length: Long): Long {
        val start = (length - 22L - MAX_COMMENT_LENGTH).coerceAtLeast(0L)
        var position = length - 22L
        while (position >= start) {
            raf.seek(position)
            if (raf.readU32() == EOCD_SIGNATURE) {
                raf.seek(position + 20L)
                val commentLength = raf.readU16()
                if (position + 22L + commentLength <= length) {
                    return position
                }
            }
            position--
        }
        throw ZipSecurityException(CODE_INVALID, "找不到 ZIP 结束记录")
    }

    private fun verifyLocalHeader(
        raf: RandomAccessFile,
        localOffset: Long,
        centralMethod: Int,
        centralFlags: Int,
        centralName: ByteArray,
        compressedSize: Long,
        centralOffset: Long
    ) {
        if (localOffset >= centralOffset) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地文件头偏移越界")
        }
        raf.seek(localOffset)
        if (raf.readU32() != LOCAL_SIGNATURE) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地文件头签名非法")
        }
        raf.readU16() // version needed
        val localFlags = raf.readU16()
        val localMethod = raf.readU16()
        raf.readU16() // mod time
        raf.readU16() // mod date
        raf.readU32() // crc
        raf.readU32() // compressed size（可能为 data descriptor 占位）
        raf.readU32() // uncompressed size
        val localNameLength = raf.readU16()
        val localExtraLength = raf.readU16()

        if (localMethod != centralMethod) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地头与中央目录压缩方法不一致")
        }
        if ((localFlags and 0x1) != (centralFlags and 0x1) ||
            (localFlags and 0x800) != (centralFlags and 0x800)
        ) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地头与中央目录标志位不一致")
        }
        if (localNameLength != centralName.size) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地头与中央目录名称长度不一致")
        }
        val localName = ByteArray(localNameLength)
        raf.readFully(localName)
        if (!localName.contentEquals(centralName)) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 本地头与中央目录名称不一致")
        }
        val dataStart = localOffset + 30L + localNameLength + localExtraLength
        if (dataStart > centralOffset || compressedSize > centralOffset - dataStart) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 条目数据越界")
        }
    }

    private fun RandomAccessFile.readU16(): Int {
        val low = read()
        val high = read()
        if (low < 0 || high < 0) {
            throw ZipSecurityException(CODE_INVALID, "ZIP 文件意外结束")
        }
        return low or (high shl 8)
    }

    private fun RandomAccessFile.readU32(): Long {
        var value = 0L
        for (index in 0 until 4) {
            val byte = read()
            if (byte < 0) {
                throw ZipSecurityException(CODE_INVALID, "ZIP 文件意外结束")
            }
            value = value or (byte.toLong() shl (index * 8))
        }
        return value
    }
}
