package com.aster.files.services

/** 图片批量转换的纯命名/质量规划，便于 JVM 测试。 */
object ConversionPlanner {
    fun outputName(sourceName: String, format: ImageOutputFormat, index: Int = 0): String {
        val base = sourceName.substringBeforeLast('.', sourceName) + "_converted"
        val suffix = if (index == 0) "" else " ($index)"
        return base + suffix + "." + format.extension
    }

    fun normalizedQuality(quality: Int): Int = quality.coerceIn(0, 100)

    fun supportedExtensions(): Set<String> = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
}
