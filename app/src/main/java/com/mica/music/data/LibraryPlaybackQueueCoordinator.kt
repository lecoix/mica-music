package com.mica.music.data

import android.os.SystemClock
import com.mica.music.LibraryQueueSyncPlan
import com.mica.music.LibraryQueueSyncPolicy
import com.mica.music.util.DiagnosticLog

data class LibraryQueueSyncInput(
    val songs: List<Song>,
    val songIds: List<String>,
    val hasScanned: Boolean,
    val songById: (String) -> Song?,
)

/** 曲库可见列表 → 播放队列同步的执行协调（策略见 [LibraryQueueSyncPolicy]）。 */
internal class LibraryPlaybackQueueCoordinator(
    private val policy: LibraryQueueSyncPolicy = LibraryQueueSyncPolicy(),
) {
    internal interface Target {
        var songResolver: ((String) -> Song?)?
        val currentQueueIds: List<String>
        val queueSize: Int
        fun connectIfNeeded()
        fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean
        fun setQueue(queue: List<Song>)
        fun refreshQueueMetadata(songs: List<Song>)
    }

    fun sync(
        reason: String,
        library: LibraryQueueSyncInput,
        player: Target,
    ) {
        val effectStartedMs = SystemClock.elapsedRealtime()
        val songs = library.songs
        if (songs.isNotEmpty()) {
            player.songResolver = library.songById
            DiagnosticLog.event("LibraryQueue", "$reason connectIfNeeded start songs=${songs.size}")
            player.connectIfNeeded()
        }
        val currentQueueIds = player.currentQueueIds
        when (
            val plan = policy.plan(
                songs = songs,
                libraryIds = library.songIds,
                currentQueueIds = currentQueueIds,
            )
        ) {
            LibraryQueueSyncPlan.SkipEmpty -> {
                DiagnosticLog.event(
                    "LibraryQueue",
                    "$reason effect skipped empty hasScanned=${library.hasScanned}",
                )
            }
            is LibraryQueueSyncPlan.BootstrapOrSetQueue -> {
                logEffectStart(reason, plan, songs.size, currentQueueIds.size)
                val bootstrapped = player.bootstrapQueue(library.songById)
                DiagnosticLog.event(
                    "LibraryQueue",
                    "$reason bootstrap result=$bootstrapped " +
                        "durMs=${SystemClock.elapsedRealtime() - effectStartedMs}",
                )
                if (!bootstrapped) {
                    player.setQueue(plan.songs)
                }
                logEffectEnd(reason, effectStartedMs, player.queueSize)
            }
            is LibraryQueueSyncPlan.SetQueue -> {
                logEffectStart(reason, plan, songs.size, currentQueueIds.size)
                player.setQueue(plan.songs)
                logEffectEnd(reason, effectStartedMs, player.queueSize)
            }
            is LibraryQueueSyncPlan.RefreshMetadata -> {
                logEffectStart(reason, plan, songs.size, currentQueueIds.size)
                player.refreshQueueMetadata(plan.songs)
                logEffectEnd(reason, effectStartedMs, player.queueSize)
            }
        }
    }

    private fun logEffectStart(
        reason: String,
        plan: LibraryQueueSyncPlan,
        songsSize: Int,
        currentQueueSize: Int,
    ) {
        DiagnosticLog.event(
            "LibraryQueue",
            "$reason effect start songs=$songsSize queue=$currentQueueSize " +
                "previousLibrary=${plan.previousLibraryIdsSize()} " +
                "currentQueueWasLibrary=${plan.currentQueueWasLibrary()}",
        )
    }

    private fun logEffectEnd(reason: String, startedMs: Long, queueSize: Int) {
        DiagnosticLog.event(
            "LibraryQueue",
            "$reason effect end durMs=${SystemClock.elapsedRealtime() - startedMs} queue=$queueSize",
        )
    }
}

internal fun MusicLibrary.toLibraryQueueSyncInput(): LibraryQueueSyncInput =
    LibraryQueueSyncInput(
        songs = songs,
        songIds = songIds,
        hasScanned = hasScanned,
        songById = ::songById,
    )

internal fun PlayerController.asLibraryPlaybackQueueTarget(): LibraryPlaybackQueueCoordinator.Target =
    object : LibraryPlaybackQueueCoordinator.Target {
        override var songResolver: ((String) -> Song?)?
            get() = this@asLibraryPlaybackQueueTarget.songResolver
            set(value) {
                this@asLibraryPlaybackQueueTarget.songResolver = value
            }

        override val currentQueueIds: List<String>
            get() = playbackQueueState.queue.map { it.id }

        override val queueSize: Int
            get() = playbackQueueState.queue.size

        override fun connectIfNeeded() = this@asLibraryPlaybackQueueTarget.connectIfNeeded()

        override fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean =
            this@asLibraryPlaybackQueueTarget.bootstrapQueue(resolveSong)

        override fun setQueue(queue: List<Song>) = this@asLibraryPlaybackQueueTarget.setQueue(queue)

        override fun refreshQueueMetadata(songs: List<Song>) =
            this@asLibraryPlaybackQueueTarget.refreshQueueMetadata(songs)
    }

private fun LibraryQueueSyncPlan.previousLibraryIdsSize(): Int =
    when (this) {
        LibraryQueueSyncPlan.SkipEmpty -> 0
        is LibraryQueueSyncPlan.BootstrapOrSetQueue -> previousLibraryIdsSize
        is LibraryQueueSyncPlan.SetQueue -> previousLibraryIdsSize
        is LibraryQueueSyncPlan.RefreshMetadata -> previousLibraryIdsSize
    }

private fun LibraryQueueSyncPlan.currentQueueWasLibrary(): Boolean =
    when (this) {
        LibraryQueueSyncPlan.SkipEmpty -> false
        is LibraryQueueSyncPlan.BootstrapOrSetQueue -> currentQueueWasLibrary
        is LibraryQueueSyncPlan.SetQueue -> currentQueueWasLibrary
        is LibraryQueueSyncPlan.RefreshMetadata -> currentQueueWasLibrary
    }
