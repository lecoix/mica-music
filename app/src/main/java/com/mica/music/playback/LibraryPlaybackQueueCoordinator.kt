package com.mica.music.playback

import com.mica.music.LibraryQueueSyncPlan
import com.mica.music.LibraryQueueSyncPolicy
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog

internal data class LibraryQueueSyncInput(
    val songs: List<Song>,
    val songIds: List<String>,
    val hasScanned: Boolean,
    val changeSet: com.mica.music.data.library.LibraryChangeSet?,
    val songById: (String) -> Song?,
)

/** 曲库可见列表 → 播放队列同步的执行协调（策略见 [LibraryQueueSyncPolicy]）。 */
internal class LibraryPlaybackQueueCoordinator(
    private val policy: LibraryQueueSyncPolicy = LibraryQueueSyncPolicy(),
) {
    internal interface Target {
        val currentQueueIds: List<String>
        val currentSongId: String?
        val isPlaying: Boolean
        val queueSize: Int
        fun connectIfNeeded()
        fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean
        fun setQueue(queue: List<Song>)
        fun removeFromQueue(index: Int)
        fun refreshQueueMetadata(songs: List<Song>)
    }

    fun sync(
        reason: String,
        library: LibraryQueueSyncInput,
        player: Target,
    ) {
        val songs = library.songs
        player.connectIfNeeded()
        val currentQueueIds = player.currentQueueIds
        when (
            val plan = policy.plan(
                songs = songs,
                libraryIds = library.songIds,
                currentQueueIds = currentQueueIds,
                changeSet = library.changeSet,
                currentSongId = player.currentSongId,
                isPlaying = player.isPlaying,
            )
        ) {
            LibraryQueueSyncPlan.SkipEmpty -> {
            }
            LibraryQueueSyncPlan.BootstrapOnly -> {
                player.bootstrapQueue(library.songById)
            }
            is LibraryQueueSyncPlan.BootstrapOrSetQueue -> {
                val bootstrapped = player.bootstrapQueue(library.songById)
                if (!bootstrapped) {
                    player.setQueue(plan.songs)
                }
            }
            is LibraryQueueSyncPlan.SetQueue -> {
                player.setQueue(plan.songs)
            }
            is LibraryQueueSyncPlan.ReconcileQueue -> {
                currentQueueIds.withIndex()
                    .filter { (_, id) -> id in plan.removeIds }
                    .map { it.index }
                    .sortedDescending()
                    .forEach(player::removeFromQueue)
                if (plan.songs.isNotEmpty()) {
                    player.refreshQueueMetadata(plan.songs)
                }
            }
            is LibraryQueueSyncPlan.RefreshMetadata -> {
                player.refreshQueueMetadata(plan.songs)
            }
        }
    }

    fun onPlaybackCurrentChanged(player: Target) {
        val orphanId = policy.orphanToPurgeAfterPlaybackTransition(
            currentSongId = player.currentSongId,
            currentQueueIds = player.currentQueueIds,
        ) ?: return
        val index = player.currentQueueIds.indexOf(orphanId)
        if (index >= 0) {
            DiagnosticLog.event(
                "LibraryQueue",
                "purge deferred orphan id=$orphanId index=$index",
            )
            player.removeFromQueue(index)
        }
    }

}

internal fun MusicLibrary.toLibraryQueueSyncInput(
    resolver: (String) -> Song? = ::songById,
): LibraryQueueSyncInput =
    LibraryQueueSyncInput(
        songs = songs,
        songIds = songIds,
        hasScanned = hasScanned,
        changeSet = lastLibraryChangeSet,
        songById = resolver,
    )

internal fun PlayerController.asLibraryPlaybackQueueTarget(): LibraryPlaybackQueueCoordinator.Target =
    object : LibraryPlaybackQueueCoordinator.Target {
        override val currentQueueIds: List<String>
            get() = playbackQueueState.queue.map { it.id }

        override val currentSongId: String?
            get() = playbackQueueState.queue.getOrNull(playbackQueueState.currentIndex)?.id

        override val isPlaying: Boolean
            get() = playbackSurfaceState.isPlaying

        override val queueSize: Int
            get() = playbackQueueState.queue.size

        override fun connectIfNeeded() = this@asLibraryPlaybackQueueTarget.connectIfNeeded()

        override fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean =
            this@asLibraryPlaybackQueueTarget.bootstrapQueue(resolveSong)

        override fun setQueue(queue: List<Song>) = this@asLibraryPlaybackQueueTarget.setQueue(queue)

        override fun removeFromQueue(index: Int) =
            this@asLibraryPlaybackQueueTarget.removeFromQueue(index)

        override fun refreshQueueMetadata(songs: List<Song>) =
            this@asLibraryPlaybackQueueTarget.refreshQueueMetadata(songs)
    }
