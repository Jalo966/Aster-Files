package com.aster.files.ui.components

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.nio.ByteBuffer

/**
 * 全屏 Coil 图片查看器，支持双指缩放与双击复位。
 *
 * 图片解码尺寸由 Coil 按目标 View 约束自动采样，不再手动 BitmapFactory
 * 解码/回收，避免大图 OOM 和过早 recycle 导致的闪退。
 */
@Composable
fun ImageViewerDialog(
    name: String,
    model: Any?,
    onDismiss: () -> Unit,
    apngPath: String? = null,
    apngBytes: ByteArray? = null,
    onExtractFrames: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var loading by remember { mutableStateOf(true) }
            var failed by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val request = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(false)
                    .build()
            }
            val apngDrawable = remember(apngPath, apngBytes) {
                runCatching {
                    when {
                        apngPath != null -> APNGDrawable.fromFile(apngPath)
                        apngBytes != null -> APNGDrawable(object : ByteBufferLoader() {
                            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(apngBytes)
                        })
                        else -> null
                    }
                }.getOrNull()
            }
            DisposableEffect(apngDrawable) {
                apngDrawable?.start()
                onDispose { apngDrawable?.stop() }
            }
            LaunchedEffect(apngDrawable) {
                if (apngDrawable != null) {
                    loading = false
                    failed = false
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 6f)
                                scale = nextScale
                                offset = if (nextScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    offset + pan
                                }
                            }
                        }
                ) {
                    if (apngDrawable != null) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    setImageDrawable(apngDrawable)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    } else {
                        AsyncImage(
                            model = request,
                            contentDescription = name,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentScale = ContentScale.Fit,
                            onLoading = {
                                loading = true
                                failed = false
                            },
                            onSuccess = {
                                loading = false
                                failed = false
                            },
                            onError = {
                                loading = false
                                failed = true
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Text(
                        text = name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "双指缩放",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                if (onExtractFrames != null) {
                    Button(
                        onClick = onExtractFrames,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        Text("抽帧")
                    }
                }

                if (loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
                if (failed) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("图片加载失败", color = Color.White)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "文件可能损坏或格式不受支持",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
