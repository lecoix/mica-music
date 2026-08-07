package com.mica.music.data

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PlaybackQueueMirrorResult(
    val itemsCount: Int,
    val resolvedCount: Int,
    val applied: Boolean,
)

internal class PlaybackQueueMirrorCoordinator(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val debounceMs: Long,
) {
    private var refreshJob: Job? = null
    private var refreshRequestId = 0L
    private var lastOrderSignature: QueueOrderSignature? = null

    fun orderSignature(player: Player): QueueOrderSignature =
        PlaybackQueueMirror.orderSignature(PlaybackQueueMirror.snapshotItems(player))

    fun hasSignature(signature: QueueOrderSignature): Boolean =
        signature == lastOrderSignature

    fun rebuildNow(
        player: Player,
        resolver: ((String) -> Song?)?,
        applyMirrored: (songs: List<Song>, playerIndex: Int) -> Unit,
    ): PlaybackQueueMirrorResult {
        val items = PlaybackQueueMirror.snapshotItems(player)
        val mirrored = PlaybackQueueMirror.rebuildSongs(items, resolver)
        val complete = mirrored.isNotEmpty() && mirrored.size == items.size
        if (complete) {
            applyMirrored(mirrored, player.currentMediaItemIndex)
            lastOrderSignature = PlaybackQueueMirror.orderSignature(items)
        }
        return PlaybackQueueMirrorResult(items.size, mirrored.size, complete)
    }

    fun schedule(
        player: MediaController,
        isCurrentPlayer: () -> Boolean,
        localQueue: () -> List<Song>,
        localRevision: () -> Long,
        fallbackResolver: () -> ((String) -> Song?)?,
        applyMirrored: (songs: List<Song>, playerIndex: Int) -> Unit,
        syncIndex: () -> Unit,
        log: (action: String, startedNs: Long, details: String) -> Unit,
    ) {
        val requestId = ++refreshRequestId
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(debounceMs)
            if (requestId != refreshRequestId || !isCurrentPlayer()) return@launch

            val mirrorStartedNs = SystemClock.elapsedRealtimeNanos()
            val items = PlaybackQueueMirror.snapshotItems(player)
            if (items.isEmpty()) {
                log("mirror-rebuild-rejected", mirrorStartedNs, "reason=incomplete-snapshot")
                return@launch
            }
            val previousSignature = lastOrderSignature
            val revisionBeforeBuild = localRevision()
            val build = buildMirror(items, previousSignature, localQueue(), fallbackResolver())
            if (requestId != refreshRequestId ||
                !isCurrentPlayer() ||
                revisionBeforeBuild != localRevision()
            ) {
                return@launch
            }

            val mirrored = build.songs
            if (mirrored == null || build.signature == lastOrderSignature) {
                syncIndex()
                log(
                    "mirror-rebuild-skipped",
                    mirrorStartedNs,
                    "playerItems=${items.size} reason=same-order",
                )
                return@launch
            }
            if (mirrored.isEmpty() || mirrored.size != items.size) {
                log(
                    "mirror-rebuild-rejected",
                    mirrorStartedNs,
                    "playerItems=${items.size} resolved=${mirrored.size} reason=incomplete-decode",
                )
                return@launch
            }
            applyMirrored(mirrored, player.currentMediaItemIndex)
            lastOrderSignature = build.signature
            syncIndex()
            log(
                "mirror-rebuild",
                mirrorStartedNs,
                "playerItems=${items.size} resolved=${mirrored.size} mode=debounced",
            )
        }
    }

    fun clear() {
        refreshRequestId += 1
        refreshJob?.cancel()
        refreshJob = null
        lastOrderSignature = null
    }

    private suspend fun buildMirror(
        items: List<MediaItem>,
        previousSignature: QueueOrderSignature?,
        localQueue: List<Song>,
        fallbackResolver: ((String) -> Song?)?,
    ): QueueMirrorBuild =
        withContext(workerDispatcher) {
            PlaybackQueueMirror.buildIfChanged(
                items = items,
                previousSignature = previousSignature,
                localQueue = localQueue,
                fallbackResolver = fallbackResolver,
            )
        }
}
