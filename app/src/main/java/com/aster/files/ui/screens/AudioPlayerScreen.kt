package com.aster.files.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.aster.files.data.FileEntry
import com.aster.files.playback.AudioPlaybackService
import com.aster.files.ui.components.rememberAudioCover
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

@Composable
fun AudioPlayerScreen(
    entry: FileEntry?,
    queue: List<FileEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0L) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var currentTitle by remember { mutableStateOf(entry?.name.orEmpty()) }
    var currentAudioPath by remember { mutableStateOf(entry?.path) }
    var playlist by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPlaylist by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(
            context,
            ComponentName(context, AudioPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying
                duration = player.duration.coerceAtLeast(0L)
                playbackSpeed = player.playbackParameters.speed
                currentTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
                    .orEmpty()
                    .ifBlank { entry?.name.orEmpty() }
                currentAudioPath = player.currentMediaItem?.mediaMetadata?.extras
                    ?.getString("audio_path")
                    ?: player.currentMediaItem?.localConfiguration?.uri?.path
                    ?: currentAudioPath
                playlist = (0 until player.mediaItemCount).map { index ->
                    player.getMediaItemAt(index).mediaMetadata.title?.toString()
                        ?: ("音频 " + (index + 1))
                }
                if (!dragging) {
                    position = player.currentPosition.coerceAtLeast(0L)
                }
            }
        }
        future.addListener({
            try {
                val connected = future.get()
                connected.addListener(listener)
                controller = connected
                playing = connected.isPlaying
                duration = connected.duration.coerceAtLeast(0L)
                position = connected.currentPosition.coerceAtLeast(0L)
                playbackSpeed = connected.playbackParameters.speed
                currentTitle = connected.currentMediaItem?.mediaMetadata?.title?.toString()
                    .orEmpty()
                    .ifBlank { entry?.name.orEmpty() }
                currentAudioPath = connected.currentMediaItem?.mediaMetadata?.extras
                    ?.getString("audio_path")
                    ?: connected.currentMediaItem?.localConfiguration?.uri?.path
                    ?: currentAudioPath
                playlist = (0 until connected.mediaItemCount).map { index ->
                    connected.getMediaItemAt(index).mediaMetadata.title?.toString()
                        ?: ("音频 " + (index + 1))
                }
            } catch (e: Exception) {
                error = e.message ?: "无法连接音频播放服务"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            controller?.removeListener(listener)
            controller?.release()
            MediaController.releaseFuture(future)
        }
    }

    val artwork = rememberAudioCover(currentAudioPath)

    val orderedQueue = remember(entry?.path, queue) {
        if (entry == null) {
            emptyList()
        } else {
            val list = queue.filterNot { it.isDirectory }.toMutableList()
            if (list.none { it.path == entry.path }) list.add(0, entry)
            list
        }
    }

    LaunchedEffect(controller) {
        val active = controller ?: return@LaunchedEffect
        if (entry == null || orderedQueue.isEmpty()) {
            // 从通知栏/迷你播放器进入时，复用服务中已有的队列。
            return@LaunchedEffect
        }
        active.pause()
        val items = orderedQueue.map { item ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(item.path)))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setArtist("Aster Files")
                        .setExtras(Bundle().apply { putString("audio_path", item.path) })
                        .build()
                )
                .build()
        }
        val index = orderedQueue.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        active.setMediaItems(items, index, 0L)
        active.prepare()
        active.play()
    }

    LaunchedEffect(controller) {
        while (isActive) {
            val active = controller
            if (active != null && !dragging) {
                playing = active.isPlaying
                duration = active.duration.coerceAtLeast(0L)
                position = active.currentPosition.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    BackHandler { onBack() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "音频播放",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(String.format(Locale.ROOT, "%.1fx", playbackSpeed))
                    }
                    DropdownMenu(
                        expanded = speedMenu,
                        onDismissRequest = { speedMenu = false }
                    ) {
                        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(String.format(Locale.ROOT, "%.1fx", speed))
                                },
                                onClick = {
                                    controller?.setPlaybackSpeed(speed)
                                    playbackSpeed = speed
                                    speedMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { showPlaylist = true }) {
                    Icon(Icons.Outlined.QueueMusic, contentDescription = "播放列表")
                }
                IconButton(
                    onClick = {
                        controller?.stop()
                        controller?.clearMediaItems()
                        context.stopService(
                            Intent(context, AudioPlaybackService::class.java)
                        )
                        onBack()
                    }
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "停止播放")
                }
            }
            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.size(240.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (artwork != null) {
                        AsyncImage(
                            model = artwork,
                            contentDescription = "专辑封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Outlined.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                currentTitle.ifBlank { entry?.name.orEmpty() },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "退出页面后继续播放 · 通知栏可控制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            Slider(
                value = (if (dragging) dragPosition else position).toFloat(),
                onValueChange = {
                    dragging = true
                    dragPosition = it.toLong()
                },
                onValueChangeFinished = {
                    controller?.seekTo(dragPosition)
                    position = dragPosition
                    dragging = false
                },
                valueRange = 0f..(duration.coerceAtLeast(1L)).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatAudioTime(if (dragging) dragPosition else position))
                Text(formatAudioTime(duration))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                IconButton(
                    onClick = { controller?.seekToPreviousMediaItem() },
                    enabled = controller?.hasPreviousMediaItem() == true
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首")
                }
                FilledIconButton(
                    onClick = {
                        val active = controller ?: return@FilledIconButton
                        if (active.isPlaying) active.pause() else active.play()
                    },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(38.dp)
                    )
                }
                IconButton(
                    onClick = { controller?.seekToNextMediaItem() },
                    enabled = controller?.hasNextMediaItem() == true
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一首")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPlaylist) {
        AlertDialog(
            onDismissRequest = { showPlaylist = false },
            title = { Text("播放列表") },
            text = {
                val titles = playlist.ifEmpty {
                    orderedQueue.map { it.name }
                }
                if (titles.isEmpty()) {
                    Text("当前没有可播放的音频")
                } else {
                    LazyColumn {
                        itemsIndexed(titles) { index, title ->
                            val selected = controller?.currentMediaItemIndex == index
                            Text(
                                (index + 1).toString() + ". " + title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        controller?.seekTo(index, 0L)
                                        controller?.play()
                                        showPlaylist = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylist = false }) { Text("关闭") }
            }
        )
    }
}

private fun formatAudioTime(value: Long): String {
    if (value <= 0L) return "00:00"
    val totalSeconds = value / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hours = minutes / 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60L, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
