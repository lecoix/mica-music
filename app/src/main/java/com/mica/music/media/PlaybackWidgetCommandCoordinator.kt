package com.mica.music.media

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import com.mica.music.R
import com.mica.music.util.DiagnosticLog

/** Owns widget transport commands that can arrive before the service queue has been restored. */
internal class PlaybackWidgetCommandCoordinator(
    private val service: MediaSessionService,
    private val mainHandler: Handler,
    private val playerProvider: () -> MicaCompositePlayer?,
    private val sessionActivityPendingIntent: () -> PendingIntent,
    private val restoreExhausted: () -> Boolean,
) {
    companion object {
        const val ACTION_PREVIOUS = "com.mica.music.action.WIDGET_PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.mica.music.action.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.mica.music.action.WIDGET_NEXT"

        private const val RESTORE_TIMEOUT_MS = 10_000L
    }

    private var pendingAction: String? = null
    private var temporaryForegroundActive = false
    private val pendingActionTimeout = Runnable {
        if (pendingAction == null) return@Runnable
        DiagnosticLog.important(
            "PlaybackWidget",
            "cold-command-timeout action=$pendingAction items=${playerProvider()?.mediaItemCount ?: 0}",
        )
        pendingAction = null
        finishTemporaryForegroundIfIdle()
    }

    fun isPlaybackAction(action: String): Boolean = when (action) {
        ACTION_PREVIOUS,
        ACTION_PLAY_PAUSE,
        ACTION_NEXT,
        -> true
        else -> false
    }

    fun handle(action: String) {
        startTemporaryForegroundIfNeeded()
        val player = playerProvider()
        if (player == null) {
            finishTemporaryForegroundIfIdle()
            return
        }
        if (player.mediaItemCount == 0) {
            pendingAction = action
            mainHandler.removeCallbacks(pendingActionTimeout)
            mainHandler.postDelayed(pendingActionTimeout, RESTORE_TIMEOUT_MS)
            if (restoreExhausted()) {
                fail("no-restorable-queue")
            }
            return
        }
        execute(player, action)
        finishTemporaryForegroundIfIdle()
    }

    fun drain() {
        val action = pendingAction ?: return
        val player = playerProvider() ?: return
        if (player.mediaItemCount == 0) return
        pendingAction = null
        mainHandler.removeCallbacks(pendingActionTimeout)
        execute(player, action)
        finishTemporaryForegroundIfIdle()
    }

    fun fail(reason: String) {
        val action = pendingAction ?: return
        pendingAction = null
        mainHandler.removeCallbacks(pendingActionTimeout)
        DiagnosticLog.important(
            "PlaybackWidget",
            "cold-command-dropped action=$action reason=$reason",
        )
        finishTemporaryForegroundIfIdle()
    }

    fun release() {
        mainHandler.removeCallbacks(pendingActionTimeout)
        pendingAction = null
        temporaryForegroundActive = false
    }

    private fun execute(player: MicaCompositePlayer, action: String) {
        when (action) {
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_PLAY_PAUSE -> {
                if (player.playWhenReady) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
            ACTION_NEXT -> player.seekToNextMediaItem()
            else -> return
        }
    }

    // The merged manifest declares mediaPlayback plus its Android 14 permission; lint cannot
    // associate this ServiceCompat call with the MediaSessionService declaration.
    @SuppressLint("ForegroundServiceType")
    private fun startTemporaryForegroundIfNeeded() {
        if (temporaryForegroundActive || playerProvider()?.playWhenReady == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                    service.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
        val notification = NotificationCompat.Builder(
            service,
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentIntent(sessionActivityPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(
                service,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onSuccess {
            temporaryForegroundActive = true
        }.onFailure { error ->
            DiagnosticLog.event(
                "PlaybackWidget",
                "temporary-foreground-failed error=${error.javaClass.simpleName}",
                error,
            )
        }
    }

    private fun finishTemporaryForegroundIfIdle() {
        if (!temporaryForegroundActive) return
        if (playerProvider()?.playWhenReady == true) return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        temporaryForegroundActive = false
    }
}
