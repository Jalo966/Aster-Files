package com.aster.files.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.ui.graphics.vector.ImageVector
import com.aster.files.data.FileCategory

fun categoryLabel(category: FileCategory): String = when (category) {
    FileCategory.IMAGE -> "图片"
    FileCategory.VIDEO -> "视频"
    FileCategory.AUDIO -> "音频"
    FileCategory.DOCUMENT -> "文档"
    FileCategory.APK -> "安装包"
    FileCategory.ARCHIVE -> "压缩包"
}

fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGE -> Icons.Outlined.Image
    FileCategory.VIDEO -> Icons.Outlined.Movie
    FileCategory.AUDIO -> Icons.Outlined.MusicNote
    FileCategory.DOCUMENT -> Icons.Outlined.Description
    FileCategory.APK -> Icons.Outlined.PhoneAndroid
    FileCategory.ARCHIVE -> Icons.Outlined.Archive
}
