package com.mica.music.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
import com.mica.music.data.Song

internal interface CarBluetoothLyricsSink {
    fun setEnabled(enabled: Boolean)
    fun publishLyric(song: Song, line: String)
    fun publishDefault(song: Song)
    fun clear()
}

internal data class CarBluetoothLyricsPayload(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    companion object {
        fun lyric(song: Song, line: String): CarBluetoothLyricsPayload? {
            val title = line.trim().takeIf(String::isNotEmpty) ?: return null
            return from(song, title)
        }

        fun default(song: Song): CarBluetoothLyricsPayload = from(song, song.title)

        private fun from(song: Song, title: String): CarBluetoothLyricsPayload =
            CarBluetoothLyricsPayload(
                title = title,
                artist = NotificationLyrics.subtitle(song.title, song.artist),
                album = song.album,
                durationMs = song.durationSec.coerceAtLeast(0) * 1_000L,
            )
    }
}

/**
 * Experimental AVRCP lyric surface modelled after the observed NetEase session:
 * one active legacy session, current lyric in TITLE, and deliberately no queue/browse tree.
 * The production Media3 session remains responsible for notification rendering and app clients.
 */
internal class CarBluetoothLyricsSession(
    context: Context,
    private val player: Player,
    sessionActivity: PendingIntent,
) : CarBluetoothLyricsSink {
    private val handler = Handler(player.applicationLooper)
    private val mediaButtonComponent = ComponentName(context, MicaMediaService::class.java)
    private val mediaButtonIntent = PendingIntent.getService(
        context,
        0,
        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonComponent),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    private val context = context.applicationContext
    private val sessionActivity = sessionActivity
    private var session: MediaSessionCompat? = null
    private var enabled = false
    private var released = false
    private var lastPayload: CarBluetoothLyricsPayload? = null

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = onPlayer { player.play() }
        override fun onPause() = onPlayer { player.pause() }
        override fun onStop() = onPlayer { player.stop() }
        override fun onSeekTo(pos: Long) = onPlayer { player.seekTo(pos.coerceAtLeast(0L)) }
        override fun onSkipToNext() = onPlayer {
            if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
                player.seekToNextMediaItem()
            }
        }
        override fun onSkipToPrevious() = onPlayer {
            if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
                player.seekToPreviousMediaItem()
            }
        }
        override fun onFastForward() = onPlayer {
            player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(player.duration.safeDuration()))
        }
        override fun onRewind() = onPlayer {
            player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
        }
        override fun onSetPlaybackSpeed(speed: Float) = onPlayer {
            if (speed > 0f && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                player.setPlaybackSpeed(speed)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!released && enabled) publishPlaybackState()
        }
    }

    init {
        player.addListener(playerListener)
    }

    override fun setEnabled(enabled: Boolean) = onPlayer {
        if (released || this.enabled == enabled) return@onPlayer
        this.enabled = enabled
        if (!enabled) {
            lastPayload = null
            releaseSession()
        }
    }

    override fun publishLyric(song: Song, line: String) {
        CarBluetoothLyricsPayload.lyric(song, line)?.let(::publish)
    }

    override fun publishDefault(song: Song) {
        publish(CarBluetoothLyricsPayload.default(song))
    }

    override fun clear() = onPlayer {
        if (released) return@onPlayer
        lastPayload = null
        releaseSession()
    }

    fun release() = onPlayer {
        if (released) return@onPlayer
        released = true
        player.removeListener(playerListener)
        releaseSession()
    }

    private fun publish(payload: CarBluetoothLyricsPayload) = onPlayer {
        if (released || !enabled) return@onPlayer
        val activeSession = ensureSession()
        if (lastPayload != payload) {
            activeSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, payload.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, payload.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, payload.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, payload.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, payload.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, payload.durationMs)
                    .build(),
            )
            lastPayload = payload
        }
        publishPlaybackState()
        activeSession.setActive(true)
    }

    private fun publishPlaybackState() {
        if (released || !enabled) return
        val activeSession = session ?: return
        val state = when {
            player.playerError != null -> PlaybackStateCompat.STATE_ERROR
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            player.playbackState == Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (player.isPlaying) player.playbackParameters.speed else 0f
        activeSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setActiveQueueItemId(MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong())
                .setState(
                    state,
                    player.currentPosition.coerceAtLeast(0L),
                    speed,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    private fun ensureSession(): MediaSessionCompat = session ?: MediaSessionCompat(
        context,
        SESSION_TAG,
        mediaButtonComponent,
        mediaButtonIntent,
        Bundle.EMPTY,
    ).also { created ->
        created.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        created.setSessionActivity(sessionActivity)
        created.setCallback(callback, handler)
        created.setQueue(emptyList())
        created.setQueueTitle("")
        session = created
    }

    private fun releaseSession() {
        session?.setActive(false)
        session?.release()
        session = null
    }

    private inline fun onPlayer(crossinline action: () -> Unit) {
        if (android.os.Looper.myLooper() == player.applicationLooper) action() else handler.post { action() }
    }

    private fun Long.safeDuration(): Long = takeIf { it > 0L } ?: Long.MAX_VALUE

    private companion object {
        const val SESSION_TAG = "MicaCarBluetoothLyrics"
        const val SEEK_STEP_MS = 10_000L
        const val PLAYBACK_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED
    }
}
