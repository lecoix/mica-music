package com.mica.music.playback

import com.mica.music.data.Song

internal data class PlaybackQueueModel(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val order: PlaybackOrderState = PlaybackOrderState(),
) {
    val currentSong: Song?
        get() = queue.getOrNull(currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))

    fun applyOrder(
        nextOrder: PlaybackOrderState,
        candidates: List<Song> = queue,
    ): PlaybackQueueModel {
        val byId = (queue + candidates).associateBy { it.id }
        val orderedSongs = nextOrder.playbackIds.mapNotNull { byId[it] }
        val safeOrder = if (orderedSongs.size == nextOrder.playbackIds.size) {
            nextOrder
        } else {
            nextOrder.withQueue(orderedSongs.map { it.id })
        }
        return copy(
            queue = orderedSongs,
            currentIndex = if (orderedSongs.isEmpty()) {
                0
            } else {
                safeOrder.currentOrderIndex.coerceIn(0, orderedSongs.lastIndex)
            },
            order = safeOrder,
        )
    }

    fun resetFromQueue(
        nextQueue: List<Song>,
        preserveSongId: String?,
    ): PlaybackQueueModel {
        val nextOrder = order.withQueue(
            ids = nextQueue.map { it.id },
            preserveId = preserveSongId,
        )
        return applyOrder(nextOrder, nextQueue)
    }

    fun preserveIndexForQueue(
        nextQueue: List<Song>,
        preserveSongId: String?,
        fallbackIndex: Int = currentIndex,
    ): PlaybackQueueModel {
        if (nextQueue.isEmpty()) {
            return copy(queue = emptyList(), currentIndex = 0, order = order.withQueue(emptyList()))
        }
        val keepIndex = preserveSongId?.let { id ->
            nextQueue.indexOfFirst { it.id == id }
        } ?: -1
        val nextIndex = if (keepIndex >= 0) {
            keepIndex
        } else {
            fallbackIndex.coerceIn(0, nextQueue.lastIndex)
        }
        val nextOrder = nextQueue.getOrNull(nextIndex)?.id?.let(order::moveTo) ?: order
        return copy(queue = nextQueue, currentIndex = nextIndex, order = nextOrder)
    }

    fun mirrorFromPlayer(
        mirrored: List<Song>,
        playerIndex: Int,
        preserveShuffleEnabled: Boolean = order.shuffleEnabled,
    ): PlaybackQueueModel {
        val safeIndex = playerIndex.coerceIn(0, (mirrored.size - 1).coerceAtLeast(0))
        val mirroredIds = mirrored.map { it.id }
        val sourceIds = if (preserveShuffleEnabled && order.sourceIds.isNotEmpty()) {
            val mirroredSet = mirroredIds.toHashSet()
            val preserved = order.sourceIds.filter { it in mirroredSet }.distinct()
            preserved + mirroredIds.filterNot(preserved.toHashSet()::contains)
        } else {
            mirroredIds
        }
        return copy(
            queue = mirrored,
            currentIndex = if (mirrored.isEmpty()) 0 else safeIndex,
            order = PlaybackOrderState(
                sourceIds = sourceIds,
                playbackIds = mirroredIds,
                currentId = mirrored.getOrNull(safeIndex)?.id,
                shuffleEnabled = preserveShuffleEnabled,
                shuffleSeed = order.shuffleSeed.takeIf { preserveShuffleEnabled },
            ),
        )
    }

    fun linearQueue(
        nextQueue: List<Song>,
        nextIndex: Int,
        preserveShuffleEnabled: Boolean = order.shuffleEnabled,
    ): PlaybackQueueModel {
        val safeIndex = nextIndex.coerceIn(0, (nextQueue.size - 1).coerceAtLeast(0))
        return copy(
            queue = nextQueue,
            currentIndex = if (nextQueue.isEmpty()) 0 else safeIndex,
            order = PlaybackOrderState(
                sourceIds = nextQueue.map { it.id },
                playbackIds = nextQueue.map { it.id },
                currentId = nextQueue.getOrNull(safeIndex)?.id,
                shuffleEnabled = preserveShuffleEnabled,
                shuffleSeed = order.shuffleSeed.takeIf { preserveShuffleEnabled },
            ),
        )
    }

    fun selectId(id: String): PlaybackQueueModel =
        copy(order = order.moveTo(id))

    fun selectIndex(index: Int): PlaybackQueueModel {
        if (queue.isEmpty()) return copy(currentIndex = 0)
        val safe = index.coerceIn(0, queue.lastIndex)
        val nextOrder = queue.getOrNull(safe)?.id?.let(order::moveTo) ?: order
        return copy(currentIndex = safe, order = nextOrder)
    }

    fun insertPlayNext(song: Song): PlaybackQueueModel =
        applyOrder(order.insertPlayNext(song.id), queue + song)

    fun move(fromIndex: Int, toIndex: Int): PlaybackQueueModel {
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) {
            return this
        }
        val nextQueue = queue.toMutableList()
        val moved = nextQueue.removeAt(fromIndex)
        nextQueue.add(toIndex, moved)
        val nextIndex = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }.coerceIn(0, nextQueue.lastIndex)
        return copy(
            queue = nextQueue,
            currentIndex = nextIndex,
            order = PlaybackOrderState(
                sourceIds = nextQueue.map { it.id },
                playbackIds = nextQueue.map { it.id },
                currentId = nextQueue.getOrNull(nextIndex)?.id,
                shuffleEnabled = order.shuffleEnabled,
            ),
        )
    }

    fun removeAt(index: Int): PlaybackQueueModel {
        if (index !in queue.indices) return this
        val nextQueue = queue.toMutableList().also { it.removeAt(index) }
        val nextIndex = when {
            nextQueue.isEmpty() -> 0
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> index.coerceAtMost(nextQueue.lastIndex)
            else -> currentIndex
        }.let { if (nextQueue.isEmpty()) 0 else it.coerceIn(0, nextQueue.lastIndex) }
        return copy(
            queue = nextQueue,
            currentIndex = nextIndex,
            order = PlaybackOrderState(
                sourceIds = nextQueue.map { it.id },
                playbackIds = nextQueue.map { it.id },
                currentId = nextQueue.getOrNull(nextIndex)?.id,
                shuffleEnabled = order.shuffleEnabled,
            ),
        )
    }
}
