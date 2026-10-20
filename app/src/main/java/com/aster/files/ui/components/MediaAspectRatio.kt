package com.aster.files.ui.components

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 读取图片/视频真实宽高比；失败时回退为 1:1。 */
@Composable
fun rememberMediaAspectRatio(entry: FileEntry): Float {
    val ratio by produceState(
        initialValue = 1f,
        key1 = entry.path,
        key2 = entry.modifiedAt
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { readAspectRatio(entry) }.getOrDefault(1f)
        }.coerceIn(0.35f, 3.0f)
    }
    return ratio
}

private fun readAspectRatio(entry: FileEntry): Float {
    if (entry.isDirectory) return 1f
    val file = File(entry.path)
    if (!file.isFile) return 1f
    return when (entry.kind) {
        FileKind.IMAGE -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(entry.path, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
        }
        FileKind.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(entry.path)
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (width <= 0 || height <= 0) {
                    1f
                } else if (rotation == 90 || rotation == 270) {
                    height.toFloat() / width.toFloat()
                } else {
                    width.toFloat() / height.toFloat()
                }
            } finally {
                retriever.release()
            }
        }
        else -> 1f
    }
}
