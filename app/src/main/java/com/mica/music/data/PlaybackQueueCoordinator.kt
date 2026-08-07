package com.mica.music.data

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal class PlaybackQueueCoordinator(
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    mirrorDebounceMs: Long,
) {
    private var model = PlaybackQueueModel()
    private val mirror = PlaybackQueueMirrorCoordinator(
        scope = scope,
        workerDispatcher = workerDispatcher,
        debounceMs = mirrorDebounceMs,
    )

    var revision: Long = 0L
        private set

    val queue: List<Song>
        get() = model.queue

    val currentIndex: Int
        get() = model.currentIndex

    val order: PlaybackOrderState
        get() = model.order

    fun snapshot(): PlaybackQueueModel = model

    fun commit(next: PlaybackQueueModel) {
        if (next == model) return
        if (next.queue != model.queue || next.currentIndex != model.currentIndex) {
            revision += 1
        }
        model = next
    }

    fun replaceQueue(queue: List<Song>) {
        commit(model.copy(queue = queue))
    }

    fun replaceCurrentIndex(index: Int) {
        commit(model.copy(currentIndex = index))
    }

    fun replaceOrder(order: PlaybackOrderState) {
        commit(model.copy(order = order))
    }

    fun orderSignature(player: Player): QueueOrderSignature = mirror.orderSignature(player)

    fun hasSignature(signature: QueueOrderSignature): Boolean = mirror.hasSignature(signature)

    fun rebuildMirrorNow(
        player: Player,
        resolver: ((String) -> Song?)?,
        onApplied: () -> Unit,
    ): PlaybackQueueMirrorResult =
        mirror.rebuildNow(
            player = player,
            resolver = resolver,
        ) { songs, playerIndex ->
            commit(model.mirrorFromPlayer(songs, playerIndex))
            onApplied()
        }

    fun scheduleMirror(
        player: MediaController,
        isCurrentPlayer: () -> Boolean,
        fallbackResolver: () -> ((String) -> Song?)?,
        onApplied: () -> Unit,
        syncIndex: () -> Unit,
        log: (action: String, startedNs: Long, details: String) -> Unit,
    ) {
        mirror.schedule(
            player = player,
            isCurrentPlayer = isCurrentPlayer,
            localQueue = { model.queue },
            localRevision = { revision },
            fallbackResolver = fallbackResolver,
            applyMirrored = { songs, playerIndex ->
                commit(model.mirrorFromPlayer(songs, playerIndex))
                onApplied()
            },
            syncIndex = syncIndex,
            log = log,
        )
    }

    fun clearMirror() = mirror.clear()
}
