package com.mica.music.data

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.media.SongMediaItemCodec

internal data class QueueSyncResult(
    val itemsCount: Int,
    val startIndex: Int,
    val preserveCurrentPlayback: Boolean,
    val queueAligned: Boolean,
    val targetMismatch: Boolean,
    val reusedMap: Boolean,
)

internal sealed class PlaybackQueueSyncPlan {
    abstract val result: QueueSyncResult

    data class Skip(
        override val result: QueueSyncResult,
    ) : PlaybackQueueSyncPlan()

    data class SetMediaItems(
        val items: List<MediaItem>,
        val startPositionMs: Long,
        override val result: QueueSyncResult,
    ) : PlaybackQueueSyncPlan()

    data class ReplaceMediaItems(
        val replacements: List<IndexedValue<MediaItem>>,
        override val result: QueueSyncResult,
    ) : PlaybackQueueSyncPlan()
}

internal object MediaControllerQueueSync {
    fun canMoveItemIncrementally(
        player: Player,
        queueBeforeMove: List<Song>,
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        if (player.mediaItemCount != queueBeforeMove.size) return false
        val sourceMatches = runCatching {
            player.getMediaItemAt(fromIndex).mediaId == queueBeforeMove[fromIndex].id
        }.getOrDefault(false)
        val destinationMatches = runCatching {
            player.getMediaItemAt(toIndex).mediaId == queueBeforeMove[toIndex].id
        }.getOrDefault(false)
        return sourceMatches && destinationMatches
    }

    fun planSync(
        player: Player,
        queue: List<Song>,
        targetIndex: Int,
        positionMs: Long,
        preserveCurrentPlayback: Boolean,
        prebuiltItems: List<MediaItem>? = null,
        mediaItemFactory: (Song) -> MediaItem = { song -> song.toMediaItem() },
    ): PlaybackQueueSyncPlan? {
        if (queue.isEmpty()) return null
        val safeTarget = targetIndex.coerceIn(0, queue.lastIndex)
        val startIndex = if (preserveCurrentPlayback) {
            val currentId = player.currentMediaItem?.mediaId
            currentId?.let { id ->
                queue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            } ?: safeTarget
        } else {
            safeTarget
        }
        val startPosition = if (preserveCurrentPlayback) {
            runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val targetSongId = queue.getOrNull(safeTarget)?.id
        val serviceIdAtTarget = runCatching { player.getMediaItemAt(safeTarget).mediaId }.getOrNull()
        val targetMismatch = targetSongId != null && targetSongId != serviceIdAtTarget
        val identityAligned = player.mediaItemCount == queue.size &&
            queue.indices.all { index ->
                runCatching { player.getMediaItemAt(index).mediaId == queue[index].id }
                    .getOrDefault(false)
            }
        val metadataChangedIndices = if (identityAligned) {
            queue.indices.filter { index ->
                runCatching {
                    SongMediaItemCodec.metadataRevision(player.getMediaItemAt(index)) !=
                        SongMediaItemCodec.metadataRevision(queue[index])
                }.getOrDefault(true)
            }
        } else {
            emptyList()
        }
        val queueAligned = identityAligned && metadataChangedIndices.isEmpty()
        if (preserveCurrentPlayback && queueAligned && !targetMismatch) {
            return PlaybackQueueSyncPlan.Skip(
                QueueSyncResult(
                    itemsCount = queue.size,
                    startIndex = safeTarget,
                    preserveCurrentPlayback = preserveCurrentPlayback,
                    queueAligned = queueAligned,
                    targetMismatch = targetMismatch,
                    reusedMap = prebuiltItems != null,
                ),
            )
        }
        if (preserveCurrentPlayback && identityAligned && !targetMismatch &&
            metadataChangedIndices.isNotEmpty() &&
            player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        ) {
            val items = prebuiltItems ?: queue.map(mediaItemFactory)
            return PlaybackQueueSyncPlan.ReplaceMediaItems(
                replacements = metadataChangedIndices.map { index -> IndexedValue(index, items[index]) },
                result = QueueSyncResult(
                    itemsCount = items.size,
                    startIndex = startIndex,
                    preserveCurrentPlayback = true,
                    queueAligned = false,
                    targetMismatch = false,
                    reusedMap = prebuiltItems != null,
                ),
            )
        }
        val items = prebuiltItems ?: queue.map(mediaItemFactory)
        return PlaybackQueueSyncPlan.SetMediaItems(
            items = items,
            startPositionMs = startPosition,
            result = QueueSyncResult(
                itemsCount = items.size,
                startIndex = startIndex,
                preserveCurrentPlayback = preserveCurrentPlayback,
                queueAligned = queueAligned,
                targetMismatch = targetMismatch,
                reusedMap = prebuiltItems != null,
            ),
        )
    }

    fun executeSyncPlan(player: Player, plan: PlaybackQueueSyncPlan): QueueSyncResult {
        when (plan) {
            is PlaybackQueueSyncPlan.Skip -> Unit
            is PlaybackQueueSyncPlan.ReplaceMediaItems -> plan.replacements.forEach { replacement ->
                player.replaceMediaItem(replacement.index, replacement.value)
            }
            is PlaybackQueueSyncPlan.SetMediaItems ->
                player.setMediaItems(plan.items, plan.result.startIndex, plan.startPositionMs)
        }
        return plan.result
    }

    fun syncToPlayer(
        player: Player,
        queue: List<Song>,
        targetIndex: Int,
        positionMs: Long,
        preserveCurrentPlayback: Boolean,
        prebuiltItems: List<MediaItem>? = null,
        mediaItemFactory: (Song) -> MediaItem = { song -> song.toMediaItem() },
    ): QueueSyncResult? =
        planSync(
            player = player,
            queue = queue,
            targetIndex = targetIndex,
            positionMs = positionMs,
            preserveCurrentPlayback = preserveCurrentPlayback,
            prebuiltItems = prebuiltItems,
            mediaItemFactory = mediaItemFactory,
        )?.let { plan ->
            executeSyncPlan(player, plan)
        }
}
