package com.aster.files.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aster.files.data.FileEntry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    entry: FileEntry,
    onBack: () -> Unit,
    networkUri: String? = null,
    networkHeaders: Map<String, String> = emptyMap(),
    dataSourceFactory: DataSource.Factory? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(entry.path, networkUri, networkHeaders, dataSourceFactory) {
        val builder = when {
            dataSourceFactory != null -> ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            networkUri != null -> {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(networkHeaders)
                    .setAllowCrossProtocolRedirects(true)
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            }
            else -> ExoPlayer.Builder(context)
        }
        builder.build().apply {
            setMediaItem(
                if (networkUri != null) {
                    MediaItem.fromUri(Uri.parse(networkUri))
                } else {
                    MediaItem.fromUri(Uri.fromFile(File(entry.path)))
                }
            )
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    val inPip = VideoPipState.inPip
    DisposableEffect(Unit) {
        VideoPipState.screenOpen = true
        onDispose {
            VideoPipState.screenOpen = false
            VideoPipState.playing = false
        }
    }
    LaunchedEffect(isPlaying) {
        VideoPipState.playing = isPlaying
    }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }
    var speedMenu by remember { mutableStateOf(false) }
    var isLandscape by remember {
        mutableStateOf(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }
    var dragTargetMs by remember { mutableStateOf<Long?>(null) }
    var dragStartMs by remember { mutableStateOf(0L) }
    var dragDistance by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    fun replay() {
        ended = false
        player.seekTo(0)
        player.play()
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            activity?.let { hideSystemBars(it) }
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.let { showSystemBars(it) }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended = true
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                    error = null
                }
            }

            override fun onPlayerError(playerError: androidx.media3.common.PlaybackException) {
                error = playerError.message ?: "视频无法播放"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let { showSystemBars(it) }
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(configuration.orientation) {
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    LaunchedEffect(player) {
        while (true) {
            if (dragTargetMs == null) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            val duration = player.duration
            if (duration > 0) durationMs = duration
            delay(250)
        }
    }

    LaunchedEffect(dragTargetMs) {
        if (dragTargetMs != null) {
            delay(900)
            dragTargetMs = null
        }
    }

    BackHandler { onBack() }

    val displayPosition = dragTargetMs ?: positionMs
    val maxDuration = durationMs.coerceAtLeast(1L)
    val hudText = dragTargetMs?.let { target ->
        (if (target >= dragStartMs) "快进至 " else "快退至 ") + formatPlayerTime(target)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    keepScreenOn = true
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        // 画面手势区：水平滑动根据总时长按像素比例 seek。
        if (!inPip) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = {
                            dragStartMs = player.currentPosition.coerceAtLeast(0L)
                            dragDistance = 0f
                            dragTargetMs = dragStartMs
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDistance += dragAmount.x
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val deltaMs = (dragDistance / width * durationMs).toLong()
                            val target = (dragStartMs + deltaMs)
                                .coerceIn(0L, durationMs.coerceAtLeast(0L))
                            dragTargetMs = target
                            player.seekTo(target)
                        },
                        onDragEnd = {
                            dragTargetMs?.let { player.seekTo(it) }
                        },
                        onDragCancel = { dragTargetMs = null }
                    )
                }
        )
        }

        if (!inPip) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = entry.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        }

        if (!inPip && hudText != null) {
            Text(
                text = hudText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        error?.let { message ->
            Text(
                text = message,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(16.dp)
            )
        }

        if (!inPip && ended && dragTargetMs == null) {
            Button(
                onClick = ::replay,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("重播")
            }
        }

        if (inPip) {
            IconButton(
                onClick = {
                    if (ended) replay() else if (isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
            ) {
                Icon(
                    if (isPlaying && !ended) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying && !ended) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        } else {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatPlayerTime(displayPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    formatPlayerTime(durationMs),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Slider(
                value = displayPosition.coerceIn(0L, maxDuration).toFloat(),
                onValueChange = { value ->
                    dragTargetMs = value.toLong()
                    player.seekTo(value.toLong())
                },
                onValueChangeFinished = { },
                valueRange = 0f..maxDuration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        val target = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                        player.seekTo(target)
                        positionMs = target
                    }
                ) {
                    Icon(
                        Icons.Outlined.FastRewind,
                        contentDescription = "快退 10 秒",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = {
                        if (ended) replay() else if (isPlaying) player.pause() else player.play()
                    }
                ) {
                    Icon(
                        if (isPlaying && !ended) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying && !ended) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val target = (player.currentPosition + 10_000L)
                            .coerceAtMost(durationMs.coerceAtLeast(0L))
                        player.seekTo(target)
                        positionMs = target
                    }
                ) {
                    Icon(
                        Icons.Outlined.FastForward,
                        contentDescription = "快进 10 秒",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(
                            formatSpeed(speed),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenu,
                        onDismissRequest = { speedMenu = false }
                    ) {
                        SPEEDS.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        formatSpeed(option) +
                                            if (option == 1.0f) "（正常）" else ""
                                    )
                                },
                                onClick = {
                                    speed = option
                                    player.playbackParameters = PlaybackParameters(option)
                                    speedMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = ::toggleOrientation) {
                    Icon(
                        if (isLandscape) Icons.Outlined.FullscreenExit
                        else Icons.Outlined.Fullscreen,
                        contentDescription = if (isLandscape) "退出横屏" else "横屏全屏",
                        tint = Color.White
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                    IconButton(
                        onClick = {
                            runCatching {
                                VideoPipState.params()?.let {
                                    activity.enterPictureInPictureMode(it)
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.PictureInPictureAlt,
                            contentDescription = "画中画",
                            tint = Color.White
                        )
                    }
                }
            }
        }
        }
    }
}

private fun hideSystemBars(activity: Activity) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private fun showSystemBars(activity: Activity) {
    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
    WindowCompat.setDecorFitsSystemWindows(activity.window, true)
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString() + ".0x"
    } else {
        speed.toString() + "x"
    }
