package com.aster.files.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import java.io.File

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "apng"
)

/**
 * 文件列表/网格缩略图。
 *
 * 图片交给 Coil 按目标 48/96dp 采样；视频通过 coil-video 的 VideoFrameDecoder
 * 取首帧。请求显式开启内存/磁盘缓存，失败回退到原文件类型图标。
 */
@Composable
fun FileThumbnail(
    entry: FileEntry,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = fileKindIcon(entry.kind),
    contentScale: ContentScale = ContentScale.Fit,
    aspectRatio: Float? = null
) {
    val isImage = entry.kind == FileKind.IMAGE
    val isVideo = entry.kind == FileKind.VIDEO
    val isAudio = entry.kind == FileKind.AUDIO
    val cover = rememberMediaCover(entry)
    var folderImages by remember(entry.path, entry.modifiedAt) {
        mutableStateOf<List<File>>(emptyList())
    }
    LaunchedEffect(entry.path, entry.modifiedAt, entry.isDirectory) {
        if (entry.isDirectory) {
            folderImages = withContext(Dispatchers.IO) {
                File(entry.path).listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                    ?.sortedBy { it.name.lowercase() }
                    ?.take(4)
                    .orEmpty()
            }
        }
    }
    if (entry.isDirectory && folderImages.isNotEmpty()) {
        FolderCollage(
            entry = entry,
            images = folderImages,
            size = size,
            modifier = modifier,
            aspectRatio = aspectRatio
        )
        return
    }
    if (!isImage && !isVideo && !(isAudio && cover != null)) {
        ThumbnailFallback(
            entry = entry,
            icon = fallbackIcon,
            size = size,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val data = when {
        cover != null -> cover
        else -> File(entry.path)
    }
    val request = remember(entry.path, entry.modifiedAt, isVideo, size, sizePx, cover?.absolutePath) {
        ImageRequest.Builder(context)
            .data(data)
            .size(sizePx)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // 视频默认跳过 2 秒；避免大量视频首帧纯黑导致缩略图不可用。
            .apply { if (isVideo && cover == null) videoFrameMillis(2_000) }
            .build()
    }

    val shapeModifier = if (aspectRatio != null) {
        Modifier.fillMaxWidth().aspectRatio(aspectRatio)
    } else {
        Modifier.size(size)
    }
    Box(
        modifier = modifier
            .then(shapeModifier)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = entry.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            loading = {
                ThumbnailFallback(
                    entry = entry,
                    icon = fallbackIcon,
                    size = size,
                    modifier = Modifier.fillMaxSize()
                )
            },
            error = {
                ThumbnailFallback(
                    entry = entry,
                    icon = fallbackIcon,
                    size = size,
                    modifier = Modifier.fillMaxSize()
                )
            }
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(size * 0.38f)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize(0.72f)
                )
            }
        }
    }
}

@Composable
private fun FolderCollage(
    entry: FileEntry,
    images: List<File>,
    size: Dp,
    modifier: Modifier,
    aspectRatio: Float?
) {
    val cells = (images + images).take(4)
    val shape = if (aspectRatio != null) {
        Modifier.fillMaxWidth().aspectRatio(aspectRatio)
    } else {
        Modifier.size(size)
    }
    val collageSizePx = with(LocalDensity.current) { (size / 2).roundToPx() }
    Box(
        modifier = modifier
            .then(shape)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until 2) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0 until 2) {
                        val image = cells.getOrNull(row * 2 + column) ?: cells.first()
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(image)
                                .size(collageSizePx)
                                .crossfade(false)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = entry.name,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                        )
                    }
                }
                if (row == 0) Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun ThumbnailFallback(
    entry: FileEntry,
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(if (size >= 80.dp) 40.dp else 26.dp)
        )
    }
}
