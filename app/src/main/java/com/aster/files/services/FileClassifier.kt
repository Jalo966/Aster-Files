package com.aster.files.services

import com.aster.files.data.FileCategory
import com.aster.files.data.FileKind
import java.util.Locale

/** 文件扩展名分类的共享实现，供浏览与常规分析保持一致。 */
internal object FileClassifier {

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "heic", "heif"
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2",
        "mpg", "mpeg", "ts", "m2ts", "mts", "wmv", "flv"
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "amr"
    )
    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
        "rtf", "csv", "epub"
    )
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz"
    )
    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h",
        "cpp", "cc", "cs", "go", "rs", "rb", "php", "sh", "sql", "html",
        "css", "xml", "json", "yaml", "yml", "gradle", "properties"
    )
    private val TEXT_EXTENSIONS = DOCUMENT_EXTENSIONS + CODE_EXTENSIONS + setOf(
        "log", "ini", "conf", "cfg", "toml", "bat", "ps1"
    )

    fun extensionOf(name: String): String {
        val index = name.lastIndexOf('.')
        if (index <= 0 || index == name.length - 1) return ""
        return name.substring(index + 1).lowercase(Locale.ROOT)
    }

    fun isImage(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS

    fun isTextPreviewable(name: String): Boolean {
        val extension = extensionOf(name)
        return extension in TEXT_EXTENSIONS
    }

    fun categoryOf(name: String): FileCategory? {
        val extension = extensionOf(name)
        return when {
            extension in IMAGE_EXTENSIONS -> FileCategory.IMAGE
            extension in VIDEO_EXTENSIONS -> FileCategory.VIDEO
            extension in AUDIO_EXTENSIONS -> FileCategory.AUDIO
            extension == "apk" -> FileCategory.APK
            extension in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
            extension in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
            else -> null
        }
    }

    fun kindOf(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return when (extensionOf(name)) {
            in IMAGE_EXTENSIONS -> FileKind.IMAGE
            in VIDEO_EXTENSIONS -> FileKind.VIDEO
            in AUDIO_EXTENSIONS -> FileKind.AUDIO
            in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
            in CODE_EXTENSIONS -> FileKind.CODE
            in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
            else -> FileKind.OTHER
        }
    }
}
