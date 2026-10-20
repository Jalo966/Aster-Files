package com.aster.files.services

enum class ZipFormat(val label: String, val supported: Boolean) {
    ZIP("ZIP", true),
    SEVEN_Z("7Z", false),
    TAR_GZ("TAR.GZ", false)
}

enum class ZipAlgorithm(val label: String, val supported: Boolean) {
    DEFLATE("Deflate", true),
    BZIP2("Bzip2", false)
}

enum class ZipEncryptionMode(val label: String) {
    ZIP_STANDARD("ZIP 标准"),
    AES_128("AES-128"),
    AES_256("AES-256")
}

enum class ZipSizeUnit(val label: String, val multiplier: Long) {
    MB("MB", 1024L * 1024L),
    GB("GB", 1024L * 1024L * 1024L);

    fun toBytes(value: Long): Long = Math.multiplyExact(value, multiplier)

    companion object {
        /** 100 GB，避免分卷参数溢出和异常巨大的单卷。 */
        const val MAX_SPLIT_BYTES: Long = 100L * 1024L * 1024L * 1024L
    }
}

data class ZipOptions(
    val compressionLevel: Int = 5,
    val password: String? = null,
    val encryption: ZipEncryptionMode = ZipEncryptionMode.ZIP_STANDARD,
    val splitSizeBytes: Long = 0L,
    val deleteSources: Boolean = false,
    val format: ZipFormat = ZipFormat.ZIP,
    val algorithm: ZipAlgorithm = ZipAlgorithm.DEFLATE
) {
    init {
        require(compressionLevel in setOf(0, 1, 5, 9)) { "不支持的压缩级别" }
        require(splitSizeBytes >= 0L) { "分卷大小不能为负数" }
        require(splitSizeBytes <= ZipSizeUnit.MAX_SPLIT_BYTES) { "分卷大小超出上限" }
    }
}

data class ZipArchiveInfo(
    val encrypted: Boolean,
    val entryCount: Int,
    val split: Boolean
)
