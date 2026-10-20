package com.aster.files.playback

import android.app.PendingIntent
import android.content.Intent
import com.aster.files.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 前台媒体会话服务：持有 ExoPlayer 与 MediaSession。
 *
 * MediaSessionService 自动提供媒体通知与锁屏控制；用户离开页面或锁屏时，
 * 正在播放的 ExoPlayer 由前台服务继续持有。
 */
class AudioPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer
        val sessionActivity = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_AUDIO_PLAYER
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null ||
            !current.playWhenReady ||
            current.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_AUDIO_PLAYER = "com.aster.files.action.OPEN_AUDIO_PLAYER"
        private const val REQUEST_OPEN_PLAYER = 4101
    }
}
