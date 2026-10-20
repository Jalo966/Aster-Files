package com.aster.files.integrity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.aster.files.data.MediaType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android L3 真实解码实现。
 *
 * 图片使用 BitmapFactory 实际解码（限制采样尺寸）；
 * 视频使用 MediaExtractor + MediaCodec + SurfaceTexture 解码到离屏 Surface，
 * 必须读到 EOS 且至少解出一帧。
 *
 * 注意：该实现依赖设备/模拟器的 MediaCodec，当前环境没有模拟器，
 * 仅完成编译与接口接入；需在真机或模拟器上验收。
 * JVM 单元测试注入 Mock DecodeVerifier，不加载本类。
 */
class AndroidMediaDecodeVerifier : DecodeVerifier {

    override fun verify(file: File): Boolean = when (MediaFormats.mediaTypeFor(file.name)) {
        MediaType.PHOTO -> verifyImage(file)
        MediaType.VIDEO -> verifyVideo(file)
        null -> false
    }

    private fun verifyImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw DecodeVerificationException("图片尺寸解析失败")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw DecodeVerificationException("图片解码失败")
        return try {
            bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= MAX_DECODE_DIMENSION ||
            height / (sampleSize * 2) >= MAX_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun verifyVideo(file: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null

        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = index
                    videoFormat = format
                    break
                }
            }
            if (videoTrack < 0 || videoFormat == null) {
                throw DecodeVerificationException("视频没有可解码的视频轨")
            }
            extractor.selectTrack(videoTrack)

            surfaceTexture = SurfaceTexture(0)
            surface = Surface(surfaceTexture)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                ?: throw DecodeVerificationException("视频轨缺少 MIME")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(videoFormat, surface, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedFrames = 0
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_DECODE_SECONDS)

            while (!outputDone) {
                if (System.nanoTime() > deadlineNanos) {
                    throw DecodeVerificationException("视频解码超时")
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) decodedFrames++
                        val endOfStream =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, true)
                        if (endOfStream) outputDone = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            if (decodedFrames == 0) {
                throw DecodeVerificationException("视频未解出任何帧")
            }
            return true
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // 未启动或已释放时忽略。
            }
            try {
                codec?.release()
            } catch (_: Exception) {
                // 忽略释放异常。
            }
            try {
                surface?.release()
            } catch (_: Exception) {
                // 忽略释放异常。
            }
            try {
                surfaceTexture?.release()
            } catch (_: Exception) {
                // 忽略释放异常。
            }
            try {
                extractor.release()
            } catch (_: Exception) {
                // 忽略释放异常。
            }
        }
    }

    companion object {
        private const val MAX_DECODE_DIMENSION = 1024
        private const val MAX_DECODE_SECONDS = 120L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
