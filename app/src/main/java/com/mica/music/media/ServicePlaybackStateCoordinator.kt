package com.mica.music.media

import com.mica.music.data.playback.ServicePlaybackStateStore

import com.mica.music.data.playback.ServicePlaybackRestoreResolver

import com.mica.music.data.playback.ServicePlaybackRestore

import com.mica.music.data.playback.ServicePlaybackCursor

import com.mica.music.data.playback.ServiceQueueSnapshot

import com.mica.music.data.playback.ServiceExternalSongSnapshot

import com.mica.music.audio.AudioQualityMode

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import com.mica.music.data.Song
import com.mica.music.data.PlaybackTuning
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ServicePlaybackStateCoordinator(
    private val player: Player,
    private val store: ServicePlaybackStateStore,
    private val handler: Handler,
    initialQualityMode: AudioQualityMode,
    private val externalSongResolver: (String) -> Song? = { null },
) {
    private var pendingRestore = store.load()
    private var qualityMode = initialQualityMode
    private var released = false
    private var queueRevision = pendingRestore?.queueRevision ?: 0L
    private var lastPersistedPositionMs = Long.MIN_VALUE
    private var lastPersistedQueueIds: List<String>? = null
    private var lastPersistedExternalSongs: List<ServiceExternalSongSnapshot>? = null
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mica-playback-state").apply { isDaemon = true }
    }

    var onRestoreCompleted: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!tryRestore()) {
                val songIds = currentSongIds()
                val externalSongs = currentExternalSongs()
                if (songIds.isEmpty()) {
                    if (!lastPersistedQueueIds.isNullOrEmpty()) {
                        queueRevision++
                        lastPersistedQueueIds = emptyList()
                        lastPersistedExternalSongs = emptyList()
                        submit(sync = false) { store.clear() }
                    }
                } else if (songIds != lastPersistedQueueIds || externalSongs != lastPersistedExternalSongs) {
                    queueRevision++
                    lastPersistedQueueIds = songIds
                    lastPersistedExternalSongs = externalSongs
                    persistQueue(externalSongs = externalSongs)
                }
                persistCursor(force = true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistCursor(force = true)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            persistCursor(force = true)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            persistCursor(force = true)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) player.shuffleModeEnabled = false
            persistCursor(force = true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            persistCursor(force = true)
        }
    }

    private val periodicPersist = object : Runnable {
        override fun run() {
            if (released) return
            persistCursor()
            handler.postDelayed(this, PERSIST_INTERVAL_MS)
        }
    }

    fun start() {
        player.addListener(listener)
        handler.postDelayed(periodicPersist, PERSIST_INTERVAL_MS)
        tryRestore()
    }

    fun setQualityMode(mode: AudioQualityMode) {
        qualityMode = mode
        persistCursor(force = true)
    }

    fun release() {
        released = true
        handler.removeCallbacks(periodicPersist)
        persistQueue(sync = true)
        persistCursor(force = true, sync = true)
        persistenceExecutor.shutdown()
        persistenceExecutor.awaitTermination(2, TimeUnit.SECONDS)
        player.removeListener(listener)
    }

    private fun tryRestore(): Boolean {
        val snapshot = pendingRestore ?: return false
        val songIds = currentSongIds()
        if (songIds.isEmpty()) return true
        pendingRestore = null
        val restore = ServicePlaybackRestoreResolver.resolve(snapshot, songIds)
        if (restore == null) {
            store.clear()
            DiagnosticLog.event("PlaybackRestore", "saved song missing; discarded service snapshot")
            return false
        }
        player.repeatMode = restore.repeatMode
        player.shuffleModeEnabled = false
        if (player is MicaCompositePlayer) {
            player.pauseExoDirect()
        } else {
            player.playWhenReady = false
        }
        player.playbackParameters = restore.playbackTuning.toPlaybackParameters()
        player.seekTo(restore.currentIndex, restore.positionMs)
        player.prepare()
        DiagnosticLog.event(
            "PlaybackRestore",
            "service restored index=${restore.currentIndex} positionMs=${restore.positionMs} " +
                "savedPlayWhenReady=${snapshot.playWhenReady} resumed=false",
        )
        persistQueue(externalSongs = currentExternalSongs())
        persistCursor(force = true)
        lastPersistedQueueIds = currentSongIds()
        lastPersistedExternalSongs = currentExternalSongs()
        onRestoreCompleted?.invoke()
        return true
    }

    private fun persistQueue(
        sync: Boolean = false,
        externalSongs: List<ServiceExternalSongSnapshot> = currentExternalSongs(),
    ) {
        if (pendingRestore != null || player.mediaItemCount <= 0) return
        val songIds = currentSongIds()
        if (songIds.isEmpty()) return
        if (!queueCanSurviveRestart(songIds)) {
            submit(sync) { store.clear(sync) }
            return
        }
        submit(sync) {
            store.saveQueue(
                ServiceQueueSnapshot(
                    songIds = songIds,
                    revision = queueRevision,
                    externalSongs = externalSongs,
                ),
                sync,
            )
        }
    }

    private fun persistCursor(force: Boolean = false, sync: Boolean = false) {
        if (pendingRestore != null || player.mediaItemCount <= 0) return
        val currentId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return
        if (!songCanSurviveRestart(currentId)) return
        val position = player.currentPosition.coerceAtLeast(0L)
        if (!force && kotlin.math.abs(position - lastPersistedPositionMs) < MIN_POSITION_DELTA_MS) {
            return
        }
        lastPersistedPositionMs = position
        val cursor = ServicePlaybackCursor(
            currentSongId = currentId,
            positionMs = position,
            repeatMode = player.repeatMode,
            shuffleEnabled = false,
            playWhenReady = player.playWhenReady,
            qualityMode = qualityMode,
            playbackTuning = PlaybackTuning.fromPlaybackParameters(player.playbackParameters),
            queueRevision = queueRevision,
        )
        submit(sync) { store.saveCursor(cursor, sync) }
    }

    private fun submit(sync: Boolean, action: () -> Unit) {
        if (sync) {
            persistenceExecutor.submit(action).get()
        } else if (!released) {
            persistenceExecutor.execute(action)
        }
    }

    private fun currentSongIds(): List<String> = buildList {
        for (index in 0 until player.mediaItemCount) {
            player.getMediaItemAt(index).mediaId.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun queueCanSurviveRestart(songIds: List<String>): Boolean =
        songIds.all(::songCanSurviveRestart)

    private fun songCanSurviveRestart(id: String): Boolean =
        !com.mica.music.data.TransientPlaybackCatalog.isTransientId(id) ||
            externalSongResolver(id) != null

    private fun currentExternalSongs(): List<ServiceExternalSongSnapshot> = buildList {
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (!com.mica.music.data.TransientPlaybackCatalog.isTransientId(item.mediaId)) continue
            val song = externalSongResolver(item.mediaId)
                ?.takeIf(Song::isTransient)
            song?.let(ServiceExternalSongSnapshot::from)
                ?.let(::add)
        }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 3_000L
        const val MIN_POSITION_DELTA_MS = 1_000L
    }
}
