package com.mica.music.media

import android.content.Context
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.media3.common.Player
import com.mica.music.data.LyricsDocument
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.service.addConnectionListener

/** Optional projection of Mica's current structured lyrics and playback state into Lyricon. */
internal class LyriconLyricsSink(context: Context) {
    private val appContext = context.applicationContext

    private var provider: LyriconProvider? = null
    private var connectionListener: ConnectionListener? = null
    private var started = false
    private var released = false
    private var lastSongSignature: String? = null

    fun start(enabled: Boolean) {
        if (started || released) return
        started = true
        if (enabled) enableProvider()
    }

    fun setEnabled(enabled: Boolean) {
        if (!started || released) return
        if (enabled) {
            enableProvider()
        } else if (provider != null) {
            lastSongSignature = null
            closeProvider("disabled")
        }
    }

    private fun enableProvider() {
        if (provider != null || released) return
        val created = runCatching {
            LyriconFactory.createProvider(appContext).apply { autoSync = true }
        }.getOrElse { error ->
            DiagnosticLog.event(TAG, "provider creation failed", error)
            return
        }
        provider = created
        connectionListener = created.service.addConnectionListener {
            onConnected { DiagnosticLog.event(TAG, "connected") }
            onReconnected { DiagnosticLog.event(TAG, "reconnected") }
            onDisconnected { DiagnosticLog.event(TAG, "disconnected") }
            onConnectTimeout { DiagnosticLog.event(TAG, "connect timeout") }
        }
        sdkCall("register") { created.register() }
        DiagnosticLog.event(TAG, "enabled")
    }

    fun publishSong(
        song: Song,
        document: LyricsDocument?,
        effectiveOffsetMs: Int,
        signature: String,
        positionMs: Long,
        playbackState: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        if (released) return
        val remotePlayer = provider?.player ?: return
        if (signature != lastSongSignature) {
            val mapped = LyriconLyricsMapper.mapSong(
                song = song,
                document = document ?: LyricsDocument(),
                effectiveOffsetMs = effectiveOffsetMs,
            )
            sdkCall("setSong") { remotePlayer.setSong(mapped) }
            sdkCall("display translation") { remotePlayer.setDisplayTranslation(true) }
            sdkCall("display roma") { remotePlayer.setDisplayRoma(true) }
            lastSongSignature = signature
            val wordCount = mapped.lyrics.orEmpty().sumOf { it.words.orEmpty().size }
            DiagnosticLog.event(
                TAG,
                "setSong song=${song.id.takeLast(12)} lines=${mapped.lyrics.orEmpty().size} words=$wordCount",
            )
        }
        syncPlayback(
            positionMs = positionMs,
            playbackState = playbackState,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
        )
    }

    fun syncPlayback(
        positionMs: Long,
        playbackState: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        if (released) return
        val state = buildPlaybackState(
            positionMs = positionMs,
            playbackState = playbackState,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
        )
        provider?.player?.let { remotePlayer ->
            sdkCall("playback state") { remotePlayer.setPlaybackState(state) }
        }
    }

    fun seekTo(
        positionMs: Long,
        playbackState: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        if (released) return
        val remotePlayer = provider?.player ?: return
        val safePositionMs = positionMs.coerceAtLeast(0L)
        sdkCall("seek") { remotePlayer.seekTo(safePositionMs) }
        // Finish in PlaybackState mode so reconnect keeps position, speed and advancing semantics.
        syncPlayback(
            positionMs = safePositionMs,
            playbackState = playbackState,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
        )
    }

    fun clear() {
        if (released || lastSongSignature == null) return
        provider?.player?.let { remotePlayer ->
            sdkCall("clear") { remotePlayer.setSong(null) }
        }
        lastSongSignature = null
        DiagnosticLog.event(TAG, "clear")
    }

    fun release() {
        if (released) return
        released = true
        started = false
        lastSongSignature = null
        closeProvider("release")
    }

    private fun closeProvider(reason: String) {
        val current = provider
        val listener = connectionListener
        connectionListener = null
        provider = null
        if (current != null) {
            if (listener != null) {
                sdkCall("remove connection listener") {
                    current.service.removeConnectionListener(listener)
                }
            }
            sdkCall("unregister") { current.unregister() }
            sdkCall("destroy") { current.destroy() }
        }
        DiagnosticLog.event(TAG, reason)
    }

    private fun buildPlaybackState(
        positionMs: Long,
        playbackState: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ): PlaybackState {
        val state = when (playbackState) {
            Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        }
        val speed = if (isPlaying) playbackSpeed.coerceAtLeast(0f) else 0f
        return PlaybackState.Builder()
            .setState(
                state,
                positionMs.coerceAtLeast(0L),
                speed,
                SystemClock.elapsedRealtime(),
            )
            .build()
    }

    private inline fun sdkCall(operation: String, block: () -> Boolean) {
        runCatching(block).onFailure { error ->
            DiagnosticLog.event(TAG, "$operation failed", error)
        }
    }

    private companion object {
        const val TAG = "Lyricon"
    }
}
