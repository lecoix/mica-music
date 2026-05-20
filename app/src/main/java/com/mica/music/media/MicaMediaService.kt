package com.mica.music.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 播放服务：拥有 ExoPlayer + MediaSession，独立于 Activity 生命周期。
 *
 * 职责：
 * - 唯一持有 ExoPlayer 实例（避免多个 Activity 时音频抢占）
 * - 通过 MediaSession 对接系统：通知栏 / 锁屏 / 蓝牙耳机 / Auto / Wear
 * - 由 Media3 自动生成媒体通知（无需手写 NotificationChannel）
 *
 * 生命周期：
 * - 由 MediaController.buildAsync() 自动启动（无需 Activity 显式 startService）
 * - onTaskRemoved：用户滑掉任务栈时，仅当未在播放时停止服务（在播放则继续）
 */
class MicaMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var compositePlayer: MicaCompositePlayer? = null

    override fun onCreate() {
        super.onCreate()
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                // 与 Media3 官方 Session Demo 对齐：Android 13+ 未授予 POST_NOTIFICATIONS 时，
                // 强行 pauseAllPlayersAndStopSelf() 容易与 Activity 侧 MediaController 断开时机冲突，导致进程退出。
                // 此时不做激进收尾；用户授权通知后前台服务即可正常建立。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
        })
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
        }
        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val player = MicaCompositePlayer(exoPlayer)
        compositePlayer = player
        AlacPlaybackCoordinator.compositePlayer = player
        mediaSession = MediaSession.Builder(this, player).build()
        AlacPlaybackCoordinator.engine = AlacAudioTrackEngine(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = compositePlayer ?: mediaSession?.player ?: return
        val alacSession = (player as? MicaCompositePlayer)?.alacState
        val alacActive = alacSession?.playWhenReady == true
        // 用户滑掉任务栈：仅在以下情况停止服务
        // - 未在播放（playWhenReady=false，即暂停状态）
        // - 队列为空
        // - 已播完（STATE_ENDED）
        // 正在播放则保留服务，让用户通过通知栏继续控制
        if ((!player.playWhenReady && !alacActive) ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AlacPlaybackCoordinator.engine?.release()
        AlacPlaybackCoordinator.engine = null
        AlacPlaybackCoordinator.compositePlayer = null
        AlacPlaybackCoordinator.sessionHandler = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        compositePlayer = null
        clearListener()
        super.onDestroy()
    }
}
