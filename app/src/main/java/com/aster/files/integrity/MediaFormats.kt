package com.aster.files.integrity

import com.aster.files.data.MediaType
import java.io.File
import java.util.Locale

/** 完整性分析支持的媒体格式（需求 9.2）。 */
object MediaFormats {

    private val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff"
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2",
        "mpg", "mpeg", "ts", "m2ts", "mts", "wmv", "flv"
    )

    fun extensionOf(name: String): String {
        val index = name.lastIndexOf('.')
        if (index <= 0 || index == name.length - 1) return ""
        return name.substring(index + 1).lowercase(Locale.ROOT)
    }

    fun mediaTypeFor(name: String): MediaType? = when (extensionOf(name)) {
        in PHOTO_EXTENSIONS -> MediaType.PHOTO
        in VIDEO_EXTENSIONS -> MediaType.VIDEO
        else -> null
    }

    fun mediaTypeFor(file: File): MediaType? = mediaTypeFor(file.name)

    fun isSupported(name: String): Boolean = mediaTypeFor(name) != null
}
