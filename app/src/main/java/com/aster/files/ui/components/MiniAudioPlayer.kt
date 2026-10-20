package com.aster.files.ui.components

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.aster.files.playback.AudioPlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

@Composable
fun MiniAudioPlayer(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf<String?>(null) }
    val cover = rememberAudioCover(currentPath)

    DisposableEffect(Unit) {
        val token = SessionToken(
            context,
            ComponentName(context, AudioPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                currentPath = player.currentMediaItem?.mediaMetadata?.extras
                    ?.getString("audio_path")
                    ?: player.currentMediaItem?.localConfiguration?.uri?.path
                playing = player.isPlaying
                active = player.currentMediaItem != null &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED
            }
        }
        future.addListener({
            runCatching {
                val connected = future.get()
                connected.addListener(listener)
                controller = connected
                title = connected.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                currentPath = connected.currentMediaItem?.mediaMetadata?.extras
                    ?.getString("audio_path")
                    ?: connected.currentMediaItem?.localConfiguration?.uri?.path
                playing = connected.isPlaying
                active = connected.currentMediaItem != null &&
                    connected.playbackState != Player.STATE_IDLE &&
                    connected.playbackState != Player.STATE_ENDED
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.removeListener(listener)
            controller?.release()
            MediaController.releaseFuture(future)
        }
    }

    if (!active || controller == null) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "正在播放" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "点击打开播放器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    val player = controller ?: return@IconButton
                    if (player.isPlaying) player.pause() else player.play()
                }
            ) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放"
                )
            }
            IconButton(
                onClick = {
                    val player = controller
                    player?.stop()
                    player?.clearMediaItems()
                    active = false
                    playing = false
                    controller = null
                    player?.release()
                    context.stopService(
                        Intent(context, AudioPlaybackService::class.java)
                    )
                }
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "停止播放")
            }
        }
    }
}
