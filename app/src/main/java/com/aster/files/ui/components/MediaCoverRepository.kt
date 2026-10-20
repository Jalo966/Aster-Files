package com.aster.files.ui.components

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aster.files.data.FileEntry
import com.aster.files.data.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 本地媒体封面：
 * - 视频优先使用同名的 jpg/jpeg/png/webp；
 * - 音频使用 ID3/FLAC 内嵌图片；
 * - 音频封面写入 cacheDir/media_covers，交给 Coil 加载。
 * 仅本地解码，不访问网络。
 */
object MediaCoverRepository {
    private val lock = Any()
    private val cache = HashMap<String, String>()
    private const val NONE = ""
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    fun coverFile(context: Context, entry: FileEntry): File? {
        if (entry.isDirectory) return null
        if (entry.kind != FileKind.AUDIO && entry.kind != FileKind.VIDEO) return null
        val key = entry.kind.name + "|" + entry.path + "|" + entry.modifiedAt
        synchronized(lock) {
            cache[key]?.let { cached ->
                if (cached == NONE) return null
                val file = File(cached)
                if (file.exists()) return file
            }
        }
        val result = runCatching {
            when (entry.kind) {
                FileKind.VIDEO -> siblingPoster(File(entry.path))
                FileKind.AUDIO -> embeddedAudioCover(context, File(entry.path))
                else -> null
            }
        }.getOrNull()
        synchronized(lock) {
            cache[key] = result?.absolutePath ?: NONE
        }
        return result
    }

    fun audioCoverFile(context: Context, path: String): File? {
        val file = File(path)
        if (!file.isFile) return null
        val key = "AUDIO|" + file.absolutePath + "|" + file.lastModified()
        synchronized(lock) {
            cache[key]?.let { cached ->
                if (cached == NONE) return null
                val cachedFile = File(cached)
                if (cachedFile.exists()) return cachedFile
            }
        }
        val result = runCatching { embeddedAudioCover(context, file) }.getOrNull()
        synchronized(lock) {
            cache[key] = result?.absolutePath ?: NONE
        }
        return result
    }

    private fun siblingPoster(video: File): File? {
        val parent = video.parentFile ?: return null
        val base = video.nameWithoutExtension
        imageExtensions.forEach { extension ->
            listOf(extension, extension.uppercase(Locale.ROOT)).forEach { ext ->
                val candidate = File(parent, "$base.$ext")
                if (candidate.isFile) return candidate
            }
        }
        return null
    }

    private fun embeddedAudioCover(context: Context, audio: File): File? {
        if (!audio.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audio.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            val directory = File(context.cacheDir, "media_covers").apply { mkdirs() }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((audio.absolutePath + "|" + audio.lastModified()).toByteArray())
            val name = digest.joinToString("") { "%02x".format(it) } + ".cover"
            val file = File(directory, name)
            if (!file.exists() || file.length() == 0L) {
                file.writeBytes(bytes)
            }
            file
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable
fun rememberMediaCover(entry: FileEntry): File? {
    val context = LocalContext.current
    var cover by remember(entry.path, entry.modifiedAt) { mutableStateOf<File?>(null) }
    LaunchedEffect(entry.path, entry.modifiedAt) {
        cover = if (entry.kind == FileKind.AUDIO || entry.kind == FileKind.VIDEO) {
            withContext(Dispatchers.IO) { MediaCoverRepository.coverFile(context, entry) }
        } else {
            null
        }
    }
    return cover
}

@Composable
fun rememberAudioCover(path: String?): File? {
    val context = LocalContext.current
    var cover by remember(path) { mutableStateOf<File?>(null) }
    LaunchedEffect(path) {
        cover = path?.let {
            withContext(Dispatchers.IO) { MediaCoverRepository.audioCoverFile(context, it) }
        }
    }
    return cover
}
