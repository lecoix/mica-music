package com.mica.music.media

import com.mica.music.data.playback.ServicePlaybackStateStore

import com.mica.music.data.playback.ServicePlaybackRestoreResolver

import com.mica.music.data.playback.ServicePlaybackRestore

import com.mica.music.data.playback.ServicePlaybackCursor

import com.mica.music.data.playback.ServiceQueueSnapshot

import com.mica.music.data.playback.ServiceExternalSongSnapshot
import com.mica.music.data.playback.ServiceRemoteSongSnapshot

import com.mica.music.audio.AudioQualityMode

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import com.mica.music.data.Song
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ServicePlaybackStateCoordinator(
    private val player: Player,
    private val store: ServicePlaybackStateStore,
    private val handler: Handler,
    initialQualityMode: AudioQualityMode,
    private val externalSongResolver: (String) -> Song? = { null },
    private val orderProvider: (List<String>) -> AcceptedPlaybackOrder? = { null },
    private val restoreOrder: (com.mica.music.data.playback.ServicePlaybackSnapshot) -> Unit = {},
) {
    private var pendingRestore = store.load()
    private var qualityMode = initialQualityMode
    private var released = false
    private var queueRevision = pendingRestore?.queueRevision ?: 0L
    private var lastPersistedPositionMs: Long? = null
    private var queueContent: QueueContent? = null
    private var restorableRemoteIds = emptySet<String>()
    private var queueCanRestore = false
    private var queueShuffleEnabled = false
    private var restoring = false
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mica-playback-state").apply { isDaemon = true }
    }

    var onRestoreCompleted: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (restoring || tryRestore()) return
            if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && queueContent != null) {
                // Media sources refresh their timelines without replacing playlist items.
                persistCursor()
                return
            }
            updateQueue()
            persistCursor(force = true)
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
            // App shuffle may temporarily use Media3's native ShuffleOrder without physically
            // rebuilding the playlist. Persist the cursor, but do not force native shuffle off.
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
        if (!tryRestore()) {
            updateQueue()
            persistCursor(force = true)
        }
    }

    fun setQualityMode(mode: AudioQualityMode) {
        qualityMode = mode
        persistCursor(force = true)
    }

    fun onOrderChanged() {
        if (released || restoring) return
        pendingRestore = null
        updateQueue(orderChanged = true)
        persistCursor(force = true)
    }

    fun release() {
        released = true
        handler.removeCallbacks(periodicPersist)
        updateQueue(sync = true)
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
            submit(sync = false) { store.clear() }
            DiagnosticLog.important("PlaybackRestore", "saved song missing; discarded service snapshot")
            return false
        }
        restoring = true
        try {
            player.repeatMode = restore.repeatMode
            player.shuffleModeEnabled = false
            restoreOrder(snapshot)
            if (player is MicaCompositePlayer) {
                player.pauseExoDirect()
            } else {
                player.playWhenReady = false
            }
            player.playbackParameters = restore.playbackTuning.toPlaybackParameters()
            player.seekTo(restore.currentIndex, restore.positionMs)
            player.prepare()
        } finally {
            restoring = false
        }
        DiagnosticLog.event(
            "PlaybackRestore",
            "service restored index=${restore.currentIndex} positionMs=${restore.positionMs} " +
                "savedPlayWhenReady=${snapshot.playWhenReady} resumed=false",
        )
        updateQueue()
        persistCursor(force = true)
        onRestoreCompleted?.invoke()
        return true
    }

    private fun updateQueue(orderChanged: Boolean = false, sync: Boolean = false) {
        if (pendingRestore != null || restoring) return
        val content = readQueueContent()
        val previous = queueContent
        if (content == previous && !orderChanged && !sync) return

        queueContent = content
        val songIds = content.songIds
        if (content != previous || orderChanged) queueRevision++
        val remoteIds = content.remoteSongs.mapTo(HashSet(content.remoteSongs.size), ServiceRemoteSongSnapshot::id)
        restorableRemoteIds = remoteIds
        queueCanRestore = songIds.isNotEmpty() && songIds.all { id ->
            when {
                TransientPlaybackCatalog.isTransientId(id) -> externalSongResolver(id) != null
                RemoteMediaIdCodec.isRemoteId(id) -> id in remoteIds
                else -> true
            }
        }
        if (!queueCanRestore) {
            queueShuffleEnabled = false
            if (songIds.isNotEmpty() || !previous?.songIds.isNullOrEmpty()) {
                submit(sync) { store.clear(sync) }
            }
            return
        }

        val order = orderProvider(songIds)
        queueShuffleEnabled = order?.shuffleEnabled ?: false
        val snapshot = ServiceQueueSnapshot(
            songIds = songIds,
            revision = queueRevision,
            externalSongs = content.externalSongs,
            remoteSongs = content.remoteSongs,
            playbackOrderIds = order?.playbackIds.orEmpty(),
            sourceOrderIds = order?.sourceIds.orEmpty(),
            appShuffleEnabled = order?.shuffleEnabled,
            orderCurrentSongId = player.currentMediaItem?.mediaId,
        )
        submit(sync) { store.saveQueue(snapshot, sync) }
    }

    private fun persistCursor(force: Boolean = false, sync: Boolean = false) {
        if (!queueCanRestore || pendingRestore != null || restoring) return
        val currentId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return
        if (!songCanSurviveRestart(currentId)) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val previousPosition = lastPersistedPositionMs
        if (!force && previousPosition != null &&
            kotlin.math.abs(position - previousPosition) < MIN_POSITION_DELTA_MS
        ) {
            return
        }
        lastPersistedPositionMs = position
        val cursor = ServicePlaybackCursor(
            currentSongId = currentId,
            positionMs = position,
            repeatMode = player.repeatMode,
            shuffleEnabled = queueShuffleEnabled,
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

    private fun songCanSurviveRestart(id: String): Boolean = when {
        TransientPlaybackCatalog.isTransientId(id) -> externalSongResolver(id) != null
        RemoteMediaIdCodec.isRemoteId(id) -> id in restorableRemoteIds
        else -> true
    }

    private fun readQueueContent(): QueueContent {
        val songIds = ArrayList<String>(player.mediaItemCount)
        val externalSongs = mutableListOf<ServiceExternalSongSnapshot>()
        val remoteSongs = mutableListOf<ServiceRemoteSongSnapshot>()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val id = item.mediaId.takeIf(String::isNotBlank) ?: continue
            songIds.add(id)
            if (TransientPlaybackCatalog.isTransientId(id)) {
                externalSongResolver(id)?.takeIf(Song::isTransient)
                    ?.let(ServiceExternalSongSnapshot::from)?.let(externalSongs::add)
            } else if (RemoteMediaIdCodec.isRemoteId(id)) {
                ServiceRemoteSongSnapshot.fromMediaItem(item)?.let(remoteSongs::add)
            }
        }
        return QueueContent(songIds, externalSongs, remoteSongs)
    }

    private data class QueueContent(
        val songIds: List<String>,
        val externalSongs: List<ServiceExternalSongSnapshot>,
        val remoteSongs: List<ServiceRemoteSongSnapshot>,
    )

    private companion object {
        const val PERSIST_INTERVAL_MS = 3_000L
        const val MIN_POSITION_DELTA_MS = 1_000L
    }
}
