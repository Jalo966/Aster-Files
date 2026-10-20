package com.aster.files.data

/** 分类页的六类文件；与 FileKind 分开，避免影响既有浏览语义。 */
enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    APK,
    ARCHIVE
}
